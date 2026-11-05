package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.application.task.TaskContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Bounded reads and thumbnails for content previews. */
final class ContentPreviewIO {
    private ContentPreviewIO() {
    }

    static byte[] bytes(ZipFile archive, String name, int limit, TaskContext task) throws IOException {
        ZipEntry entry = archive.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("条目不存在：" + name);
        }
        if (entry.getSize() > limit) {
            throw new IOException("条目过大：" + name);
        }
        try (InputStream input = archive.getInputStream(entry)) {
            return bytes(input, name, limit, task);
        }
    }

    static byte[] bytes(InputStream input, String name, int limit, TaskContext task) throws IOException {
        try (var output = new ByteArrayOutputStream()) {
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

    static byte[] thumbnail(byte[] bytes) throws IOException {
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
