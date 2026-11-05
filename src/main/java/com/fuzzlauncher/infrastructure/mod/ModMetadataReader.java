package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.content.ContentFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.jar.Manifest;
import java.util.zip.*;

/** Reads metadata only; mod code and embedded dependencies are never loaded. */
public final class ModMetadataReader {
    private static final int METADATA_LIMIT = 2 * 1024 * 1024;
    private static final int ICON_LIMIT = 1024 * 1024;

    private final List<ModMetadataParser> parsers;

    public ModMetadataReader() {
        this(List.of(new FabricMetadataParser(), new QuiltMetadataParser(), new ForgeMetadataParser("Forge", "META-INF/mods.toml"), new ForgeMetadataParser("NeoForge", "META-INF/neoforge.mods.toml"), new LegacyForgeMetadataParser()));
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

    public ContentInspection read(Path path, TaskContext task) throws IOException {
        task.checkCancelled();
        List<ContentFile.Metadata> metadata = new ArrayList<>();
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
                    String text = new String(ContentPreviewIO.bytes(archive, entry, METADATA_LIMIT, task), StandardCharsets.UTF_8);
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
                    icon = ContentPreviewIO.thumbnail(ContentPreviewIO.bytes(archive, name, ICON_LIMIT, task));
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
            return new ContentInspection(true, merge(metadata), String.join("\n", problems), icon);
        } catch (ZipException ex) {
            return new ContentInspection(false, List.of(), "压缩文件损坏：" + ex.getMessage(), null);
        }
    }

    private static List<ContentFile.Metadata> merge(List<ContentFile.Metadata> values) {
        Map<String, ContentFile.Metadata> result = new LinkedHashMap<>();
        for (var mod : values) {
            var old = result.get(mod.id());
            if (old == null) {
                result.put(mod.id(), mod);
            } else {
                Set<String> loaders = new HashSet<>(old.loaders());
                loaders.addAll(mod.loaders());
                result.put(mod.id(), new ContentFile.Metadata(old.id(), old.name(), old.version(), old.authors(), old.description(), loaders));
            }
        }
        return List.copyOf(result.values());
    }

    private static String manifestVersion(ZipFile archive, TaskContext task, List<String> problems) {
        if (archive.getEntry("META-INF/MANIFEST.MF") != null) {
            try {
                var manifest = new Manifest(new ByteArrayInputStream(ContentPreviewIO.bytes(archive, "META-INF/MANIFEST.MF", METADATA_LIMIT, task)));
                return Objects.requireNonNullElse(manifest.getMainAttributes().getValue("Implementation-Version"), "未知");
            } catch (IOException ex) {
                problems.add("MANIFEST.MF 无法读取");
            }
        }
        return "未知";
    }
}
