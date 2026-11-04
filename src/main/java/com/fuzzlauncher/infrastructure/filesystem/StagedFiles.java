package com.fuzzlauncher.infrastructure.filesystem;

import com.fuzzlauncher.application.task.TaskContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Checksums belong to one private staging tree and are reused only for the same file generation. */
public final class StagedFiles {
    private record Generation(Object key, long size, FileTime modified, FileTime created) {
    }

    private record Entry(Generation generation, Map<String, String> hashes) {
    }

    private final Map<Path, Entry> entries = new HashMap<>();
    private final byte[] buffer = new byte[128 * 1024];

    public void invalidate(Path file) {
        entries.remove(file.toAbsolutePath().normalize());
    }

    public long write(InputStream input, Path target, long limit, TaskContext task) throws IOException {
        task.checkCancelled();
        Path path = target.toAbsolutePath().normalize();
        invalidate(path);
        Map<String, MessageDigest> hashes = hashes(false);
        long total = 0;
        try (OutputStream output = Files.newOutputStream(path)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                task.checkCancelled();
                if (count > limit - total) {
                    throw new IOException("整合包展开大小超过限制");
                }
                output.write(buffer, 0, count);
                update(hashes, buffer, count);
                total += count;
            }
        }
        task.checkCancelled();
        entries.put(path, new Entry(generation(path), finish(hashes)));
        return total;
    }

    public String digest(Path file, String algorithm, TaskContext task) throws IOException {
        task.checkCancelled();
        Path path = file.toAbsolutePath().normalize();
        Generation before = generation(path);
        Entry entry = entries.get(path);
        if (entry != null && entry.generation.equals(before) && entry.hashes.containsKey(algorithm)) {
            return entry.hashes.get(algorithm);
        }
        Map<String, MessageDigest> hashes = hashes(true);
        try (InputStream input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                task.checkCancelled();
                update(hashes, buffer, count);
            }
        }
        task.checkCancelled();
        if (!before.equals(generation(path))) {
            entries.remove(path);
            throw new IOException("暂存文件在校验期间被修改：" + path);
        }
        Map<String, String> values = finish(hashes);
        entries.put(path, new Entry(before, values));
        String result = values.get(algorithm);
        if (result == null) {
            throw new IllegalArgumentException("不支持的校验算法：" + algorithm);
        }
        return result;
    }

    public void verify(Path file, String expected, String algorithm, TaskContext task) throws IOException {
        if (!digest(file, algorithm, task).equalsIgnoreCase(expected)) {
            throw new IOException("整合包文件校验失败：" + file.getFileName());
        }
    }

    public void persist(Path root, TaskContext task) throws IOException {
        try (var files = Files.walk(root)) {
            var iterator = files.iterator();
            int completed = 0;
            while (iterator.hasNext()) {
                task.checkCancelled();
                Path path = iterator.next();
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                generation(path);
                task.report("持久化整合包文件 · " + ++completed + " 项", -1);
                try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
        }
        task.checkCancelled();
    }

    private static Generation generation(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("暂存路径不是普通文件：" + path);
        }
        return new Generation(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), attributes.creationTime());
    }

    private static Map<String, MessageDigest> hashes(boolean includeSha512) {
        Map<String, MessageDigest> result = new LinkedHashMap<>();
        try {
            for (String algorithm : includeSha512 ? List.of("SHA-1", "SHA-256", "SHA-512") : List.of("SHA-1", "SHA-256")) {
                result.put(algorithm, MessageDigest.getInstance(algorithm));
            }
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        return result;
    }

    private static void update(Map<String, MessageDigest> hashes, byte[] bytes, int count) {
        for (MessageDigest hash : hashes.values()) {
            hash.update(bytes, 0, count);
        }
    }

    private static Map<String, String> finish(Map<String, MessageDigest> hashes) {
        Map<String, String> result = new HashMap<>();
        hashes.forEach((algorithm, hash) -> result.put(algorithm, HexFormat.of().formatHex(hash.digest())));
        return Map.copyOf(result);
    }
}
