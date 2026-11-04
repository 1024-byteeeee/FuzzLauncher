package com.fuzzlauncher.infrastructure.filesystem;

import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.infrastructure.persistence.JsonFiles;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public final class ZipFiles {
    private ZipFiles() {
    }

    public static void extract(Path zip, Path target, boolean natives, TaskContext task) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                task.checkCancelled();
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || natives && entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                Path destination = JsonFiles.safeResolve(target, entry.getName());
                Files.createDirectories(destination.getParent());
                try (InputStream stream = file.getInputStream(entry)) {
                    Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
