package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.Account;

import com.google.gson.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Official Minecraft skin faces, with a small per-account PNG cache and no third-party avatar
 * service.
 */
public final class SkinAvatars implements com.fuzzlauncher.application.AccountAvatars {
    private static final long MAX_PNG_BYTES = 4 * 1024 * 1024;
    private final Path directory;
    private final HttpTransport http;

    public SkinAvatars(Path data, HttpTransport http) {
        this.directory = data.resolve("skins");
        this.http = http;
    }

    public static String activeSkinUrl(JsonObject profile) {
        if (!profile.has("skins") || !profile.get("skins").isJsonArray()) {
            return "";
        }
        for (JsonElement entry : profile.getAsJsonArray("skins")) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject skin = entry.getAsJsonObject();
            JsonElement state = skin.get("state"), url = skin.get("url");
            if (state != null && state.isJsonPrimitive() && "ACTIVE".equals(state.getAsString()) && url != null && url.isJsonPrimitive()
                && url.getAsJsonPrimitive().isString()) {
                return url.getAsString();
            }
        }
        return "";
    }

    public byte[] load(Account account, TaskContext task) throws Exception {
        if (account.isOffline()) {
            return null;
        }
        task.checkCancelled();
        String uuid = account.uuid().replace("-", "").toLowerCase(Locale.ROOT);
        if (!uuid.matches("[0-9a-f]{32}")) {
            throw new IOException("皮肤账号 UUID 无效");
        }
        Path avatar = directory.resolve(uuid + ".png"), metadata = directory.resolve(uuid + ".json");
        String url = account.skinUrl();
        if (Files.isRegularFile(avatar) && Files.isRegularFile(metadata)) {
            try {
                String cached = JsonFiles.string(JsonFiles.read(metadata), "url", "");
                if ((url.isBlank() || textureUrl(url).equals(cached)) && !cached.isBlank()) {
                    textureUrl(cached);
                    readPng(avatar, true);
                    return Files.readAllBytes(avatar);
                }
            } catch (IOException | RuntimeException ex) {
                LauncherLog.LOGGER.warning("头像缓存无效，重新加载：" + ex.getMessage());
            }
        }
        if (url.isBlank()) {
            JsonObject profile = http.getJson("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid);
            task.checkCancelled();
            if (profile.has("properties")) {
                for (JsonElement element : profile.getAsJsonArray("properties")) {
                    JsonObject property = element.getAsJsonObject();
                    if (!"textures".equals(JsonFiles.string(property, "name", ""))) {
                        continue;
                    }
                    JsonObject textures = JsonParser
                        .parseString(new String(Base64.getDecoder().decode(property.get("value").getAsString()), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("textures");
                    if (textures != null && textures.has("SKIN")) {
                        url = JsonFiles.string(textures.getAsJsonObject("SKIN"), "url", "");
                    }
                }
            }
        }
        if (url.isBlank()) {
            return null;
        }
        url = textureUrl(url);
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "skin-", ".png");
        try {
            // HttpTransport uses a separate partial file and atomically publishes the completed
            // image.
            http.download(url, temporary, "", task);
            task.checkCancelled();
            BufferedImage face = face(readPng(temporary, false));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(face, "png", bytes);
            JsonFiles.atomicWrite(avatar, bytes.toByteArray());
            JsonFiles.write(metadata, Map.of("url", url));
            return bytes.toByteArray();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static String textureUrl(String input) throws IOException {
        try {
            URI uri = URI.create(input);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || !"textures.minecraft.net".equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)
                || !uri.getPath().matches("/texture/[0-9a-fA-F]{32,128}")) {
                throw new IOException("皮肤地址不是官方纹理地址");
            }
            return "https://textures.minecraft.net" + uri.getPath();
        } catch (IllegalArgumentException ex) {
            throw new IOException("皮肤地址无效", ex);
        }
    }

    private static BufferedImage readPng(Path file, boolean avatar) throws IOException {
        if (Files.size(file) > MAX_PNG_BYTES) {
            throw new IOException("皮肤图片过大");
        }
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) {
                throw new IOException("无法读取皮肤图片");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("皮肤不是有效图片");
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                boolean valid = avatar ? width >= 8 && width <= 128 && width % 8 == 0 && height == width
                    : width >= 64 && width <= 1024 && width % 64 == 0 && (height == width || height * 2 == width);
                if (!"png".equalsIgnoreCase(reader.getFormatName()) || !valid) {
                    throw new IOException("皮肤 PNG 尺寸无效");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    public static BufferedImage face(BufferedImage skin) throws IOException {
        int width = skin.getWidth(), height = skin.getHeight();
        if (width < 64 || width > 1024 || width % 64 != 0 || (height != width && height * 2 != width)) {
            throw new IOException("皮肤尺寸无效");
        }
        int scale = width / 64, size = 8 * scale;
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var graphics = result.createGraphics();
        try {
            graphics.drawImage(skin.getSubimage(8 * scale, 8 * scale, size, size), 0, 0, null);
            // Legacy opaque skins used an unused opaque outer layer instead of a transparent hat.
            boolean hat = height == width;
            if (!hat) {
                for (int y = 0; y < 32 * scale && !hat; y++) {
                    for (int x = 32 * scale; x < 64 * scale; x++) {
                        if ((skin.getRGB(x, y) >>> 24) < 255) {
                            hat = true;
                            break;
                        }
                    }
                }
            }
            if (hat) {
                graphics.drawImage(skin.getSubimage(40 * scale, 8 * scale, size, size), 0, 0, null);
            }
        } finally {
            graphics.dispose();
        }
        return result;
    }
}
