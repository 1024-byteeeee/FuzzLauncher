package com.fuzzlauncher.infrastructure.filesystem;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

/** Bounded cleanup for disposable transaction workspaces. */
public final class WorkspaceFiles {
    private WorkspaceFiles() {
    }

    public static void deleteWorkspace(Path directory, Path parent) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        Path boundary = parent.toAbsolutePath().normalize();
        if (!normalized.startsWith(boundary) || normalized.equals(boundary) || Files.isSymbolicLink(normalized)) {
            throw new IOException("拒绝清理工作目录之外的路径");
        }
        if (!Files.exists(normalized)) {
            return;
        }
        try (var walk = Files.walk(normalized)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
