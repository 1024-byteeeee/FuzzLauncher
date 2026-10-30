package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.application.InstanceRepository;
import com.fuzzlauncher.domain.GameInstance;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Name-based instance folders, with reversible moves and no replacement of existing data. */
public final class InstanceDirectories {
    public static final String PROFILE = ".fuzz-instance.json";

    private InstanceDirectories() {
    }

    public static Path directory(Path root, GameInstance instance) {
        return root.toAbsolutePath().normalize().resolve("versions").resolve(instance.name());
    }

    public static Path profile(Path root, GameInstance instance) {
        return directory(root, instance).resolve(PROFILE);
    }

    public static void checkAvailable(Path root, GameInstance instance, List<GameInstance> registered) throws IOException {
        for (GameInstance other : registered) {
            if (!other.id().equals(instance.id()) && other.name().equalsIgnoreCase(instance.name())) {
                throw new IOException("实例名称已存在：" + instance.name());
            }
        }
        checkTarget(directory(root, instance), instance.id());
    }

    private static boolean owns(Path folder, String id) throws IOException {
        Path marker = folder.resolve(PROFILE);
        return Files.isRegularFile(marker) && id.equals(JsonFiles.string(JsonFiles.read(marker), "id", ""));
    }

    private static void checkTarget(Path folder, String id) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        if (Files.isSymbolicLink(folder) || !Files.isDirectory(folder)) {
            throw new IOException("实例目录不是普通文件夹：" + folder);
        }
        if (Files.exists(folder.resolve(PROFILE))) {
            if (owns(folder, id)) {
                return;
            }
            throw new IOException("此实例名对应的目录已被另一个实例使用：" + folder);
        }
        // A vanilla version folder can also contain the instance using that exact display name.
        String base = folder.getFileName().toString();
        try (var children = Files.list(folder)) {
            for (Path child : children.toList()) {
                if (!child.getFileName().toString().equals(base + ".json") && !child.getFileName().toString().equals(base + ".jar")) {
                    throw new IOException("实例目录已有未登记的数据，请换一个实例名：" + folder);
                }
            }
        }
    }

    public static void migrate(Path root, GameInstance instance) throws IOException {
        Path target = directory(root, instance);
        if (owns(target, instance.id())) {
            return;
        }
        Path legacy = root.toAbsolutePath().normalize().resolve("versions").resolve(instance.id());
        if (!Files.isRegularFile(legacy.resolve(instance.id() + ".json"))) {
            return;
        }
        checkTarget(target, instance.id());
        try (Relocation move = new Relocation(root)) {
            move.relocate(legacy, target);
            move.move(target.resolve(instance.id() + ".json"), target.resolve(PROFILE));
            move.commit();
        }
    }

    public static void save(Path root, GameInstance previous, GameInstance updated, InstanceRepository repository) throws IOException {
        Path oldFolder = directory(root, previous), target = directory(root, updated);
        for (GameInstance other : repository.findAll()) {
            if (!other.id().equals(updated.id()) && other.name().equalsIgnoreCase(updated.name())) {
                throw new IOException("实例名称已存在：" + updated.name());
            }
        }
        if (previous.name().equals(updated.name())) {
            migrate(root, previous);
            repository.save(updated);
            return;
        }
        Path source = owns(oldFolder, previous.id()) ? oldFolder
            : root.toAbsolutePath().normalize().resolve("versions").resolve(previous.id());
        if (!Files.exists(source)) {
            checkTarget(target, updated.id());
            repository.save(updated);
            return;
        }
        if (!owns(source, previous.id()) && !Files.isRegularFile(source.resolve(previous.id() + ".json"))) {
            throw new IOException("原实例目录缺少身份描述，不能自动移动：" + source);
        }
        boolean sameFolder = Files.exists(target) && Files.isSameFile(source, target);
        if (!sameFolder) {
            checkTarget(target, updated.id());
        }
        try (Relocation move = new Relocation(root)) {
            move.relocate(source, target);
            Path legacyProfile = target.resolve(previous.id() + ".json");
            if (!Files.exists(target.resolve(PROFILE)) && Files.exists(legacyProfile)) {
                move.move(legacyProfile, target.resolve(PROFILE));
            }
            repository.save(updated);
            move.commit();
        }
    }

    private static final class Relocation implements AutoCloseable {
        private record Entry(Path from, Path to) {
        }

        private final Path versions;
        private final List<Entry> moved = new ArrayList<>();
        private final Set<Path> cleanup = new LinkedHashSet<>();
        private boolean committed;

        Relocation(Path root) {
            versions = root.toAbsolutePath().normalize().resolve("versions");
        }

        void move(Path from, Path to) throws IOException {
            from = from.toAbsolutePath().normalize();
            to = to.toAbsolutePath().normalize();
            if (!from.startsWith(versions) || !to.startsWith(versions) || from.equals(versions) || to.equals(versions)
                || Files.isSymbolicLink(from) || Files.isSymbolicLink(to)) {
                throw new IOException("实例目录移动路径无效");
            }
            Files.move(from, to);
            moved.add(new Entry(from, to));
        }

        void relocate(Path from, Path to) throws IOException {
            if (from.toString().equals(to.toString())) {
                return;
            }
            if (Files.exists(to) && Files.isSameFile(from, to)) {
                Path temporary = versions.resolve(".fuzz-rename-" + UUID.randomUUID());
                move(from, temporary);
                move(temporary, to);
                return;
            }
            boolean sharedBase = Files.isRegularFile(from.resolve(PROFILE))
                && Files.isRegularFile(from.resolve(from.getFileName() + ".json"));
            if (!sharedBase && !Files.exists(to)) {
                move(from, to);
                return;
            }
            if (Files.isSymbolicLink(from) || Files.isSymbolicLink(to)) {
                throw new IOException("不能迁移符号链接实例目录");
            }
            String base = from.getFileName().toString();
            List<Path> children;
            try (var files = Files.list(from)) {
                children = files
                    .filter(path -> !sharedBase
                        || !(path.getFileName().toString().equals(base + ".json") || path.getFileName().toString().equals(base + ".jar")))
                    .toList();
            }
            for (Path child : children) {
                if (Files.exists(to.resolve(child.getFileName()))) {
                    throw new IOException("目标目录已有同名文件，不会覆盖：" + child.getFileName());
                }
            }
            if (!Files.exists(to)) {
                Files.createDirectory(to);
                cleanup.add(to);
            }
            cleanup.add(from);
            for (Path child : children) {
                move(child, to.resolve(child.getFileName()));
            }
        }

        void commit() {
            committed = true;
        }

        @Override
        public void close() throws IOException {
            if (!committed) {
                for (int i = moved.size() - 1; i >= 0; i--) {
                    Entry entry = moved.get(i);
                    if (Files.exists(entry.to()) && !Files.exists(entry.from())) {
                        Files.move(entry.to(), entry.from());
                    }
                }
            }
            for (Path folder : cleanup) {
                if (Files.isDirectory(folder) && !Files.isSymbolicLink(folder)) {
                    boolean empty;
                    try (var children = Files.list(folder)) {
                        empty = children.findAny().isEmpty();
                    }
                    if (empty) {
                        Files.delete(folder);
                    }
                }
            }
        }
    }
}
