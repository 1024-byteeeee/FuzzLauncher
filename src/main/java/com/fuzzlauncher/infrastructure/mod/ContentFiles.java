package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.application.model.FileOperationResult;
import com.fuzzlauncher.application.port.ContentRepository;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.content.ContentFile;
import com.fuzzlauncher.domain.content.ContentKind;
import com.fuzzlauncher.domain.mod.ModFileNames;
import com.fuzzlauncher.infrastructure.filesystem.BrowserFiles;
import com.fuzzlauncher.infrastructure.filesystem.CancellableFiles;
import com.fuzzlauncher.infrastructure.platform.windows.WindowsPaths;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.*;

/** Local archive operations. Every publication refuses to overwrite an existing file. */
public final class ContentFiles implements ContentRepository {
    private static final String DISABLED = ".disabled";
    private static final int CACHE_CAPACITY = 1024;
    private final ModMetadataReader reader = new ModMetadataReader();
    private final Map<Path, ContentFile> cache = new LinkedHashMap<>(32, 0.75f, true);
    private final Recycler recycler;
    private final ContentKind kind;

    @FunctionalInterface
    public interface Recycler {
        void recycle(Path path) throws IOException;
    }

    public ContentFiles() {
        this(BrowserFiles::recycle);
    }

    public ContentFiles(Recycler recycler) {
        this(ContentKind.MODS, recycler);
    }

    public ContentFiles(ContentKind kind) {
        this(kind, BrowserFiles::recycle);
    }

    private ContentFiles(ContentKind kind, Recycler recycler) {
        this.kind = Objects.requireNonNull(kind);
        this.recycler = Objects.requireNonNull(recycler);
    }

    private record Prepared(Path temporary, Path target) {
    }

    public static boolean supported(Path path) {
        return ModFileNames.supported(path);
    }

    public static String logicalName(String name) {
        return ModFileNames.logicalName(name);
    }

    @Override
    public Path directory(Path gameDirectory, ContentKind contentKind) {
        Path fallback = ContentRepository.super.directory(gameDirectory, contentKind);
        return kind == ContentKind.SCHEMATICS ? SchematicDirectory.resolve(gameDirectory, fallback) : fallback;
    }

