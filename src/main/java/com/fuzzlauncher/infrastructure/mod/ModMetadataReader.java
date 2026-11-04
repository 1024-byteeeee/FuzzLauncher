package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.ModFile;

import com.google.gson.*;
import org.tomlj.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Manifest;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Reads metadata only; mod code and embedded dependencies are never loaded. */
public final class ModMetadataReader {
    private static final int METADATA_LIMIT = 2 * 1024 * 1024;
    private static final int ICON_LIMIT = 1024 * 1024;

    public record Result(boolean readable, List<ModFile.Metadata> metadata, String problem, byte[] icon) {
    }

    public Result read(Path path, TaskContext task) throws IOException {
        task.checkCancelled();
        List<ModFile.Metadata> metadata = new ArrayList<>();
        List<String> problems = new ArrayList<>(), icons = new ArrayList<>();
        try (ZipFile archive = new ZipFile(path.toFile())) {
            String jarVersion = manifestVersion(archive, task, problems);
            for (String entry : List.of("fabric.mod.json", "quilt.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml",
                "mcmod.info")) {
                task.checkCancelled();
                if (archive.getEntry(entry) == null) {
                    continue;
                }
                try {
                    String text = new String(bytes(archive, entry, METADATA_LIMIT, task), StandardCharsets.UTF_8);
                    switch (entry) {
                        case "fabric.mod.json" -> fabric(JsonParser.parseString(text).getAsJsonObject(), metadata, icons);
                        case "quilt.mod.json" -> quilt(JsonParser.parseString(text).getAsJsonObject(), metadata, icons);
                        case "mcmod.info" -> legacy(JsonParser.parseString(text), metadata, icons);
                        default -> forge(text, entry.contains("neoforge") ? "NeoForge" : "Forge", jarVersion, metadata, icons);
                    }
                } catch (IOException | RuntimeException ex) {
                    if (ex instanceof java.util.concurrent.CancellationException cancelled) {
                        throw cancelled;
                    }
                    problems.add(entry + "：" + Objects.toString(ex.getMessage(), "元数据无效"));
                }
            }
            byte[] icon = null;
            Set<String> candidates = new LinkedHashSet<>();
            for (String name : icons) {
                String normalized = name.replace('\\', '/');
                while (normalized.startsWith("/") || normalized.startsWith("./")) {
                    normalized = normalized.substring(normalized.startsWith("./") ? 2 : 1);
                }
                candidates.add(normalized);
            }
            for (var mod : metadata) {
                for (String name : List.of("assets/" + mod.id() + "/icon.png", "assets/" + mod.id() + "/logo.png")) {
                    if (archive.getEntry(name) != null) {
                        candidates.add(name);
                    }
                }
            }
            for (String name : List.of("icon.png", "logo.png")) {
                if (archive.getEntry(name) != null) {
                    candidates.add(name);
                }
            }
            for (String name : candidates) {
                task.checkCancelled();
                try {
                    icon = thumbnail(bytes(archive, name, ICON_LIMIT, task));
                    if (icon != null) {
                        break;
                    }
                } catch (IOException | IllegalArgumentException ex) {
                    problems.add("图标无法读取：" + name);
                }
            }
            if (metadata.isEmpty() && problems.isEmpty()) {
                problems.add("未识别到受支持的模组元数据");
            }
            return new Result(true, merge(metadata), String.join("\n", problems), icon);
        } catch (ZipException ex) {
            return new Result(false, List.of(), "压缩文件损坏：" + ex.getMessage(), null);
        }
    }

    private static void fabric(JsonObject json, List<ModFile.Metadata> output, List<String> icons) {
        String id = required(json, "id");
        output.add(new ModFile.Metadata(id, string(json, "name", id), string(json, "version", "未知"), people(json.get("authors")),
            string(json, "description", ""), Set.of("Fabric")));
        icon(json.get("icon"), icons);
    }

    private static void quilt(JsonObject json, List<ModFile.Metadata> output, List<String> icons) {
        JsonObject loader = json.getAsJsonObject("quilt_loader");
        String id = required(loader, "id");
        JsonObject metadata = loader.has("metadata") ? loader.getAsJsonObject("metadata") : new JsonObject();
        String authors = metadata.has("contributors") ? String.join(", ", metadata.getAsJsonObject("contributors").keySet()) : "";
        output.add(new ModFile.Metadata(id, string(metadata, "name", id), string(loader, "version", "未知"), authors,
            string(metadata, "description", ""), Set.of("Quilt")));
        icon(metadata.get("icon"), icons);
    }

    private static void legacy(JsonElement json, List<ModFile.Metadata> output, List<String> icons) {
        JsonArray mods = json.isJsonArray() ? json.getAsJsonArray() : json.getAsJsonObject().getAsJsonArray("modList");
        if (mods == null || mods.isEmpty()) {
            throw new IllegalArgumentException("缺少 modList");
        }
        for (JsonElement value : mods) {
            JsonObject mod = value.getAsJsonObject();
            String id = required(mod, "modid");
            output.add(new ModFile.Metadata(id, string(mod, "name", id), string(mod, "version", "未知"), people(mod.get("authorList")),
                string(mod, "description", ""), Set.of("Forge")));
            icon(mod.get("logoFile"), icons);
        }
    }

