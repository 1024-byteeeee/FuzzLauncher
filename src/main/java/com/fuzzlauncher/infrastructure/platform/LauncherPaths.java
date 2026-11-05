package com.fuzzlauncher.infrastructure.platform;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.Objects;

/** Resolves the executable location independently of shortcuts' working directories. */
public final class LauncherPaths {
    private LauncherPaths() {
    }

    public static Path bootstrapDirectory() {
        String override = System.getProperty("fuzz.dataDir");
        return (override == null ? Path.of(Objects.toString(System.getenv("LOCALAPPDATA"), System.getProperty("user.home")), "FuzzLauncher")
            : Path.of(override)).toAbsolutePath().normalize();
    }

    public static Path gameStorageDirectory() throws IOException {
        Path launcherDirectory = directory();
        return allUsersInstallation(launcherDirectory) ? bootstrapDirectory() : launcherDirectory;
    }

    public static boolean allUsersInstallation(Path launcherDirectory) {
        return Files.isRegularFile(launcherDirectory.resolve("all-users.install"));
    }

    public static Path directory() throws IOException {
        String packaged = System.getProperty("jpackage.app-path");
        if (packaged != null && !packaged.isBlank()) {
            return Path.of(packaged).toAbsolutePath().normalize().getParent();
        }
        try {
            Path source = Path.of(LauncherPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(source)) {
                return source.toAbsolutePath().normalize().getParent();
            }
            // Gradle/IDE execution has class directories rather than a launcher executable.
            return Path.of("").toAbsolutePath().normalize();
        } catch (URISyntaxException ex) {
            throw new IOException("无法定位启动器所在目录", ex);
        }
    }
}
