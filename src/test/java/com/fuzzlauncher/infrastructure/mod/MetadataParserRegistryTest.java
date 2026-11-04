package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.domain.mod.ModFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static com.fuzzlauncher.ModFixtures.TASK;
import static com.fuzzlauncher.ModFixtures.jar;
import static org.junit.jupiter.api.Assertions.*;

class MetadataParserRegistryTest {
    @TempDir
    Path temp;

    @Test
    void anAdditionalFormatCanBeReadWithoutChangingTheArchiveReader() throws Exception {
        ModMetadataParser parser = new ModMetadataParser() {
            public String entry() {
                return "custom.info";
            }

            public void parse(String text, String jarVersion, List<ModFile.Metadata> output, List<String> icons) {
                output.add(new ModFile.Metadata(text, text, jarVersion, "", "", Set.of("Custom")));
            }
        };
        Path archive = jar(temp.resolve("custom.jar"),
            Map.of("custom.info", "custom", "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 2.0\n\n"));
        var result = new ModMetadataReader(List.of(parser)).read(archive, TASK);
        assertTrue(result.readable());
        assertEquals("custom", result.metadata().getFirst().id());
        assertEquals("2.0", result.metadata().getFirst().version());
    }

    @Test
    void duplicateEntriesAreRejectedInsteadOfParsingTheSameMetadataTwice() {
        var parser = new FabricMetadataParser();
        assertThrows(IllegalArgumentException.class, () -> new ModMetadataReader(List.of(parser, parser)));
    }

    @Test
    void parserCancellationEscapesInsteadOfBecomingAMetadataWarning() throws Exception {
        ModMetadataParser parser = new ModMetadataParser() {
            public String entry() {
                return "custom.info";
            }

            public void parse(String text, String jarVersion, List<ModFile.Metadata> output, List<String> icons) {
                throw new CancellationException();
            }
        };
        Path archive = jar(temp.resolve("custom.jar"), Map.of("custom.info", "custom"));
        assertThrows(CancellationException.class, () -> new ModMetadataReader(List.of(parser)).read(archive, TASK));
    }
}
