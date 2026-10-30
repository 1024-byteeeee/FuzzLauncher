package com.fuzzlauncher.infrastructure;

import java.io.IOException;
import java.nio.file.*;
import java.util.logging.*;

public final class LauncherLog {
    public static final Logger LOGGER = Logger.getLogger("com.fuzzlauncher.launcher");

    private LauncherLog() {
    }

    public static synchronized void configure(Path data) throws IOException {
        close();
        Path logs = Files.createDirectories(data.resolve("logs"));
        FileHandler handler = new FileHandler(logs.resolve("launcher.log").toString(), 1024 * 1024, 3, true);
        handler.setFormatter(new SimpleFormatter());
        LOGGER.addHandler(handler);
        LOGGER.info("启动器启动，数据目录：" + data);
    }

    public static synchronized void close() {
        for (Handler handler : LOGGER.getHandlers()) {
            handler.close();
            LOGGER.removeHandler(handler);
        }
    }
}
