package com.fuzzlauncher.application;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.*;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Builds a completely fresh loader before committing files and the existing instance identity. */
public final class ReinstallLoader {
    private final InstanceRepository repository;
    private final GameFiles files;
    private final VersionResolver resolver;
    private final Map<LoaderType, LoaderInstaller> installers;

    public ReinstallLoader(InstanceRepository repository, GameFiles files, VersionResolver resolver,
        Map<LoaderType, LoaderInstaller> installers) {
        this.repository = repository;
        this.files = files;
        this.resolver = resolver;
        this.installers = Map.copyOf(installers);
    }

    public GameInstance execute(Path root, GameInstance original, String version, TaskContext task) throws Exception {
        if (original.loader() == LoaderType.VANILLA) {
            throw new IOException("原版实例不能修改加载器版本");
        }
        if (version == null || version.isBlank()) {
            throw new IOException("请选择加载器版本");
        }
        if (!repository.findAll().contains(original)) {
            throw new IOException("实例配置已改变，请刷新后重试");
        }
        root = root.toAbsolutePath().normalize();
        Path profile = InstanceDirectories.profile(root, original);
        resolver.resolveInstance(root, original); // Validate ownership and migrate legacy descriptors.
        checkPath(root, profile);
        byte[] oldProfile = Files.readAllBytes(profile);
        JsonObject old = JsonFiles.read(profile);
        JsonObject settings = JsonFiles.GSON.toJsonTree(original).getAsJsonObject();
        settings.addProperty("loaderVersion", version);
        GameInstance replacement = JsonFiles.GSON.fromJson(settings, GameInstance.class);
        Path stagingParent = root.resolve(".fuzz-staging");
        Files.createDirectories(stagingParent);
        checkPath(root, stagingParent);
        Path workspace = Files.createTempDirectory(stagingParent, "reinstall-");
        Path staged = workspace.resolve("game");
        Map<Path, Path> changed = new LinkedHashMap<>();
        boolean[] recoveryRequired = { false };
        Exception failure = null;
        try {
            Path originalProfile = workspace.resolve("original-profile.json");
            Files.write(originalProfile, oldProfile);
            JsonFiles.write(workspace.resolve("original-instances.json"), Map.of("instances", repository.findAll()));
            JsonObject vanilla = resolver.resolve(root, original.gameVersion());
            BaseVersions stagingBases = new BaseVersions();
            Path base = stagingBases.version(staged, original.gameVersion());
            Files.createDirectories(base);
            JsonFiles.write(base.resolve(original.gameVersion() + ".json"), vanilla);
            Path client = resolver.baseVersions().client(root, original.gameVersion());
            checkPath(resolver.baseVersions().directory(root), client);
            Files.copy(client, base.resolve(original.gameVersion() + ".jar"));
            Set<Path> vanillaPaths = libraryPaths(root, vanilla);
            // Only vanilla inputs are reusable. No old loader descriptor, JAR or generated output
            // is
            // seeded.
            for (Path relative : vanillaPaths) {
                task.checkCancelled();
                Path source = root.resolve(relative);
                if (Files.isRegularFile(source)) {
                    checkPath(root, source);
                    Files.createDirectories(staged.resolve(relative).getParent());
                    Files.copy(source, staged.resolve(relative));
                }
            }
            JsonObject fresh = Objects.requireNonNull(installers.get(original.loader()), "缺少加载器适配器").install(staged,
                workspace.resolve("processors"), new GameVersion(original.gameVersion(), "release", "", ""), version, stagingBases, task);
            fresh.addProperty("id", original.id());
            if (!fresh.has("inheritsFrom")) {
                fresh.addProperty("inheritsFrom", original.gameVersion());
            }
            fresh.add("fuzzInstance", JsonFiles.GSON.toJsonTree(replacement));
            JsonFiles.write(InstanceDirectories.profile(staged, replacement), fresh);
            JsonObject resolved = new VersionResolver(stagingBases).resolveInstance(staged, replacement);
            // The game, assets and logging configuration do not change during loader
            // reinstallation.
            resolved.remove("assetIndex");
            resolved.remove("logging");
            resolved.remove("downloads");
            files.prepare(staged, resolved, task);
            if (!fresh.has("mainClass") || fresh.get("mainClass").getAsString().isBlank()) {
                throw new IOException("新加载器缺少启动入口");
            }
            Set<Path> produced = new LinkedHashSet<>();
            Path libraries = staged.resolve("libraries");
            if (Files.isDirectory(libraries)) {
                try (var paths = Files.walk(libraries)) {
                    for (Path file : paths.filter(Files::isRegularFile).toList()) {
                        checkPath(staged, file);
                        produced.add(staged.relativize(file));
                    }
                }
            }
            JsonArray owned = new JsonArray();
            produced.stream().filter(path -> !vanillaPaths.contains(path)).forEach(path -> owned.add(path.toString().replace('\\', '/')));
            fresh.add("fuzzLoaderFiles", owned);
            Set<Path> otherReferences = references(root, profile);
            Set<Path> obsolete = libraryPaths(root, old);
            obsolete.removeAll(vanillaPaths);
            obsolete.removeAll(produced);
            obsolete.removeAll(otherReferences);
            Path committedRoot = root;
            task.checkCancelled();
            task.commit(() -> {
                task.report("正在提交全新加载器安装", -1);
                if (!repository.findAll().contains(original) || !Arrays.equals(oldProfile, Files.readAllBytes(profile))) {
                    throw new IOException("实例配置在安装期间发生变化，请刷新后重试");
                }
                try {
                    for (Path relative : produced) {
                        Path target = committedRoot.resolve(relative), source = staged.resolve(relative);
                        checkPath(committedRoot, target);
                        if (Files.exists(target) && !Files.isRegularFile(target)) {
                            throw new IOException("依赖路径不是普通文件，未覆盖：" + relative);
                        }
                        if (Files.isRegularFile(target) && Files.mismatch(source, target) == -1) {
                            continue;
                        }
                        if (Files.exists(target) && otherReferences.contains(relative)) {
                            throw new IOException("共享依赖与全新安装结果不同，未覆盖其他实例使用的文件：" + relative);
                        }
                        Path backup = null;
                        if (Files.exists(target)) {
                            backup = workspace.resolve("backup").resolve(relative);
                            Files.createDirectories(backup.getParent());
                            Files.copy(target, backup);
                        }
                        changed.put(target, backup);
                        publish(source, target);
                    }
                    JsonFiles.write(profile, fresh);
                    repository.save(replacement);
                } catch (Exception ex) {
                    for (var entry : new ArrayList<>(changed.entrySet()).reversed()) {
                        try {
                            if (entry.getValue() == null) {
                                Files.deleteIfExists(entry.getKey());
                            } else {
                                restore(entry.getValue(), entry.getKey());
                            }
                        } catch (IOException rollback) {
                            recoveryRequired[0] = true;
                            ex.addSuppressed(rollback);
                        }
                    }
                    try {
                        restore(originalProfile, profile);
                        repository.save(original);
                    } catch (IOException rollback) {
                        recoveryRequired[0] = true;
                        ex.addSuppressed(rollback);
                    }
                    throw ex;
                }
            });
            // Re-evaluate references after commit. Only old loader-owned, unreferenced files are
            // removed.
            try {
                obsolete.removeAll(references(root, null));
            } catch (IOException | RuntimeException ex) {
                obsolete.clear();
                LauncherLog.LOGGER.warning("无法确认旧依赖引用，已保留旧缓存：" + ex.getMessage());
            }
            for (Path relative : obsolete) {
                try {
                    checkPath(root, root.resolve(relative));
                    Files.deleteIfExists(root.resolve(relative));
                    Path parent = root.resolve(relative).getParent();
                    while (parent.startsWith(root.resolve("libraries")) && !parent.equals(root.resolve("libraries"))) {
                        try {
                            checkPath(root, parent);
                            Files.deleteIfExists(parent);
                        } catch (DirectoryNotEmptyException used) {
                            break;
                        }
                        parent = parent.getParent();
                    }
                } catch (IOException ex) {
                    LauncherLog.LOGGER.warning("旧加载器缓存保留：" + relative + "：" + ex.getMessage());
                }
            }
            task.report("加载器已完整重装：" + version, 1);
            return replacement;
        } catch (Exception ex) {
            if (recoveryRequired[0]) {
                IOException recovery = new IOException("加载器重装失败且回滚未完全完成，请保留备份目录以便恢复：" + workspace, ex);
                failure = recovery;
                throw recovery;
            }
            failure = ex;
            throw ex;
        } finally {
            try {
                if (!recoveryRequired[0]) {
                    deleteWorkspace(workspace);
                }
            } catch (IOException ex) {
                LauncherLog.LOGGER.warning("加载器临时目录清理失败：" + ex.getMessage());
                if (failure != null) {
                    failure.addSuppressed(ex);
                }
            }
            try {
                Files.deleteIfExists(stagingParent);
            } catch (DirectoryNotEmptyException ignored) {
                /* Other workspaces must be retained. */
            } catch (IOException ex) {
                LauncherLog.LOGGER.warning("加载器临时目录清理失败：" + ex.getMessage());
            }
        }
    }

