package com.fuzzlauncher.infrastructure.platform;

import com.fuzzlauncher.application.model.FileOperationResult;
import com.fuzzlauncher.application.task.TaskContext;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.*;
import java.util.regex.Pattern;

public final class LauncherLog {
    public static final Logger LOGGER = Logger.getLogger("com.fuzzlauncher.launcher");
    private static final Pattern LOG_FILE = Pattern.compile(".*\\.log(?:\\.[0-9]+)*", Pattern.CASE_INSENSITIVE);

    private LauncherLog() {
    }

    public static synchronized void configure(Path data) throws IOException {
        close();
        Path logs = Files.createDirectories(data.resolve("logs"));
        open(logs);
        LOGGER.info("启动器启动，数据目录：" + data);
    }

    private static void open(Path logs) throws IOException {
        FileHandler handler = new FileHandler(logs.resolve("launcher.log").toString(), 1024 * 1024, 3, true);
        handler.setFormatter(new SimpleFormatter());
        LOGGER.addHandler(handler);
    }

    public static synchronized FileOperationResult clear(Path data, TaskContext task) throws IOException {
        Path logs = data.resolve("logs");
        if (Files.notExists(logs, LinkOption.NOFOLLOW_LINKS)) {
            return new FileOperationResult(0, List.of());
        }
        if (!Files.isDirectory(logs, LinkOption.NOFOLLOW_LINKS) || !logs.toRealPath().equals(data.toRealPath().resolve("logs"))) {
            throw new IOException("日志目录不是普通目录：" + logs);
        }
        task.checkCancelled();
        List<String> failures = new ArrayList<>();
        int deleted = 0;
        close();
        try (var files = Files.newDirectoryStream(logs, file -> LOG_FILE.matcher(file.getFileName().toString()).matches())) {
            for (Path file : files) {
                task.checkCancelled();
                try {
                    if (Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile() && Files.deleteIfExists(file)) {
                        deleted++;
                    }
                } catch (NoSuchFileException ignored) {
                    // A file removed by another process needs no cleanup.
                } catch (IOException ex) {
                    failures.add(file.getFileName() + "：" + ex.getMessage());
                }
            }
        } catch (IOException | DirectoryIteratorException ex) {
            failures.add("无法读取日志目录：" + ex.getMessage());
        } finally {
            try {
                open(logs);
            } catch (IOException ex) {
                failures.add("无法恢复启动器日志写入：" + ex.getMessage());
                LOGGER.warning(failures.getLast());
            }
        }
        return new FileOperationResult(deleted, failures);
    }

    public static synchronized void close() {
        for (Handler handler : LOGGER.getHandlers()) {
            handler.close();
            LOGGER.removeHandler(handler);
        }
    }
}
