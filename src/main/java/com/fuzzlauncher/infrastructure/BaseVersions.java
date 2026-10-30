package com.fuzzlauncher.infrastructure;

import java.io.IOException;
import java.nio.file.*;

/** Shared vanilla descriptors and clients, separate from named instance folders. */
public final class BaseVersions {
    private final Path directory;

    /** Standalone services keep their cache inside the supplied workspace. */
    public BaseVersions() {
        directory = null;
    }

    public BaseVersions(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path directory(Path root) {
        return directory == null ? root.toAbsolutePath().normalize().resolve("baseVerisons") : directory;
    }

    public Path version(Path root, String id) throws IOException {
        return JsonFiles.safeResolve(directory(root), id);
    }

    public Path metadata(Path root, String id) throws IOException {
        return JsonFiles.safeResolve(version(root, id), id + ".json");
    }

    public Path client(Path root, String id) throws IOException {
        return JsonFiles.safeResolve(version(root, id), id + ".jar");
    }

    /** Move only known base files. Other instance data is never recursively moved or deleted. */
    public void migrate(Path root, String id) throws IOException {
        Path versions = root.toAbsolutePath().normalize().resolve("versions");
        Path legacy = JsonFiles.safeResolve(versions, id);
        if (!Files.isDirectory(legacy) || Files.isSymbolicLink(legacy)) {
            return;
        }
        if (!legacy.toRealPath().startsWith(versions.toRealPath())) {
            throw new IOException("基础版本目录指向外部路径");
        }
        Path oldJson = JsonFiles.safeResolve(legacy, id + ".json");
        if (!Files.isRegularFile(oldJson) || Files.isSymbolicLink(oldJson)) {
            return;
        }
        boolean owned = Files.isRegularFile(legacy.resolve(InstanceDirectories.PROFILE));
        boolean baseOnly;
        try (var children = Files.list(legacy)) {
            baseOnly = children
                .allMatch(path -> path.getFileName().toString().equals(id + ".json") || path.getFileName().toString().equals(id + ".jar"));
        }
        for (String suffix : new String[] { ".json", ".jar" }) {
            Path source = JsonFiles.safeResolve(legacy, id + suffix);
            if (!Files.isRegularFile(source) || Files.isSymbolicLink(source)) {
                continue;
            }
            Path target = JsonFiles.safeResolve(version(root, id), id + suffix);
            Files.createDirectories(target.getParent());
            if (Files.isSymbolicLink(target) || !target.getParent().toRealPath().startsWith(directory(root).toRealPath())) {
                throw new IOException("基础版本缓存路径指向外部位置");
            }
            if (Files.exists(target) && Files.isSameFile(source, target)) {
                continue;
            }
            if (!Files.exists(target)) {
                Path temporary = Files.createTempFile(target.getParent(), ".base-", ".tmp");
                try {
                    Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(temporary, target);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            if ((owned || baseOnly) && Files.mismatch(source, target) == -1) {
                Files.delete(source);
            }
        }
        try (var children = Files.list(legacy)) {
            if (children.findAny().isPresent()) {
                return;
            }
        }
        Files.delete(legacy);
    }
}
