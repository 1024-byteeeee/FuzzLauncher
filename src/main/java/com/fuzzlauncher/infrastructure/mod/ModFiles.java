package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.ModFile;
import com.fuzzlauncher.infrastructure.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Local archive operations. Every publication refuses to overwrite an existing file. */
public final class ModFiles {
    private static final String DISABLED = ".disabled";
    private static final int CACHE_CAPACITY = 1024;
    private final ModMetadataReader reader = new ModMetadataReader();
    private final Map<Path, ModFile> cache = new LinkedHashMap<>(32, 0.75f, true);
    private final Recycler recycler;

    @FunctionalInterface
    public interface Recycler {
        void recycle(Path path) throws IOException;
    }

    public ModFiles() {
        this(BrowserFiles::recycle);
    }

    public ModFiles(Recycler recycler) {
        this.recycler = Objects.requireNonNull(recycler);
    }

    public record Report(int completed, List<String> failures) {
        public Report {
            failures = List.copyOf(failures);
        }
    }

    private record Prepared(Path temporary, Path target) {
    }

    public static boolean supported(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = logicalName(path.getFileName().toString());
        return name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".litemod");
    }

    public static String logicalName(String name) {
        name = name.toLowerCase(Locale.ROOT);
        return name.endsWith(DISABLED) ? name.substring(0, name.length() - DISABLED.length()) : name;
    }

    public List<ModFile> scan(Path directory, TaskContext task) throws IOException {
        task.checkCancelled();
        checkAncestors(directory);
        if (!Files.exists(directory)) {
            return List.of();
        }
        List<ModFile> result = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                task.checkCancelled();
                if (!supported(path) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    ordinary(path);
                    result.add(inspect(path, task));
                } catch (IOException | IllegalArgumentException ex) {
                    result.add(new ModFile(path, !disabled(path), false, 0, 0, "", List.of(), "文件无法读取：" + ex.getMessage(), null));
                }
            }
        }
        result.sort(Comparator.comparing(ModFile::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(mod -> mod.path().getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private ModFile inspect(Path path, TaskContext task) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String identity = identity(attributes);
        synchronized (cache) {
            ModFile cached = cache.get(path);
            if (cached != null && cached.identity().equals(identity) && cached.size() == attributes.size()) {
                return cached;
            }
        }
        var parsed = reader.read(path, task);
        task.checkCancelled();
        var result = new ModFile(path, !disabled(path), parsed.readable(), attributes.size(), attributes.lastModifiedTime().toMillis(),
            identity, parsed.metadata(), parsed.problem(), parsed.icon());
        if (identity.equals(identity(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)))) {
            synchronized (cache) {
                cache.put(path, result);
                while (cache.size() > CACHE_CAPACITY) {
                    cache.remove(cache.keySet().iterator().next());
                }
            }
        }
        return result;
    }

    public Report setEnabled(Path directory, List<ModFile> selected, boolean enabled, TaskContext task) throws Exception {
        return change(directory, selected, task, mod -> {
            if (mod.enabled() == enabled) {
                return;
            }
            String name = mod.path().getFileName().toString();
            Path target = BrowserFiles.child(directory, enabled ? name.substring(0, name.length() - DISABLED.length()) : name + DISABLED);
            Files.move(mod.path(), target);
        });
    }

    public Report delete(Path directory, List<ModFile> selected, TaskContext task) throws Exception {
        return change(directory, selected, task, mod -> recycler.recycle(mod.path()));
    }

    @FunctionalInterface
    private interface FileChange {
        void apply(ModFile mod) throws IOException;
    }

    private Report change(Path directory, List<ModFile> selected, TaskContext task, FileChange action) throws Exception {
        task.checkCancelled();
        checkAncestors(directory);
        Path real = directory.toRealPath();
        List<String> failures = new ArrayList<>();
        int[] completed = { 0 };
        task.commit(() -> {
            for (ModFile mod : selected) {
                task.report("处理 " + mod.path().getFileName(), (double) completed[0] / Math.max(1, selected.size()));
                try {
                    verify(real, mod);
                    action.apply(mod);
                    completed[0]++;
                    synchronized (cache) {
                        cache.remove(mod.path());
                    }
                } catch (IOException ex) {
                    failures.add(mod.path().getFileName() + "：" + ex.getMessage());
                }
            }
        });
        return new Report(completed[0], failures);
    }

    public Report importFiles(Path directory, List<Path> sources, boolean renameCollisions, TaskContext task) throws Exception {
        task.checkCancelled();
        checkAncestors(directory);
        Files.createDirectories(directory);
        Path real = directory.toRealPath();
        Path staging = Files.createTempDirectory(real, ".fuzz-mod-import-");
        List<Prepared> prepared = new ArrayList<>();
        List<Path> temporaryFiles = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Set<String> reserved = new HashSet<>();
        int[] completed = { 0 };
        Throwable primaryFailure = null;
        try {
            try (var existing = Files.newDirectoryStream(real)) {
                for (Path path : existing) {
                    task.checkCancelled();
                    reserved.add(logicalName(path.getFileName().toString()));
                }
            }
            for (Path source : sources) {
                task.checkCancelled();
                try {
                    ordinary(source);
                    if (!supported(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("请选择模组 JAR 或 ZIP 文件");
                    }
                    if (source.toRealPath().getParent().equals(real)) {
                        throw new IOException("文件已经位于此模组目录");
                    }
                    Path target = destination(real, source.getFileName().toString(), reserved, renameCollisions);
                    Path copy = staging.resolve(Integer.toString(temporaryFiles.size()));
                    temporaryFiles.add(copy);
                    task.report("导入 " + source.getFileName(), (double) prepared.size() / Math.max(1, sources.size()));
                    CancellableFiles.copy(source, copy, task);
                    if (!reader.read(copy, task).readable()) {
                        throw new IOException("压缩文件损坏");
                    }
                    prepared.add(new Prepared(copy, target));
                    reserved.add(logicalName(target.getFileName().toString()));
                } catch (IOException | IllegalArgumentException ex) {
                    failures.add(source.getFileName() + "：" + ex.getMessage());
                }
            }
            task.commit(() -> {
                checkAncestors(real);
                for (Prepared file : prepared) {
                    try {
                        if (Files.exists(counterpart(file.target), LinkOption.NOFOLLOW_LINKS)) {
                            throw new FileAlreadyExistsException("同名启用或禁用文件已存在");
                        }
                        Files.move(file.temporary, file.target);
                        completed[0]++;
                    } catch (IOException ex) {
                        failures.add(file.target.getFileName() + "：" + ex.getMessage());
                    }
                }
            });
            return new Report(completed[0], failures);
        } catch (Exception | Error ex) {
            primaryFailure = ex;
            throw ex;
        } finally {
            IOException failure = null;
            for (Path path : temporaryFiles) {
                try {
                    if (Files.exists(path) && Files.readAttributes(path, java.nio.file.attribute.DosFileAttributes.class).isReadOnly()) {
                        Files.setAttribute(path, "dos:readonly", false);
                    }
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    if (failure == null) {
                        failure = ex;
                    } else {
                        failure.addSuppressed(ex);
                    }
                }
            }
            try {
                Files.deleteIfExists(staging);
            } catch (IOException ex) {
                if (failure == null) {
                    failure = ex;
                } else {
                    failure.addSuppressed(ex);
                }
            }
            if (failure != null) {
                IOException cleanup = new IOException("无法清理模组导入临时目录：" + staging, failure);
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    private static Path destination(Path directory, String name, Set<String> reserved, boolean rename) throws IOException {
        Path target = BrowserFiles.child(directory, name);
        if (!reserved.contains(logicalName(name))) {
            return target;
        }
        if (!rename) {
            throw new FileAlreadyExistsException("同名模组已存在，已跳过");
        }
        boolean disabled = disabled(target);
        String archive = disabled ? name.substring(0, name.length() - DISABLED.length()) : name;
        int dot = archive.lastIndexOf('.');
        for (int i = 2; i < Integer.MAX_VALUE; i++) {
            String candidate = archive.substring(0, dot) + " (" + i + ")" + archive.substring(dot) + (disabled ? DISABLED : "");
            if (!reserved.contains(logicalName(candidate))) {
                return BrowserFiles.child(directory, candidate);
            }
        }
        throw new IOException("无法生成可用文件名");
    }

    private static Path counterpart(Path path) {
        String name = path.getFileName().toString();
        return path.resolveSibling(disabled(path) ? name.substring(0, name.length() - DISABLED.length()) : name + DISABLED);
    }

    private static boolean disabled(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(DISABLED);
    }

    private static void verify(Path directory, ModFile mod) throws IOException {
        Path path = mod.path().toAbsolutePath().normalize();
        ordinary(path);
        if (!path.toRealPath().getParent().equals(directory) || !supported(path)) {
            throw new IOException("文件不属于当前模组目录");
        }
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != mod.size() || !identity(attributes).equals(mod.identity())) {
            throw new IOException("文件已发生变化，请刷新列表后重试");
        }
    }

    private static String identity(BasicFileAttributes attributes) {
        return attributes.fileKey() + "|" + attributes.creationTime() + "|" + attributes.lastModifiedTime();
    }

    private static void checkAncestors(Path path) throws IOException {
        for (Path parent = path.toAbsolutePath().normalize(); parent != null; parent = parent.getParent()) {
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                ordinary(parent);
            }
        }
    }

    private static void ordinary(Path path) throws IOException {
        int flags = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(path.toString());
        if (flags == com.sun.jna.platform.win32.WinBase.INVALID_FILE_ATTRIBUTES
            || (flags & com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            throw new IOException("路径是链接或无法读取：" + path);
        }
    }
}
