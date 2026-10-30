package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.application.TaskContext;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.Arrays;

/** Bounded copy/verification loops so cancellation does not wait for a whole large file. */
public final class CancellableFiles {
    private CancellableFiles() {
    }

    public static void copy(Path source, Path target, TaskContext task) throws IOException {
        task.checkCancelled();
        Files.createFile(target);
        boolean completed = false;
        try {
            try (InputStream input = Files.newInputStream(source); OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[128 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    task.checkCancelled();
                    output.write(buffer, 0, count);
                }
            }
            task.checkCancelled();
            try (var channel = java.nio.channels.FileChannel.open(target, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
            Files.getFileAttributeView(target, BasicFileAttributeView.class).setTimes(attributes.lastModifiedTime(),
                attributes.lastAccessTime(), attributes.creationTime());
            var sourceDos = Files.getFileAttributeView(source, DosFileAttributeView.class);
            var targetDos = Files.getFileAttributeView(target, DosFileAttributeView.class);
            if (sourceDos != null && targetDos != null) {
                DosFileAttributes dos = sourceDos.readAttributes();
                targetDos.setArchive(dos.isArchive());
                targetDos.setHidden(dos.isHidden());
                targetDos.setSystem(dos.isSystem());
                targetDos.setReadOnly(dos.isReadOnly());
            }
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(target);
            }
        }
    }

    public static boolean equal(Path first, Path second, TaskContext task) throws IOException {
        task.checkCancelled();
        if (Files.size(first) != Files.size(second)) {
            return false;
        }
        try (InputStream left = Files.newInputStream(first); InputStream right = Files.newInputStream(second)) {
            byte[] a = new byte[128 * 1024], b = new byte[a.length];
            while (true) {
                task.checkCancelled();
                int count = left.readNBytes(a, 0, a.length);
                if (count != right.readNBytes(b, 0, b.length) || !Arrays.equals(a, 0, count, b, 0, count)) {
                    return false;
                }
                if (count == 0) {
                    return true;
                }
            }
        }
    }
}