    public List<ContentFile> scan(Path directory, TaskContext task) throws IOException {
        task.checkCancelled();
        checkAncestors(directory);
        if (!Files.exists(directory)) {
            return List.of();
        }
        List<ContentFile> result = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                task.checkCancelled();
                if (!accepts(path)) {
                    continue;
                }
                try {
                    ordinary(path);
                    result.add(inspect(path, task));
                } catch (IOException | IllegalArgumentException ex) {
                    result.add(new ContentFile(path, !disabled(path), false, 0, 0, "", List.of(), "文件无法读取：" + ex.getMessage(), null, false));
                }
            }
        }
        result.sort(Comparator.comparing(ContentFile::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(mod -> mod.path().getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private ContentFile inspect(Path path, TaskContext task) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String identity = identity(attributes);
        synchronized (cache) {
            ContentFile cached = cache.get(path);
            if (!attributes.isDirectory() && cached != null && cached.identity().equals(identity) && cached.size() == attributes.size()) {
                return cached;
            }
        }
        var parsed = read(path, path.getFileName().toString(), task);
        task.checkCancelled();
        var result = new ContentFile(path, enabled(path), parsed.readable(), attributes.size(), attributes.lastModifiedTime().toMillis(),
            identity, parsed.metadata(), parsed.problem(), parsed.icon(), attributes.isDirectory());
        if (!attributes.isDirectory() && identity.equals(identity(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)))) {
            synchronized (cache) {
                cache.put(path, result);
                while (cache.size() > CACHE_CAPACITY) {
                    cache.remove(cache.keySet().iterator().next());
                }
            }
        }
        return result;
    }

    private ContentInspection read(Path path, String name, TaskContext task) throws IOException {
        return switch (kind) {
            case MODS -> reader.read(path, task);
            case RESOURCE_PACKS -> ResourcePackMetadataReader.read(path, name, task);
            case SCHEMATICS -> ContentInspection.WITHOUT_METADATA;
        };
    }

    private boolean accepts(Path path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            if (kind == ContentKind.SCHEMATICS) {
                return true;
            }
            return kind.supportsFolders() && (Files.exists(path.resolve(ResourcePackMetadataReader.METADATA), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(path.resolve(ResourcePackMetadataReader.DISABLED_METADATA), LinkOption.NOFOLLOW_LINKS));
        }
        return kind.supportsArchive(path);
    }

    private static boolean enabled(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            ? Files.isRegularFile(path.resolve(ResourcePackMetadataReader.METADATA), LinkOption.NOFOLLOW_LINKS) : !disabled(path);
    }

    public FileOperationResult setEnabled(Path directory, List<ContentFile> selected, boolean enabled, TaskContext task) throws Exception {
        if (kind == ContentKind.SCHEMATICS) {
            throw new IOException("原理图没有启用状态");
        }
        return change(directory, selected, task, mod -> {
            if (mod.enabled() == enabled) {
                return;
            }
            if (Files.isDirectory(mod.path(), LinkOption.NOFOLLOW_LINKS)) {
                Path active = mod.path().resolve(ResourcePackMetadataReader.METADATA);
                Path inactive = mod.path().resolve(ResourcePackMetadataReader.DISABLED_METADATA);
                Path source = enabled ? inactive : active;
                ordinary(source);
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("资源包描述不是普通文件");
                }
                Files.move(source, enabled ? active : inactive);
                return;
            }
            String name = mod.path().getFileName().toString();
            Path target = BrowserFiles.child(directory, enabled ? name.substring(0, name.length() - DISABLED.length()) : name + DISABLED);
            Files.move(mod.path(), target);
        });
    }

    public FileOperationResult delete(Path directory, List<ContentFile> selected, TaskContext task) throws Exception {
        return change(directory, selected, task, mod -> recycler.recycle(mod.path()));
    }

    public void createFolder(Path directory, String name, TaskContext task) throws Exception {
        task.commit(() -> {
            checkAncestors(directory);
            Files.createDirectories(directory);
            BrowserFiles.createFolder(directory, name);
        });
    }

    @FunctionalInterface
    private interface FileChange {
        void apply(ContentFile mod) throws IOException;
    }

    private FileOperationResult change(Path directory, List<ContentFile> selected, TaskContext task, FileChange action) throws Exception {
        task.checkCancelled();
        checkAncestors(directory);
        Path real = directory.toRealPath();
        List<String> failures = new ArrayList<>();
        int[] completed = { 0 };
        task.commit(() -> {
            for (ContentFile mod : selected) {
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
        return new FileOperationResult(completed[0], failures);
    }

    public FileOperationResult importFiles(Path directory, List<Path> sources, boolean renameCollisions, TaskContext task)
        throws Exception {
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
                    boolean folder = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
                    if (!accepts(source) || (folder ? !kind.supportsFolders() : !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))) {
                        throw new IOException("请选择受支持的文件或文件夹");
                    }
                    Path sourceReal = source.toRealPath();
                    if (real.equals(sourceReal.getParent()) || (folder && real.startsWith(sourceReal))) {
                        throw new IOException("来源与目标目录重叠");
                    }
                    Path target = destination(real, source.getFileName().toString(), reserved, renameCollisions, folder);
                    Path copy = staging.resolve(Integer.toString(temporaryFiles.size()));
                    temporaryFiles.add(copy);
                    task.report("导入 " + source.getFileName(), (double) prepared.size() / Math.max(1, sources.size()));
                    if (folder) {
                        copyFolder(sourceReal, copy, temporaryFiles, task);
                    } else {
                        CancellableFiles.copy(source, copy, task);
                    }
                    var metadata = read(copy, source.getFileName().toString(), task);
                    if (!metadata.readable()) {
                        throw new IOException(metadata.problem());
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
            return new FileOperationResult(completed[0], failures);
        } catch (Exception | Error ex) {
            primaryFailure = ex;
            throw ex;
        } finally {
            IOException failure = null;
            for (Path path : temporaryFiles.reversed()) {
                try {
                    if (Files.exists(path) && Files.readAttributes(path, DosFileAttributes.class).isReadOnly()) {
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
                IOException cleanup = new IOException("无法清理导入临时目录：" + staging, failure);
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    private static void copyFolder(Path source, Path target, List<Path> temporaryFiles, TaskContext task) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                task.checkCancelled();
                ordinary(path);
                Path copy = target.resolve(source.relativize(path));
                if (!path.equals(source)) {
                    temporaryFiles.add(copy);
                }
                Files.createDirectory(copy);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                ordinary(path);
                if (!attributes.isRegularFile()) {
                    throw new IOException("资源包包含非普通文件：" + path);
                }
                Path copy = target.resolve(source.relativize(path));
                temporaryFiles.add(copy);
                CancellableFiles.copy(path, copy, task);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Path destination(Path directory, String name, Set<String> reserved, boolean rename, boolean folder) throws IOException {
        Path target = BrowserFiles.child(directory, name);
        if (!reserved.contains(logicalName(name))) {
            return target;
        }
        if (!rename) {
            throw new FileAlreadyExistsException("同名条目已存在，已跳过");
        }
        boolean disabled = disabled(target);
        String archive = disabled ? name.substring(0, name.length() - DISABLED.length()) : name;
        int dot = folder ? archive.length() : archive.lastIndexOf('.');
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

    private void verify(Path directory, ContentFile mod) throws IOException {
        Path path = mod.path().toAbsolutePath().normalize();
        ordinary(path);
        if (!path.toRealPath().getParent().equals(directory) || !accepts(path)) {
            throw new IOException("条目不属于当前管理目录");
        }
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if ((!attributes.isRegularFile() && !attributes.isDirectory()) || attributes.size() != mod.size()
            || !identity(attributes).equals(mod.identity()) || enabled(path) != mod.enabled()) {
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

    static void ordinary(Path path) throws IOException {
        int flags = Kernel32.INSTANCE.GetFileAttributes(WindowsPaths.extended(path));
        if (flags == WinBase.INVALID_FILE_ATTRIBUTES
            || (flags & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            throw new IOException("路径是链接或无法读取：" + path);
        }
    }
}