    private Set<Path> references(Path root, Path excluded) throws IOException {
        Set<Path> result = new HashSet<>();
        Path versions = root.resolve("versions");
        if (!Files.exists(versions)) {
            return result;
        }
        try (var paths = Files.walk(versions, 2)) {
            for (Path file : paths
                .filter(path -> Files.isRegularFile(path) && (path.getFileName().toString().equals(InstanceDirectories.PROFILE)
                    || path.getFileName().toString().equals(path.getParent().getFileName() + ".json")))
                .toList()) {
                if (file.equals(excluded)) {
                    continue;
                }
                checkPath(root, file);
                result.addAll(libraryPaths(root, JsonFiles.read(file)));
            }
        }
        return result;
    }

    private static Set<Path> libraryPaths(Path root, JsonObject metadata) throws IOException {
        Set<Path> result = new LinkedHashSet<>();
        if (metadata.has("libraries")) {
            for (JsonElement item : metadata.getAsJsonArray("libraries")) {
                JsonObject library = item.getAsJsonObject();
                if (library.has("downloads")) {
                    JsonObject downloads = library.getAsJsonObject("downloads");
                    List<JsonObject> artifacts = new ArrayList<>();
                    if (downloads.has("artifact")) {
                        artifacts.add(downloads.getAsJsonObject("artifact"));
                    }
                    if (downloads.has("classifiers")) {
                        downloads.getAsJsonObject("classifiers").entrySet()
                            .forEach(entry -> artifacts.add(entry.getValue().getAsJsonObject()));
                    }
                    for (JsonObject artifact : artifacts) {
                        result.add(root.relativize(JsonFiles.safeResolve(root.resolve("libraries"), artifact.get("path").getAsString())));
                    }
                } else {
                    result.add(root.relativize(
                        JsonFiles.safeResolve(root.resolve("libraries"), VersionResolver.mavenPath(library.get("name").getAsString()))));
                }
            }
        }
        if (metadata.has("fuzzLoaderFiles")) {
            for (JsonElement file : metadata.getAsJsonArray("fuzzLoaderFiles")) {
                Path safe = JsonFiles.safeResolve(root, file.getAsString());
                if (!safe.startsWith(root.resolve("libraries")) || safe.equals(root.resolve("libraries"))) {
                    throw new IOException("加载器文件记录超出依赖目录");
                }
                result.add(root.relativize(safe));
            }
        }
        return result;
    }

    private static void checkPath(Path root, Path path) throws IOException {
        Path realRoot = root.toRealPath();
        Path existing = path;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (!existing.toRealPath().startsWith(realRoot)) {
            throw new IOException("加载器路径指向游戏目录之外");
        }
        for (Path current = existing; current != null && current.startsWith(root); current = current.getParent()) {
            int flags = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(current.toString());
            if (flags == com.sun.jna.platform.win32.WinBase.INVALID_FILE_ATTRIBUTES
                || (flags & com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                throw new IOException("加载器路径包含目录链接或无法读取的文件");
            }
        }
    }

    private static void publish(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".loader-", ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void restore(Path backup, Path target) throws IOException {
        // Same-volume rename avoids allocating another full copy when rollback follows a disk-full
        // error.
        try {
            Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteWorkspace(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                checkPath(root, path);
                return FileVisitResult.CONTINUE;
            }

            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }

            public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
