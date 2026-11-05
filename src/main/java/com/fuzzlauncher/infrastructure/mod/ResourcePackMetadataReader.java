package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.content.ContentFile;
import com.fuzzlauncher.domain.mod.ModFileNames;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

final class ResourcePackMetadataReader {
    static final String METADATA = "pack.mcmeta";
    static final String DISABLED_METADATA = "pack.mcmeta.disabled";

    private ResourcePackMetadataReader() {
    }

    static ContentInspection read(Path path, String name, TaskContext task) throws IOException {
        boolean folder = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        try (ZipFile archive = folder ? null : new ZipFile(path.toFile())) {
            String metadata = folder && !Files.exists(path.resolve(METADATA), LinkOption.NOFOLLOW_LINKS)
                ? DISABLED_METADATA : METADATA;
            JsonElement document = JsonParser.parseString(new String(readEntry(path, archive, metadata, 2 * 1024 * 1024, task),
                StandardCharsets.UTF_8));
            JsonElement packSection = document.isJsonObject() ? document.getAsJsonObject().get("pack") : null;
            if (packSection == null || !packSection.isJsonObject()) {
                throw new IOException("pack.mcmeta 缺少 pack 信息");
            }
            var pack = packSection.getAsJsonObject();
            String title = name;
            if (!folder && ModFileNames.logicalName(name).endsWith(".zip")) {
                title = name.substring(0, ModFileNames.logicalName(name).length() - 4);
            }
            JsonElement format = pack.get("pack_format");
            String version = format != null && format.isJsonPrimitive() ? "格式 " + format.getAsString() : "";
            if (version.isEmpty() && pack.has("min_format") && pack.has("max_format")) {
                version = "格式 " + pack.get("min_format") + "–" + pack.get("max_format");
            }
            byte[] icon = null;
            String problem = "";
            if (archive == null ? Files.exists(path.resolve("pack.png"), LinkOption.NOFOLLOW_LINKS) : archive.getEntry("pack.png") != null) {
                try {
                    icon = ContentPreviewIO.thumbnail(readEntry(path, archive, "pack.png", 1024 * 1024, task));
                } catch (IOException ex) {
                    problem = "资源包图标无法读取";
                }
            }
            var entry = new ContentFile.Metadata("", title, version, "", description(pack.get("description"), 0), Set.of());
            return new ContentInspection(true, List.of(entry), problem, icon);
        } catch (IOException | IllegalStateException | com.google.gson.JsonParseException ex) {
            return new ContentInspection(false, List.of(), "无法读取资源包：" + ex.getMessage(), null);
        }
    }

    private static byte[] readEntry(Path path, ZipFile archive, String name, int limit, TaskContext task) throws IOException {
        if (archive != null) {
            return ContentPreviewIO.bytes(archive, name, limit, task);
        }
        Path file = path.resolve(name);
        ContentFiles.ordinary(file);
        try (var input = Files.newInputStream(file)) {
            return ContentPreviewIO.bytes(input, name, limit, task);
        }
    }

    private static String description(JsonElement value, int depth) {
        if (value == null || value.isJsonNull() || depth >= 16) {
            return "";
        }
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        var text = new StringBuilder();
        if (value.isJsonArray()) {
            for (var part : value.getAsJsonArray()) {
                text.append(description(part, depth + 1));
            }
        } else {
            var component = value.getAsJsonObject();
            text.append(description(component.has("text") ? component.get("text") : component.get("translate"), depth + 1));
            text.append(description(component.get("extra"), depth + 1));
        }
        return text.toString();
    }
}
