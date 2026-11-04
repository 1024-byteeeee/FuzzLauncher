package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.mod.ModFile;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.jar.Manifest;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Reads metadata only; mod code and embedded dependencies are never loaded. */
public final class ModMetadataReader {
    private static final int METADATA_LIMIT = 2 * 1024 * 1024;
    private static final int ICON_LIMIT = 1024 * 1024;

    public record Result(boolean readable, List<ModFile.Metadata> metadata, String problem, byte[] icon) {
    }

    private final List<ModMetadataParser> parsers;

    public ModMetadataReader() {
        this(List.of(new FabricMetadataParser(), new QuiltMetadataParser(), new ForgeMetadataParser("Forge", "META-INF/mods.toml"),
            new ForgeMetadataParser("NeoForge", "META-INF/neoforge.mods.toml"), new LegacyForgeMetadataParser()));
    }

    ModMetadataReader(List<ModMetadataParser> parsers) {
        this.parsers = List.copyOf(parsers);
        Set<String> entries = new HashSet<>();
        for (ModMetadataParser parser : this.parsers) {
            if (!entries.add(parser.entry())) {
                throw new IllegalArgumentException("Duplicate metadata parser: " + parser.entry());
            }
        }
    }

    public Result read(Path path, TaskContext task) throws IOException {
        task.checkCancelled();
        List<ModFile.Metadata> metadata = new ArrayList<>();
        List<String> problems = new ArrayList<>(), icons = new ArrayList<>();
        try (ZipFile archive = new ZipFile(path.toFile())) {
            String jarVersion = manifestVersion(archive, task, problems);
            for (ModMetadataParser parser : parsers) {
                String entry = parser.entry();
                task.checkCancelled();
                if (archive.getEntry(entry) == null) {
                    continue;
                }
                try {
                    String text = new String(bytes(archive, entry, METADATA_LIMIT, task), StandardCharsets.UTF_8);
                    parser.parse(text, jarVersion, metadata, icons);
                } catch (IOException | RuntimeException ex) {
                    if (ex instanceof CancellationException cancelled) {
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

}
