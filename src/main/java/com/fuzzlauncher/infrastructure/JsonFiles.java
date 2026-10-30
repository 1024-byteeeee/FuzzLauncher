package com.fuzzlauncher.infrastructure;

import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class JsonFiles {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private JsonFiles() {
    }

    public static JsonObject read(Path path) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IOException("JSON 文件损坏: " + path, ex);
        }
    }

    public static void write(Path path, Object value) throws IOException {
        atomicWrite(path, GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    public static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".fuzz-", ".tmp");
        try {
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    public static Path safeResolve(Path root, String child) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        Path resolved = normalized.resolve(child).normalize();
        if (!resolved.startsWith(normalized) || resolved.equals(normalized)) {
            throw new IOException("文件路径超出目标目录: " + child);
        }
        return resolved;
    }
}
