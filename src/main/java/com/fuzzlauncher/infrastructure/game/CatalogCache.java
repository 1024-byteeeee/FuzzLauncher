package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.infrastructure.*;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Disposable metadata cache. Never used for authentication or installed game files. */
final class CatalogCache {
    static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final int CAPACITY = 128;
    private final Map<String, Entry<?>> memory = new LinkedHashMap<>(16, 0.75f, true);
    private final Path directory;
    private final Clock clock;

    record Entry<T>(long fetchedAt, List<T> values) {
        Entry {
            values = List.copyOf(values);
        }
    }

    CatalogCache(Path directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    boolean fresh(Entry<?> entry) {
        long age = clock.millis() - entry.fetchedAt;
        return age >= 0 && age < LIFETIME.toMillis();
    }

    @SuppressWarnings("unchecked")
    <T> Entry<T> read(String key, Class<T[]> type) {
        synchronized (memory) {
            Entry<?> entry = memory.get(key);
            if (entry != null) {
                return (Entry<T>) entry;
            }
        }
        if (directory == null) {
            return null;
        }
        Path file = file(key);
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            if (Files.size(file) > 16 * 1024 * 1024) {
                throw new IOException("版本缓存过大");
            }
            JsonObject json = JsonFiles.read(file);
            if (json.get("schema").getAsInt() != 1 || !key.equals(json.get("key").getAsString())) {
                throw new IOException("版本缓存格式不匹配");
            }
            Entry<T> entry = new Entry<>(json.get("fetchedAt").getAsLong(),
                Arrays.asList(JsonFiles.GSON.fromJson(json.get("values"), type)));
            if (!valid(entry.values)) {
                throw new IOException("版本缓存条目无效");
            }
            remember(key, entry);
            return entry;
        } catch (IOException | RuntimeException ex) {
            LauncherLog.LOGGER.fine("忽略版本缓存 " + file + "：" + ex.getMessage());
            return null;
        }
    }

    <T> List<T> write(String key, List<T> values) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("版本查询已取消");
        }
        if (!valid(values)) {
            throw new IllegalArgumentException("版本列表条目无效");
        }
        Entry<T> entry = new Entry<>(clock.millis(), values);
        remember(key, entry);
        if (directory != null) {
            try {
                JsonObject json = new JsonObject();
                json.addProperty("schema", 1);
                json.addProperty("key", key);
                json.addProperty("fetchedAt", entry.fetchedAt);
                json.add("values", JsonFiles.GSON.toJsonTree(entry.values));
                JsonFiles.write(file(key), json);
                try (var files = Files.list(directory)) {
                    List<Path> entries = files.filter(path -> path.getFileName().toString().matches("[0-9a-f]{64}\\.json"))
                        .sorted(Comparator.comparingLong(CatalogCache::modified).reversed()).toList();
                    for (Path old : entries.subList(Math.min(CAPACITY, entries.size()), entries.size())) {
                        Files.deleteIfExists(old);
                    }
                }
            } catch (IOException ex) {
                LauncherLog.LOGGER.fine("无法保存版本缓存：" + ex.getMessage());
            }
        }
        return entry.values;
    }

    private static boolean valid(List<?> values) {
        return values.stream().allMatch(value -> {
            if (value instanceof String text) {
                return !text.isBlank();
            }
            if (value instanceof com.fuzzlauncher.domain.GameVersion game) {
                return game.id() != null && !game.id().isBlank() && game.type() != null && !game.type().isBlank() && game.url() != null
                    && game.url().matches("https?://.+") && game.sha1() != null
                    && (game.sha1().isEmpty() || game.sha1().matches("[0-9a-fA-F]{40}"));
            }
            return false;
        });
    }

    private void remember(String key, Entry<?> entry) {
        synchronized (memory) {
            memory.put(key, entry);
            while (memory.size() > CAPACITY) {
                memory.remove(memory.keySet().iterator().next());
            }
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return Long.MIN_VALUE;
        }
    }

    private Path file(String key) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
            return directory.resolve(hash + ".json");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
