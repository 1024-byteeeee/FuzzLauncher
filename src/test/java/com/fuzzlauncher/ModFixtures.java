package com.fuzzlauncher;

import com.fuzzlauncher.application.task.TaskContext;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.zip.*;

public final class ModFixtures {
    public static final TaskContext TASK = new TaskContext() {
        public void report(String message, double progress) {
        }

        public boolean cancelled() {
            return false;
        }
    };

    private ModFixtures() {
    }

    public static Path jar(Path path, Map<String, String> entries) throws IOException {
        Files.createDirectories(path.getParent());
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path;
    }

    public static Path fabric(Path path, String id) throws IOException {
        return jar(path, Map.of("fabric.mod.json", "{\"id\":\"" + id + "\",\"name\":\"" + id
            + "\",\"version\":\"1.0\",\"description\":\"Local fixture\",\"authors\":[\"Fixture\"]}"));
    }

    public static Path fabricWithLogo(Path path, String id, int color) throws IOException {
        Files.createDirectories(path.getParent());
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("fabric.mod.json"));
            output.write(("{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"version\":\"1.0\",\"icon\":\"/assets/" + id + "/logo.png\"}")
                .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("assets/" + id + "/logo.png"));
            var image = new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, color);
                }
            }
            javax.imageio.ImageIO.write(image, "png", output);
            output.closeEntry();
        }
        return path;
    }
}
