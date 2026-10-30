package com.fuzzlauncher.infrastructure;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Local file operations used by the path picker, independent of JavaFX. Never overwrites data. */
public final class BrowserFiles {
    private BrowserFiles() {
    }

    public static Path createFolder(Path parent, String name) throws IOException {
        return Files.createDirectory(child(parent, name));
    }

    public static Path rename(Path source, String name) throws IOException {
        source = editable(source);
        Path target = child(source.getParent(), name);
        if (target.equals(source)) {
            return source;
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSameFile(source, target)) {
            throw new FileAlreadyExistsException("名称已存在：" + name);
        }
        return Files.move(source, target);
    }

    public static Path paste(Path source, Path destination, boolean move, boolean renameCollision) throws IOException {
        source = editable(source);
        destination = destination.toRealPath();
        if (!Files.isDirectory(destination)) {
            throw new IOException("目标不是文件夹");
        }
        Path realSource = source.toRealPath();
        if (Files.isDirectory(source) && destination.startsWith(realSource)) {
            throw new IOException("不能将文件夹粘贴到自身或其子文件夹中");
        }
        Path target = destination.resolve(source.getFileName());
        if (move && Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(source, target)) {
            return source;
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!renameCollision) {
                throw new FileAlreadyExistsException("目标已有同名项目：" + source.getFileName());
            }
            target = copyName(destination, source);
        }
        if (move && Files.getFileStore(source).equals(Files.getFileStore(destination))) {
            return Files.move(source, target);
        }

        // Publish only a completed copy; a failed copy never replaces an existing target.
        Path workspace = Files.createTempDirectory(destination, ".fuzz-file-");
        IOException failure = null;
        try {
            Path staged = workspace.resolve("content");
            copyTree(source, staged);
            Files.move(staged, target);
            if (move) {
                try {
                    deleteTree(source, false);
                } catch (IOException ex) {
                    throw new IOException("文件已复制到 " + target + "，但无法完整移除源项目：" + source, ex);
                }
            }
            return target;
        } catch (IOException ex) {
            failure = ex;
            throw ex;
        } finally {
            try {
                deleteTree(workspace, true);
            } catch (IOException ex) {
                if (failure != null) {
                    failure.addSuppressed(ex);
                } else {
                    throw ex;
                }
            }
        }
    }

    public static void recycle(Path path) throws IOException {
        path = editable(path);
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            throw new IOException("当前环境不支持回收站，未删除文件");
        }
        if (!Desktop.getDesktop().moveToTrash(path.toFile())) {
            throw new IOException("无法移入回收站：" + path);
        }
    }

    public static Path child(Path parent, String name) throws IOException {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.endsWith(".") || name.endsWith(" ")
            || name.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*") || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
            throw new IOException("名称无效，请使用合法的 Windows 文件名");
        }
        Path directory = parent.toRealPath();
        if (!Files.isDirectory(directory)) {
            throw new IOException("目标不是文件夹");
        }
        Path target = directory.resolve(name).normalize();
        if (!directory.equals(target.getParent())) {
            throw new IOException("名称必须位于当前文件夹内");
        }
        return target;
    }

    private static Path editable(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        if (path.getParent() == null) {
            throw new IOException("不能操作磁盘根目录");
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(path.toString());
        }
        return path;
    }

    private static Path copyName(Path parent, Path source) throws IOException {
        String name = source.getFileName().toString(), stem = name, extension = "";
        int dot = name.lastIndexOf('.');
        if (!Files.isDirectory(source) && dot > 0) {
            stem = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int index = 1; index < Integer.MAX_VALUE; index++) {
            Path candidate = child(parent, stem + " - 副本" + (index == 1 ? "" : " (" + index + ")") + extension);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        throw new IOException("无法生成可用名称");
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Path realSource = source.toRealPath();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                int flags = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(directory.toString());
                if (flags == com.sun.jna.platform.win32.WinBase.INVALID_FILE_ATTRIBUTES) {
                    throw new IOException("无法读取目录属性：" + directory);
                }
                if ((flags & com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                    throw new IOException("暂不复制目录链接：" + directory);
                }
                if (!directory.toRealPath().startsWith(realSource)) {
                    throw new IOException("文件夹包含指向外部的目录链接");
                }
                Files.createDirectory(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path source, boolean workspace) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path realSource = source.toRealPath();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.toRealPath().startsWith(realSource)) {
                    throw new IOException("拒绝遍历外部目录链接");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                // COPY_ATTRIBUTES may have copied the DOS read-only flag into our private
                // workspace.
                if (workspace && !Files.isSymbolicLink(file)) {
                    var dos = Files.getFileAttributeView(file, java.nio.file.attribute.DosFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                    if (dos != null && dos.readAttributes().isReadOnly()) {
                        dos.setReadOnly(false);
                    }
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
