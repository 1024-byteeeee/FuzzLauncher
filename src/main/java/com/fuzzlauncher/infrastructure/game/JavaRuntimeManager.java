package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.config.LauncherConfig;
import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.infrastructure.*;

import com.google.gson.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Game runtimes are independent of the launcher's trimmed, bundled Java runtime. */
public final class JavaRuntimeManager {
    private final Path directory;

    public JavaRuntimeManager(Path directory) {
        this.directory = directory;
    }

    public int requiredMajor(JsonObject version) {
        if (version.has("javaVersion")) {
            return version.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
        }
        String id = JsonFiles.string(version, "_clientVersion", JsonFiles.string(version, "id", ""));
        return LauncherConfig.get().java().requiredMajor(id);
    }

    public Path findInstalled(int major) throws IOException {
        Path marker = directory.resolve("java-" + major + ".json");
        if (Files.exists(marker)) {
            Path home = Path.of(JsonFiles.read(marker).get("home").getAsString());
            if (major(home) == major && Files.isRegularFile(home.resolve("bin/java.exe"))) {
                return home.resolve("bin/java.exe");
            }
        }
        LinkedHashSet<Path> homes = new LinkedHashSet<>();
        String env = System.getenv("JAVA_HOME");
        if (env != null) {
            homes.add(Path.of(env));
        }
        Path current = Path.of(System.getProperty("java.home"));
        if (Files.isDirectory(current.resolve("jmods"))) {
            homes.add(current);
        }
        String programs = Objects.toString(System.getenv("ProgramFiles"), "C:/Program Files");
        for (String vendor : LauncherConfig.get().java().vendorDirectories()) {
            Path parent = Path.of(programs, vendor);
            if (Files.isDirectory(parent)) {
                try (var children = Files.list(parent)) {
                    children.filter(Files::isDirectory).forEach(homes::add);
                }
            }
        }
        for (Path home : homes) {
            if (major(home) == major && Files.isRegularFile(home.resolve("bin/java.exe"))) {
                return home.resolve("bin/java.exe");
            }
        }
        return null;
    }

    public Path resolve(int major, TaskContext task) throws Exception {
        task.checkCancelled();
        Path installed = findInstalled(major);
        if (installed != null) {
            return installed;
        }
        throw new IOException("未找到可用的 Java " + major + "。\n请先安装对应版本的 Java，或在实例详情的“Java 路径”中指定 java.exe 后重试。");
    }

    public static Path configuredExecutable(String configured) throws IOException {
        String value = configured.strip();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        Path executable = Path.of(value).toAbsolutePath().normalize();
        if (Files.isDirectory(executable)) {
            executable = Files.isRegularFile(executable.resolve("java.exe")) ? executable.resolve("java.exe")
                : executable.resolve("bin/java.exe");
        }
        if (executable.getFileName().toString().equalsIgnoreCase("javaw.exe")) {
            executable = executable.resolveSibling("java.exe");
        }
        if (!executable.getFileName().toString().equalsIgnoreCase("java.exe") || !Files.isRegularFile(executable)) {
            throw new IOException("Java 路径无效，请选择 java.exe 或包含 bin/java.exe 的 Java 目录");
        }
        return executable;
    }

    public Path resolve(int required, String configured, TaskContext task) throws Exception {
        if (configured == null || configured.isBlank()) {
            return resolve(required, task);
        }
        task.checkCancelled();
        Path executable = configuredExecutable(configured);
        Path home = executable.getParent().getParent();
        int actual = home == null ? -1 : major(home);
        if (actual < 0) {
            Process probe = new ProcessBuilder(executable.toString(), "-version").redirectErrorStream(true).start();
            try {
                if (!probe.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IOException("所选 Java 版本检测超时");
                }
                String output = new String(probe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                var matcher = java.util.regex.Pattern.compile("version \"(?:1\\.)?(\\d+)").matcher(output);
                if (probe.exitValue() != 0 || !matcher.find()) {
                    throw new IOException("无法识别所选 Java 版本");
                }
                actual = Integer.parseInt(matcher.group(1));
            } finally {
                ProcessCleanup.terminate(probe);
            }
        }
        if (actual < required) {
            throw new IOException("此实例需要 Java " + required + " 或更高版本，所选 Java 为 " + actual);
        }
        task.report("使用实例指定的 Java " + actual, -1);
        return executable;
    }

    private static int major(Path home) throws IOException {
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release)) {
            return -1;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(release)) {
            props.load(reader);
        }
        String version = props.getProperty("JAVA_VERSION", "").replace("\"", "");
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        String number = version.split("[.\\-+_]")[0];
        return number.matches("[0-9]+") ? Integer.parseInt(number) : -1;
    }
}