    private static void forge(String text, String loader, String jarVersion, List<ModFile.Metadata> output, List<String> icons)
        throws IOException {
        TomlParseResult result = Toml.parse(text);
        if (result.hasErrors()) {
            throw new IOException(result.errors().getFirst().toString());
        }
        TomlArray mods = result.getArray("mods");
        if (mods == null || mods.isEmpty()) {
            throw new IOException("缺少 [[mods]]");
        }
        for (int i = 0; i < mods.size(); i++) {
            TomlTable mod = mods.getTable(i);
            String id = mod.getString("modId");
            if (id == null || id.isBlank()) {
                throw new IOException("缺少 modId");
            }
            String version = mod.getString("version", () -> "未知").replace("${file.jarVersion}", jarVersion);
            output.add(new ModFile.Metadata(id, mod.getString("displayName", () -> id), version, mod.getString("authors", () -> ""),
                mod.getString("description", () -> ""), Set.of(loader)));
            String logo = mod.getString("logoFile", () -> result.getString("logoFile", () -> ""));
            if (!logo.isBlank()) {
                icons.add(logo);
            }
        }
    }

    private static List<ModFile.Metadata> merge(List<ModFile.Metadata> values) {
        Map<String, ModFile.Metadata> result = new LinkedHashMap<>();
        for (var mod : values) {
            var old = result.get(mod.id());
            if (old == null) {
                result.put(mod.id(), mod);
            } else {
                Set<String> loaders = new HashSet<>(old.loaders());
                loaders.addAll(mod.loaders());
                result.put(mod.id(), new ModFile.Metadata(old.id(), old.name(), old.version(), old.authors(), old.description(), loaders));
            }
        }
        return List.copyOf(result.values());
    }

    private static String manifestVersion(ZipFile archive, TaskContext task, List<String> problems) {
        if (archive.getEntry("META-INF/MANIFEST.MF") != null) {
            try {
                var manifest = new Manifest(new ByteArrayInputStream(bytes(archive, "META-INF/MANIFEST.MF", METADATA_LIMIT, task)));
                return Objects.requireNonNullElse(manifest.getMainAttributes().getValue("Implementation-Version"), "未知");
            } catch (IOException ex) {
                problems.add("MANIFEST.MF 无法读取");
            }
        }
        return "未知";
    }

    private static byte[] bytes(ZipFile archive, String name, int limit, TaskContext task) throws IOException {
        ZipEntry entry = archive.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("条目不存在：" + name);
        }
        if (entry.getSize() > limit) {
            throw new IOException("条目过大：" + name);
        }
        try (InputStream input = archive.getInputStream(entry); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                task.checkCancelled();
                if (output.size() > limit - count) {
                    throw new IOException("条目过大：" + name);
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static byte[] thumbnail(byte[] bytes) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("不支持的图片格式");
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 4_000_000) {
                    throw new IOException("图片尺寸过大");
                }
                var parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(Math.max(1, width / 128), Math.max(1, height / 128), 0, 0);
                BufferedImage image = reader.read(0, parameters);
                double scale = Math.min(1, 64.0 / Math.max(image.getWidth(), image.getHeight()));
                var small = new BufferedImage(Math.max(1, (int) (image.getWidth() * scale)), Math.max(1, (int) (image.getHeight() * scale)),
                    BufferedImage.TYPE_INT_ARGB);
                var graphics = small.createGraphics();
                try {
                    graphics.drawImage(image, 0, 0, small.getWidth(), small.getHeight(), null);
                } finally {
                    graphics.dispose();
                }
                var output = new ByteArrayOutputStream();
                ImageIO.write(small, "png", output);
                return output.toByteArray();
            } finally {
                reader.dispose();
            }
        }
    }

    private static String required(JsonObject json, String key) {
        String value = string(json, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少 " + key);
        }
        return value;
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static String people(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (!value.isJsonArray()) {
            return value.isJsonObject() ? string(value.getAsJsonObject(), "name", "") : value.getAsString();
        }
        List<String> names = new ArrayList<>();
        for (JsonElement person : value.getAsJsonArray()) {
            if (person.isJsonObject()) {
                names.add(string(person.getAsJsonObject(), "name", ""));
            } else if (person.isJsonPrimitive()) {
                names.add(person.getAsString());
            }
        }
        return String.join(", ", names);
    }

    private static void icon(JsonElement value, List<String> output) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonObject()) {
            value.getAsJsonObject().entrySet().stream().sorted(Comparator.comparingInt(entry -> iconSizeOrder(entry.getKey())))
                .map(Map.Entry::getValue).filter(JsonElement::isJsonPrimitive).forEach(item -> output.add(item.getAsString()));
        } else if (value.isJsonPrimitive() && !value.getAsString().isBlank()) {
            output.add(value.getAsString());
        }
    }

    private static int iconSizeOrder(String size) {
        try {
            int pixels = Integer.parseInt(size);
            if (pixels <= 0) {
                return Integer.MAX_VALUE;
            }
            return pixels >= 64 ? pixels - 64 : Integer.MAX_VALUE - pixels;
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }
}
