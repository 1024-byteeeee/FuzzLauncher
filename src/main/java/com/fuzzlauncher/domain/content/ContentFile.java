package com.fuzzlauncher.domain.content;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** One managed file or directory, optionally with metadata from its content format. */
public record ContentFile(Path path, boolean enabled, boolean readable, long size, long modified, String identity, List<Metadata> metadata,
    String problem, byte[] icon, boolean directory) {
    public record Metadata(String id, String name, String version, String authors, String description, Set<String> loaders) {
        public Metadata {
            loaders = Set.copyOf(loaders);
        }
    }

    public ContentFile {
        metadata = List.copyOf(metadata);
        icon = icon == null ? null : icon.clone();
    }

    @Override
    public byte[] icon() {
        return icon == null ? null : icon.clone();
    }

    public String name() {
        return metadata.isEmpty() ? path.getFileName().toString() : metadata.getFirst().name() + (metadata.size() > 1 ? " (+" + (metadata.size() - 1) + ")" : "");
    }

    public String version() {
        return metadata.stream().map(Metadata::version).distinct().collect(Collectors.joining(", "));
    }

    public String loaders() {
        return metadata.stream().flatMap(mod -> mod.loaders().stream()).distinct().sorted().collect(Collectors.joining(" / "));
    }
}
