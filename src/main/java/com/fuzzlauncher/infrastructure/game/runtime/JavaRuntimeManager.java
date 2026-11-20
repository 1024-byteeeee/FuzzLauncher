package com.fuzzlauncher.infrastructure.game.runtime;

import com.fuzzlauncher.application.model.JavaRuntimeInfo;
import com.fuzzlauncher.application.port.JavaRuntimeCatalog;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.infrastructure.config.LauncherConfig;
import com.fuzzlauncher.infrastructure.persistence.JsonFiles;
import com.fuzzlauncher.infrastructure.platform.ProcessCleanup;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Detects local Java runtimes and resolves the executable used to launch a game. */
public final class JavaRuntimeManager implements JavaRuntimeCatalog {
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

    @Override
    public List<JavaRuntimeInfo> detected() throws Exception {
        Map<Path, JavaRuntimeInfo> runtimes = new LinkedHashMap<>();
        addMarkedRuntimes(runtimes);
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            addHome(Path.of(javaHome), runtimes);
        }
        Path current = Path.of(System.getProperty("java.home"));
        if (Files.isDirectory(current.resolve("jmods"))) {
            addHome(current, runtimes);
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            addRegistryRuntimes(runtimes);
            addProgramFilesRuntimes(runtimes);
        }
        addPathRuntimes(runtimes);
        return List.copyOf(runtimes.values());
    }

    public Path resolve(int requiredMajor, String configured, TaskContext task) throws Exception {
        task.checkCancelled();
        if (configured != null && !configured.isBlank()) {
            return configuredExecutable(configured);
        }
        JavaRuntimeInfo chosen = choose(detected(), requiredMajor);
        if (chosen == null) {
            throw new IOException("未找到可用的 Java。\n请先安装 Java，或在实例详情中指定 java.exe 路径后重试。");
        }
        return chosen.executable();
    }

    public static JavaRuntimeInfo choose(List<JavaRuntimeInfo> candidates, int requiredMajor) {
        if (candidates.isEmpty()) {
            return null;
        }
        Comparator<JavaRuntimeInfo> architectureFirst = Comparator.comparing(JavaRuntimeInfo::is64Bit).reversed();
        List<JavaRuntimeInfo> satisfied = candidates.stream()
            .filter(java -> java.major() >= requiredMajor)
            .sorted(architectureFirst.thenComparingInt(JavaRuntimeInfo::major))
            .toList();
        if (!satisfied.isEmpty()) {
            return satisfied.getFirst();
        }
        return candidates.stream()
            .sorted(Comparator.comparingInt(JavaRuntimeInfo::major).reversed().thenComparing(architectureFirst))
            .findFirst().orElse(null);
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

    private void addMarkedRuntimes(Map<Path, JavaRuntimeInfo> runtimes) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var markers = Files.newDirectoryStream(directory, "java-*.json")) {
            for (Path marker : markers) {
                try {
                    JsonObject data = JsonFiles.read(marker);
                    addHome(Path.of(data.get("home").getAsString()), runtimes);
                } catch (IOException | RuntimeException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void addProgramFilesRuntimes(Map<Path, JavaRuntimeInfo> runtimes) {
        List<String> roots = List.of(Objects.toString(System.getenv("ProgramFiles"), "C:/Program Files"),
            Objects.toString(System.getenv("ProgramFiles(x86)"), "C:/Program Files (x86)"));
        for (String root : roots) {
            Path parent = Path.of(root);
            if (!Files.isDirectory(parent)) {
                continue;
            }
            for (String vendor : LauncherConfig.get().java().vendorDirectories()) {
                Path vendorDir = parent.resolve(vendor);
                if (!Files.isDirectory(vendorDir)) {
                    continue;
                }
                try (var children = Files.list(vendorDir)) {
                    children.filter(Files::isDirectory).forEach(child -> addHome(child, runtimes));
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void addPathRuntimes(Map<Path, JavaRuntimeInfo> runtimes) {
        String path = System.getenv("PATH");
        if (path == null) {
            return;
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                addExecutable(Path.of(entry, "java.exe"), runtimes);
            } catch (InvalidPathException ignored) {
            }
        }
    }

    private void addRegistryRuntimes(Map<Path, JavaRuntimeInfo> runtimes) {
        String[] keys = { "SOFTWARE\\JavaSoft\\Java Runtime Environment", "SOFTWARE\\JavaSoft\\Java Development Kit",
            "SOFTWARE\\JavaSoft\\JRE", "SOFTWARE\\JavaSoft\\JDK", "SOFTWARE\\WOW6432Node\\JavaSoft\\Java Runtime Environment",
            "SOFTWARE\\WOW6432Node\\JavaSoft\\JDK" };
        for (String key : keys) {
            for (String home : queryRegistryJavaHome(key)) {
                addHome(Path.of(home), runtimes);
            }
        }
    }

    private List<String> queryRegistryJavaHome(String key) {
        Process process = null;
        try {
            process = new ProcessBuilder("reg", "query", "HKLM\\" + key, "/s", "/v", "JavaHome")
                .redirectErrorStream(true).start();
            List<String> homes = new ArrayList<>();
            Pattern pattern = Pattern.compile("JavaHome\\s+REG_SZ\\s+(.+)");
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.ISO_8859_1))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        homes.add(matcher.group(1).strip());
                    }
                }
            }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                ProcessCleanup.terminate(process);
            }
            return homes;
        } catch (IOException | InterruptedException ignored) {
            if (process != null && process.isAlive()) {
                ProcessCleanup.terminateQuietly(process);
            }
            return List.of();
        }
    }

    private void addHome(Path home, Map<Path, JavaRuntimeInfo> runtimes) {
        if (home == null || !Files.isDirectory(home)) {
            return;
        }
        Path executable = home.resolve("bin/java.exe");
        if (!Files.isRegularFile(executable) && home.getFileName() != null
            && home.getFileName().toString().equalsIgnoreCase("bin")) {
            executable = home.resolve("java.exe");
        }
        addExecutable(executable, runtimes);
    }

    private void addExecutable(Path executable, Map<Path, JavaRuntimeInfo> runtimes) {
        try {
            if (!Files.isRegularFile(executable)) {
                return;
            }
            Path real = executable.toRealPath();
            if (runtimes.containsKey(real)) {
                return;
            }
            JvmEnvironment environment = JvmEnvironment.inspect(real);
            runtimes.put(real, new JavaRuntimeInfo(real, environment.major(), environment.is64Bit()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
