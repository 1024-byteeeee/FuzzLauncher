package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.game.VersionResolver;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Disk reconciliation and deletion scoped to one owned instance directory. */
public final class InstanceCatalog {
    private InstanceCatalog() {
    }

    public static void remember(Path root, GameInstance instance) throws IOException {
        Path marker = InstanceDirectories.profile(root, instance);
        if (!Files.isRegularFile(marker)) {
            return;
        }
        JsonObject json = JsonFiles.read(marker);
        if (!instance.id().equals(JsonFiles.string(json, "id", ""))) {
            throw new IOException("实例目录身份不匹配");
        }
        JsonElement saved = JsonFiles.GSON.toJsonTree(instance);
        if (!saved.equals(json.get("fuzzInstance"))) {
            json.add("fuzzInstance", saved);
            JsonFiles.write(marker, json);
        }
    }

    public static List<GameInstance> refresh(Path root, InstanceRepository repository) throws IOException {
        return refresh(root, repository, new BaseVersions());
    }

    public static List<GameInstance> refresh(Path root, InstanceRepository repository, BaseVersions baseVersions) throws IOException {
        List<GameInstance> result = scan(root, repository.findAll(), baseVersions);
        repository.replaceAll(result);
        return result;
    }

    public static List<GameInstance> scan(Path root, List<GameInstance> registered) throws IOException {
        return scan(root, registered, new BaseVersions());
    }

    public static List<GameInstance> scan(Path root, List<GameInstance> registered, BaseVersions baseVersions) throws IOException {
        Map<String, GameInstance> known = new HashMap<>();
        for (GameInstance item : registered) {
            known.put(item.id(), item);
        }
        List<GameInstance> result = new ArrayList<>();
        Path versions = root.toAbsolutePath().normalize().resolve("versions");
        if (Files.isDirectory(versions)) {
            try (var folders = Files.list(versions)) {
                for (Path folder : folders.filter(path -> Files.isDirectory(path) && !Files.isSymbolicLink(path)).sorted().toList()) {
                    Path marker = folder.resolve(InstanceDirectories.PROFILE);
                    if (!Files.isRegularFile(marker)) {
                        continue;
                    }
                    try {
                        JsonObject profile = JsonFiles.read(marker);
                        String id = profile.get("id").getAsString();
                        GameInstance item = known.get(id);
                        if (item == null && profile.has("fuzzInstance")) {
                            item = JsonFiles.GSON.fromJson(profile.get("fuzzInstance"), GameInstance.class);
                        }
                        if (item == null) {
                            continue;
                        }
                        item = item.withSettings(folder.getFileName().toString(), item.isolation(), item.memoryMb(), item.javaPath());
                        JsonObject resolved = new VersionResolver(baseVersions).resolveInstance(root, item);
                        Path client = resolved.has("_clientJarPath")
                            ? JsonFiles.safeResolve(root, resolved.get("_clientJarPath").getAsString())
                            : baseVersions.client(root, resolved.get("_clientVersion").getAsString());
                        if (Files.isRegularFile(client)) {
                            result.add(item);
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // A missing/broken version is unavailable. Refresh never deletes its
                        // remaining files.
                    }
                }
            }
        }
        return result;
    }

    public static void delete(Path root, GameInstance instance, InstanceRepository repository, TaskContext task) throws IOException {
        task.checkCancelled();
        InstanceDirectories.migrate(root, instance);
        Path versions = root.toAbsolutePath().normalize().resolve("versions");
        Path folder = InstanceDirectories.directory(root, instance);
        if (!folder.getParent().equals(versions) || Files.isSymbolicLink(folder)) {
            throw new IOException("拒绝删除不属于实例的目录");
        }
        if (!Files.exists(folder)) {
            repository.remove(instance.id());
            return;
        }
        Path realFolder = folder.toRealPath(), realVersions = versions.toRealPath();
        if (!realFolder.getParent().equals(realVersions)) {
            throw new IOException("实例目录链接指向外部，拒绝递归删除");
        }
        Path marker = folder.resolve(InstanceDirectories.PROFILE);
        if (!Files.isRegularFile(marker) || !instance.id().equals(JsonFiles.string(JsonFiles.read(marker), "id", ""))) {
            throw new IOException("实例目录身份无法确认，未删除任何文件");
        }
        String base = folder.getFileName().toString();
        Set<Path> keep = new HashSet<>();
        if (Files.isRegularFile(folder.resolve(base + ".json"))) {
            keep.add(folder.resolve(base + ".json"));
            keep.add(folder.resolve(base + ".jar"));
        }
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                task.checkCancelled();
                if (!directory.toRealPath().startsWith(realFolder)) {
                    throw new IOException("实例包含指向外部的目录链接，未删除链接目标");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                task.checkCancelled();
                if (!file.equals(marker) && !keep.contains(file)) {
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                task.checkCancelled();
                if (!directory.equals(folder)) {
                    Files.delete(directory);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        task.checkCancelled();
        Files.delete(marker);
        boolean empty;
        try (var children = Files.list(folder)) {
            empty = children.findAny().isEmpty();
        }
        if (empty) {
            Files.delete(folder);
        }
        repository.remove(instance.id());
    }
}
