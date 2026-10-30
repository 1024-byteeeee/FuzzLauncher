package com.fuzzlauncher.infrastructure;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/**
 * Small stable bootstrap points to the selected data folder; game files stay at their configured
 * path.
 */
public final class DataLocations {
    private static final String LOCATION = "data-location.json";
    private static final List<String> STATE = List.of("settings.json", "settings.json.bak", "instances.json", "instances.json.bak",
        "account.json", "credentials.dpapi");

    private DataLocations() {
    }

    public static Path bootstrapDirectory() {
        String override = System.getProperty("fuzz.dataDir");
        return (override == null ? Path.of(Objects.toString(System.getenv("LOCALAPPDATA"), System.getProperty("user.home")), "FuzzLauncher")
            : Path.of(override)).toAbsolutePath().normalize();
    }

    public static Path resolve() throws IOException {
        Path bootstrap = bootstrapDirectory();
        if (System.getProperty("fuzz.dataDir") != null) {
            return resolve(bootstrap);
        }
        return resolve(bootstrap, LauncherPaths.directory());
    }

    /** Defaults to local data, while preserving explicit selections and existing legacy settings. */
    public static Path resolve(Path bootstrap, Path launcherDirectory) throws IOException {
        if (Files.exists(bootstrap.resolve(LOCATION))) {
            return resolve(bootstrap);
        }
        Path local = launcherDirectory.toAbsolutePath().normalize().resolve("data");
        if (Files.exists(local.resolve("settings.json")) || Files.exists(local.resolve("settings.json.bak"))) {
            return local;
        }
        if (Files.exists(bootstrap.resolve("settings.json")) || Files.exists(bootstrap.resolve("settings.json.bak"))) {
            return bootstrap.toAbsolutePath().normalize();
        }
        return local;
    }

    public static void configure(Path bootstrap, Path current, Path target) throws IOException {
        current = current.toAbsolutePath().normalize();
        target = target.toAbsolutePath().normalize();
        if (target.equals(current)) {
            Path pointer = bootstrap.resolve(LOCATION);
            if (Files.exists(pointer)) {
                JsonFiles.write(pointer, Map.of("directory", current.toString()));
            }
            return;
        }
        if (target.getParent() == null || target.startsWith(current.resolve("logs")) || target.startsWith(current.resolve("skins"))
            || target.startsWith(current.resolve("runtimes"))) {
            throw new IOException("请选择独立的启动器数据目录");
        }
        if (Files.exists(target)) {
            if (!Files.isDirectory(target) || Files.isSymbolicLink(target)) {
                throw new IOException("数据路径不是普通目录");
            }
            try (var children = Files.list(target)) {
                if (children.findAny().isPresent()) {
                    throw new IOException("目标数据目录必须为空，避免覆盖已有文件");
                }
            }
        }
        JsonObject location = new JsonObject();
        location.addProperty("directory", target.toString());
        location.addProperty("previous", current.toString());
        JsonFiles.write(bootstrap.resolve(LOCATION), location);
    }

    public static Path resolve(Path bootstrap) throws IOException {
        Path pointer = bootstrap.resolve(LOCATION);
        if (!Files.exists(pointer)) {
            return bootstrap.toAbsolutePath().normalize();
        }
        JsonObject location = JsonFiles.read(pointer);
        Path target = Path.of(location.get("directory").getAsString()).toAbsolutePath().normalize();
        if (location.has("previous")) {
            Path source = Path.of(location.get("previous").getAsString()).toAbsolutePath().normalize();
            if (!source.equals(target)) {
                Files.createDirectories(source);
                try (FileChannel channel = FileChannel.open(source.resolve("launcher.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {
                    var lock = channel.tryLock();
                    if (lock == null) {
                        throw new IOException("请先退出旧启动器，再切换数据目录");
                    }
                    try (lock) {
                        transfer(source, target);
                    }
                } catch (java.nio.channels.OverlappingFileLockException ex) {
                    throw new IOException("请先退出旧启动器，再切换数据目录", ex);
                }
            }
            location.remove("previous");
            JsonFiles.write(pointer, location);
        }
        return target;
    }

    private static void transfer(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        Path marker = target.resolve(".data-migration.json");
        if (Files.exists(marker)) {
            if (!source.toString().equals(JsonFiles.string(JsonFiles.read(marker), "source", ""))) {
                throw new IOException("目标目录属于其他数据迁移");
            }
        } else {
            try (var children = Files.list(target)) {
                if (children.findAny().isPresent()) {
                    throw new IOException("目标数据目录已有内容，未覆盖");
                }
            }
            JsonFiles.write(marker, Map.of("source", source.toString()));
        }
        for (String name : STATE) {
            Path file = source.resolve(name);
            if (Files.isRegularFile(file) && !Files.isSymbolicLink(file)) {
                JsonFiles.atomicWrite(target.resolve(name), Files.readAllBytes(file));
            }
        }
        for (String folder : List.of("logs", "skins", "config")) {
            Path logs = source.resolve(folder);
            if (Files.isDirectory(logs) && !Files.isSymbolicLink(logs)) {
                try (var files = Files.walk(logs)) {
                    for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                        Path relative = logs.relativize(file);
                        Path destination = JsonFiles.safeResolve(target.resolve(folder), relative.toString());
                        Files.createDirectories(destination.getParent());
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        Path runtimes = source.resolve("runtimes");
        if (Files.isDirectory(runtimes)) {
            try (var markers = Files.newDirectoryStream(runtimes, "java-*.json")) {
                for (Path file : markers) {
                    if (!Files.isSymbolicLink(file)) {
                        JsonFiles.atomicWrite(target.resolve("runtimes").resolve(file.getFileName()), Files.readAllBytes(file));
                    }
                }
            }
        }
        // Keep the migration marker for a safe retry if writing the stable pointer fails.
    }
}
