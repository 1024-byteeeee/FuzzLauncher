package com.fuzzlauncher;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.infrastructure.mod.ModMetadataReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.*;
import javax.imageio.ImageIO;

import static com.fuzzlauncher.ModFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ModMetadataReaderTest {
    @TempDir
    Path temp;
    private final ModMetadataReader reader = new ModMetadataReader();

    @Test
    void fabricAndQuiltExposeLocalMetadataWithoutExecutingModCode() throws Exception {
        Path fabric = jar(temp.resolve("fabric.jar"), Map.of("fabric.mod.json", """
            {"id":"example","name":"测试模组","version":"1.2","authors":[{"name":"Author"},"Another"],"description":"说明"}
            """));
        var result = reader.read(fabric, TASK);
        assertTrue(result.readable());
        assertEquals("", result.problem());
        assertEquals("测试模组", result.metadata().getFirst().name());
        assertEquals("Author, Another", result.metadata().getFirst().authors());
        Path quilt = jar(temp.resolve("quilt.jar"), Map.of("quilt.mod.json",
            """
                {"quilt_loader":{"id":"quilt_example","version":"2.0","metadata":{"name":"Quilt example","contributors":{"Author":"Developer"}}}}
                """));
        var metadata = reader.read(quilt, TASK).metadata().getFirst();
        assertEquals("quilt_example", metadata.id());
        assertEquals("Author", metadata.authors());
        assertEquals(Set.of("Quilt"), metadata.loaders());
    }

    @Test
    void tomlHandlesMultilineTextMultipleModsAndManifestVersion() throws Exception {
        String metadata = """
            modLoader="javafml"
            loaderVersion="[1,)"
            license="MIT"
            [[mods]]
            modId="first"
            version="${file.jarVersion}"
            displayName="First"
            authors="Author"
            description='''
            First line
            Second line
            '''
            [[mods]]
            modId="second"
            version="2.0"
            """;
        Path file = jar(temp.resolve("multi.jar"), Map.of("META-INF/mods.toml", metadata, "META-INF/neoforge.mods.toml", metadata,
            "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 5.0\n\n"));
        var result = reader.read(file, TASK);
        assertEquals("", result.problem());
        assertEquals(2, result.metadata().size());
        assertEquals("5.0", result.metadata().getFirst().version());
        assertTrue(result.metadata().getFirst().description().contains("Second line"));
        assertEquals(Set.of("Forge", "NeoForge"), result.metadata().getFirst().loaders());
    }

    @Test
    void oldForgeBothMetadataShapesAreSupported() throws Exception {
        String array = "[{\"modid\":\"old\",\"name\":\"Old Forge\",\"version\":\"1.0\",\"authorList\":[\"Author\"]}]";
        for (String json : List.of(array, "{\"modList\":" + array + "}")) {
            Path file = jar(temp.resolve("old.jar"), Map.of("mcmod.info", json));
            var result = reader.read(file, TASK);
            assertEquals("old", result.metadata().getFirst().id());
            assertEquals("Author", result.metadata().getFirst().authors());
        }
    }

    @Test
    void corruptUnknownAndPartiallyMalformedArchivesRemainDistinguishable() throws Exception {
        Path broken = Files.writeString(temp.resolve("broken.jar"), "not a zip");
        assertFalse(reader.read(broken, TASK).readable());
        Path unknown = jar(temp.resolve("unknown.jar"), Map.of("readme.txt", "fixture"));
        assertTrue(reader.read(unknown, TASK).readable());
        assertTrue(reader.read(unknown, TASK).metadata().isEmpty());
        Path partial = jar(temp.resolve("partial.jar"), Map.of("fabric.mod.json", "{\"id\":\"valid\"}", "META-INF/mods.toml", "[broken"));
        var result = reader.read(partial, TASK);
        assertEquals("valid", result.metadata().getFirst().id());
        assertTrue(result.problem().contains("mods.toml"));
    }

    @Test
    void oversizedMetadataIsRejectedAndCancellationIsPropagated() throws Exception {
        Path file = jar(temp.resolve("large.jar"), Map.of("fabric.mod.json", " ".repeat(2 * 1024 * 1024 + 1)));
        assertTrue(reader.read(file, TASK).problem().contains("过大"));
        assertThrows(CancellationException.class, () -> reader.read(file, new TaskContext() {
            public void report(String message, double progress) {
            }

            public boolean cancelled() {
                return true;
            }
        }));
    }

    @Test
    void loaderMetadataPathsAndConventionalLogosReadTheArchivesOwnImage() throws Exception {
        Map<String, String> formats = Map.of("fabric.mod.json", "{\"id\":\"example\",\"icon\":\"/assets/example/logo.png\"}",
            "quilt.mod.json",
            "{\"quilt_loader\":{\"id\":\"example\",\"version\":\"1\",\"metadata\":{\"icon\":{\"64\":\"assets/example/logo.png\"}}}}",
            "META-INF/mods.toml", "[[mods]]\nmodId='example'\nlogoFile='assets\\example\\logo.png'\n", "META-INF/neoforge.mods.toml",
            "[[mods]]\nmodId='example'\nlogoFile='./assets/example/logo.png'\n", "mcmod.info",
            "[{\"modid\":\"example\",\"logoFile\":\"assets/example/logo.png\"}]");
        for (var format : formats.entrySet()) {
            Path file = logoArchive(format.getKey(), format.getValue(), Map.of("assets/example/logo.png", 0xff00aa55));
            var result = reader.read(file, TASK);
            assertNotNull(result.icon(), format.getKey() + ": " + result.problem());
            assertEquals(0xff00aa55, ImageIO.read(new ByteArrayInputStream(result.icon())).getRGB(0, 0));
        }
        Path undeclared = logoArchive("fabric.mod.json", "{\"id\":\"example\"}", Map.of("assets/example/logo.png", 0xffaa5500));
        assertEquals(0xffaa5500, ImageIO.read(new ByteArrayInputStream(reader.read(undeclared, TASK).icon())).getRGB(0, 0));
    }

    @Test
    void sizeMapsPreferTheSixtyFourPixelLogoInsteadOfLexicalKeyOrder() throws Exception {
        Path file = logoArchive("fabric.mod.json",
            "{\"id\":\"example\",\"icon\":{\"16\":\"small.png\",\"64\":\"medium.png\",\"128\":\"large.png\"}}",
            Map.of("small.png", 0xffff0000, "medium.png", 0xff00ff00, "large.png", 0xff0000ff));
        assertEquals(0xff00ff00, ImageIO.read(new ByteArrayInputStream(reader.read(file, TASK).icon())).getRGB(0, 0));
    }

    private Path logoArchive(String entry, String metadata, Map<String, Integer> images) throws Exception {
        Path path = temp.resolve("logo-" + UUID.randomUUID() + ".jar");
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry(entry));
            output.write(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            for (var image : images.entrySet()) {
                output.putNextEntry(new ZipEntry(image.getKey()));
                var pixels = new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < pixels.getHeight(); y++) {
                    for (int x = 0; x < pixels.getWidth(); x++) {
                        pixels.setRGB(x, y, image.getValue());
                    }
                }
                ImageIO.write(pixels, "png", output);
                output.closeEntry();
            }
        }
        return path;
    }

    @Test
    void iconsAreBoundedThumbnailsAndBadIconDoesNotHideTheMod() throws Exception {
        Path file = iconJar("icon.jar", 256, 128);
        var result = reader.read(file, TASK);
        var thumbnail = ImageIO.read(new ByteArrayInputStream(result.icon()));
        assertTrue(thumbnail.getWidth() <= 64);
        assertTrue(thumbnail.getHeight() <= 64);
        var oversized = reader.read(iconJar("huge-icon.jar", 2048, 2048), TASK);
        assertNull(oversized.icon());
        assertEquals("icon", oversized.metadata().getFirst().id());
        assertTrue(oversized.problem().contains("图标"));
    }

    private Path iconJar(String name, int width, int height) throws Exception {
        Path file = temp.resolve(name);
        try (var output = new ZipOutputStream(Files.newOutputStream(file))) {
            output.putNextEntry(new ZipEntry("fabric.mod.json"));
            output.write("{\"id\":\"icon\",\"icon\":\"icon.png\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("icon.png"));
            ImageIO.write(new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", output);
            output.closeEntry();
        }
        return file;
    }
}
