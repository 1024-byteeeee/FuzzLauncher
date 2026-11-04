package com.fuzzlauncher.infrastructure.filesystem;

import java.io.IOException;
import java.nio.file.*;

/** Resolves existing ancestors without requiring the destination to exist. */
public final class PhysicalPaths {
    private PhysicalPaths() {
    }

    public static Path physical(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        Path existing = path;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("找不到目录所在磁盘或上级目录：" + path);
        }
        return existing.toRealPath().resolve(existing.relativize(path)).normalize();
    }

}
