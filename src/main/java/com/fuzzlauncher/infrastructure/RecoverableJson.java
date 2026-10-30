package com.fuzzlauncher.infrastructure;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.*;
import java.util.function.Function;

/** Keeps a validated previous generation; damaged input is never silently replaced. */
public final class RecoverableJson {
    private RecoverableJson() {
    }

    public static Path backup(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    public static void integer(com.google.gson.JsonObject object, String field, String path) {
        if (!object.has(field)) {
            return;
        }
        var value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + field + " 必须为整数");
        }
        try {
            value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(path + field + " 超出整数范围或含有小数", ex);
        }
    }

    public static <E extends Enum<E>> void enumField(com.google.gson.JsonObject object, String field, Class<E> type, String path) {
        if (!object.has(field)) {
            return;
        }
        var value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || JsonFiles.GSON.fromJson(value, type) == null) {
            throw new IllegalArgumentException(path + field + " 不是有效的选项");
        }
    }

    public static <T> T read(Path file, Function<JsonObject, T> decode) throws IOException {
        try {
            return decode.apply(JsonFiles.read(file));
        } catch (IOException | RuntimeException ex) {
            throw new Damaged(file, decode, ex);
        }
    }

    public static <T> void write(Path file, Object value, Function<JsonObject, T> decode) throws IOException {
        JsonObject json = JsonFiles.GSON.toJsonTree(value).getAsJsonObject();
        decode.apply(json);
        if (Files.exists(file)) {
            read(file, decode);
            JsonFiles.atomicWrite(backup(file), Files.readAllBytes(file));
        }
        JsonFiles.write(file, value);
        if (!Files.exists(backup(file))) {
            JsonFiles.atomicWrite(backup(file), Files.readAllBytes(file));
        }
    }

    public static final class Damaged extends IOException {
        private final Path file;
        private final Function<JsonObject, ?> decode;

        private Damaged(Path file, Function<JsonObject, ?> decode, Exception cause) {
            super("配置文件无法读取：" + file + "\n" + explanation(cause), cause);
            this.file = file;
            this.decode = decode;
        }

        public Path file() {
            return file;
        }

        public boolean hasValidBackup() {
            try {
                decode.apply(JsonFiles.read(backup(file)));
                return true;
            } catch (IOException | RuntimeException ex) {
                return false;
            }
        }

        public void restore() throws IOException {
            Path backup = backup(file);
            read(backup, decode);
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".corrupt-" + java.util.UUID.randomUUID()));
            }
            JsonFiles.atomicWrite(file, Files.readAllBytes(backup));
        }

        private static String explanation(Throwable error) {
            java.util.Set<String> messages = new java.util.LinkedHashSet<>();
            java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            while (error != null && seen.add(error)) {
                if (error.getMessage() != null) {
                    messages.add(error.getMessage());
                }
                error = error.getCause();
            }
            return String.join("\n", messages);
        }
    }
}
