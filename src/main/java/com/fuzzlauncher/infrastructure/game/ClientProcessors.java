package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.infrastructure.*;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.*;
import java.util.zip.*;

/** Executes official install-profile client processors with launcher-managed networking. */
public final class ClientProcessors {
    private final HttpTransport http;
    private final BaseVersions baseVersions;
    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Z_]+)}");

    public ClientProcessors(HttpTransport http) {
        this(http, new BaseVersions());
    }

    public ClientProcessors(HttpTransport http, BaseVersions baseVersions) {
        this.http = http;
        this.baseVersions = baseVersions;
    }

    public JsonObject execute(Path root, Path staging, Path installer, JsonObject profile, JsonObject vanilla, Path java, TaskContext task)
        throws Exception {
        JsonObject version;
        try (ZipFile zip = new ZipFile(installer.toFile())) {
            String json = profile.get("json").getAsString();
            version = read(zip, json);
            // Installer archives contain immutable Maven artifacts in addition to their processors.
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                task.checkCancelled();
                if (!entry.isDirectory() && entry.getName().startsWith("maven/")) {
                    Path target = JsonFiles.safeResolve(staging.resolve("libraries"), entry.getName().substring("maven/".length()));
                    Files.createDirectories(target.getParent());
                    try (InputStream stream = zip.getInputStream(entry)) {
                        Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            downloadLibraries(root, staging, profile, task);
            downloadLibraries(root, staging, version, task);
            Map<String, String> variables = new HashMap<>();
            variables.put("SIDE", "client");
            variables.put("ROOT", staging.toString());
            variables.put("INSTALLER", installer.toString());
            variables.put("LIBRARY_DIR", staging.resolve("libraries").toString());
            String game = vanilla.get("id").getAsString();
            variables.put("MINECRAFT_VERSION", game);
            variables.put("MINECRAFT_JAR", baseVersions.client(root, game).toString());
            JsonObject data = profile.has("data") ? profile.getAsJsonObject("data") : new JsonObject();
            for (var item : data.entrySet()) {
                if (item.getValue().getAsJsonObject().has("client")) {
                    variables.put(item.getKey(), item.getValue().getAsJsonObject().get("client").getAsString());
                }
            }
            // Data values can refer to other data values. Resolve with a bounded cycle check.
            for (String key : new ArrayList<>(variables.keySet())) {
                variables.put(key, resolve(variables.get(key), variables, staging, zip, new HashSet<>(Set.of(key))));
            }
            Path log = staging.resolve("installer-output.log");
            int number = 0;
            for (JsonElement item : profile.getAsJsonArray("processors")) {
                task.checkCancelled();
                JsonObject processor = item.getAsJsonObject();
                if (processor.has("sides") && !processor.getAsJsonArray("sides").contains(new JsonPrimitive("client"))) {
                    continue;
                }
                List<String> args = new ArrayList<>();
                for (JsonElement arg : processor.getAsJsonArray("args")) {
                    args.add(resolve(arg.getAsString(), variables, staging, zip, new HashSet<>()));
                }
                task.report("安装处理器 " + (++number) + ": " + processor.get("jar").getAsString(), -1);
                if (args.contains("DOWNLOAD_MOJMAPS")) {
                    int output = args.indexOf("--output"), side = args.indexOf("--side");
                    if (output < 0 || output + 1 >= args.size()) {
                        throw new IOException("映射下载处理器缺少输出参数");
                    }
                    String selectedSide = side >= 0 ? args.get(side + 1) : "client";
                    JsonObject download = vanilla.getAsJsonObject("downloads").getAsJsonObject(selectedSide + "_mappings");
                    if (download == null) {
                        throw new IOException("游戏版本未提供官方映射文件");
                    }
                    Path target = Path.of(args.get(output + 1));
                    if (!target.toAbsolutePath().normalize().startsWith(staging.toAbsolutePath().normalize())) {
                        throw new IOException("处理器输出超出安装目录");
                    }
                    boolean sanitize = args.contains("--sanitize");
                    Path downloaded = sanitize ? target.resolveSibling(target.getFileName() + ".raw") : target;
                    http.download(download.get("url").getAsString(), downloaded, download.get("sha1").getAsString(), task);
                    if (sanitize) {
                        sanitizeMappings(processor, staging, downloaded, target, args);
                    }
                    Files.writeString(log, "Downloaded official " + selectedSide + " mappings with verified SHA-1\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } else {
                    Path jar = JsonFiles.safeResolve(staging.resolve("libraries"),
                        VersionResolver.mavenPath(processor.get("jar").getAsString()));
                    List<String> classpath = new ArrayList<>();
                    classpath.add(jar.toString());
                    for (JsonElement library : processor.getAsJsonArray("classpath")) {
                        classpath.add(JsonFiles.safeResolve(staging.resolve("libraries"), VersionResolver.mavenPath(library.getAsString()))
                            .toString());
                    }
                    String main;
                    try (JarFile file = new JarFile(jar.toFile())) {
                        main = file.getManifest().getMainAttributes().getValue("Main-Class");
                    }
                    if (main == null) {
                        throw new IOException("安装处理器缺少 Main-Class: " + jar.getFileName());
                    }
                    List<String> command = new ArrayList<>(
                        List.of(java.toString(), "-Djava.awt.headless=true", "-cp", String.join(File.pathSeparator, classpath), main));
                    command.addAll(args);
                    Files.writeString(log, "Processor: " + processor.get("jar").getAsString() + "\n", StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                    Process process = new ProcessBuilder(command).directory(staging.toFile()).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
                    Instant deadline = Instant.now().plus(Duration.ofMinutes(10));
                    try {
                        while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                            task.checkCancelled();
                            if (Instant.now().isAfter(deadline)) {
                                throw new IOException("处理器超时: " + log);
                            }
                        }
                        if (process.exitValue() != 0) {
                            throw new IOException("安装处理器失败，退出码 " + process.exitValue() + "，日志: " + log);
                        }
                    } finally {
                        if (process.isAlive()) {
                            process.descendants().forEach(ProcessHandle::destroyForcibly);
                            process.destroyForcibly();
                            // Release installer output/JAR handles before the caller removes its
                            // workspace.
                            boolean interrupted = Thread.interrupted();
                            try {
                                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                                    LauncherLog.LOGGER.warning("安装处理器尚未退出，临时文件可能仍被占用");
                                }
                            } catch (InterruptedException ex) {
                                interrupted = true;
                            } finally {
                                if (interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    }
                }
                if (processor.has("outputs")) {
                    for (var output : processor.getAsJsonObject("outputs").entrySet()) {
                        Path target = Path.of(resolve(output.getKey(), variables, staging, zip, new HashSet<>()));
                        String expected = resolve(output.getValue().getAsString(), variables, staging, zip, new HashSet<>());
                        if (!Files.isRegularFile(target) || !HttpTransport.digest(target, "SHA-1").equalsIgnoreCase(expected)) {
                            throw new IOException("处理器产物校验失败: " + target.getFileName());
                        }
                    }
                }
            }
        }
        return version;
    }

    /** Preserve Forge's declared --sanitize/--format semantics using its exact srgutils version. */
    private static void sanitizeMappings(JsonObject processor, Path staging, Path input, Path output, List<String> args) throws Exception {
        List<java.net.URL> libraries = new ArrayList<>();
        for (JsonElement coordinate : processor.getAsJsonArray("classpath")) {
            libraries.add(
                JsonFiles.safeResolve(staging.resolve("libraries"), VersionResolver.mavenPath(coordinate.getAsString())).toUri().toURL());
        }
        try (
            java.net.URLClassLoader loader = new java.net.URLClassLoader(libraries.toArray(java.net.URL[]::new),
                ClassLoader.getPlatformClassLoader());
            InputStream stream = Files.newInputStream(input)) {
            Class<?> mapping = loader.loadClass("net.minecraftforge.srgutils.IMappingFile");
            Class<?> formats = loader.loadClass("net.minecraftforge.srgutils.IMappingFile$Format");
            int formatIndex = args.indexOf("--format");
            String requested = formatIndex >= 0 ? args.get(formatIndex + 1) : "TSRG";
            Object format = Arrays.stream(formats.getEnumConstants()).filter(v -> ((Enum<?>) v).name().equals(requested)).findFirst()
                .orElseThrow(() -> new IOException("不支持的映射格式: " + requested));
            Object file = mapping.getMethod("load", InputStream.class).invoke(null, stream);
            mapping.getMethod("write", Path.class, formats, boolean.class).invoke(file, output, format, false);
        }
    }

    private void downloadLibraries(Path root, Path staging, JsonObject source, TaskContext task) throws Exception {
        if (!source.has("libraries")) {
            return;
        }
        for (JsonElement item : source.getAsJsonArray("libraries")) {
            task.checkCancelled();
            JsonObject library = item.getAsJsonObject();
            if (!VersionResolver.allowed(library)) {
                continue;
            }
            String coordinate = library.get("name").getAsString();
            if (library.has("downloads") && library.getAsJsonObject("downloads").has("artifact")) {
                JsonObject artifact = library.getAsJsonObject("downloads").getAsJsonObject("artifact");
                String url = JsonFiles.string(artifact, "url", "");
                if (url.isBlank()) {
                    continue;
                }
                Path target = JsonFiles.safeResolve(staging.resolve("libraries"), artifact.get("path").getAsString());
                task.report("安装依赖: " + coordinate, -1);
                downloadCached(root, staging, url, target, JsonFiles.string(artifact, "sha1", ""), task);
            } else if (library.has("url") && !library.get("url").getAsString().isBlank()) {
                String path = VersionResolver.mavenPath(coordinate), base = library.get("url").getAsString();
                task.report("安装依赖: " + coordinate, -1);
                downloadCached(root, staging, base + (base.endsWith("/") ? "" : "/") + path,
                    JsonFiles.safeResolve(staging.resolve("libraries"), path), JsonFiles.string(library, "sha1", ""), task);
            }
        }
    }

    private void downloadCached(Path root, Path staging, String url, Path target, String sha1, TaskContext task) throws Exception {
        Path cache = root.resolve("libraries").resolve(staging.resolve("libraries").toAbsolutePath().relativize(target.toAbsolutePath()));
        if (!sha1.isBlank() && Files.isRegularFile(cache) && HttpTransport.digest(cache, "SHA-1").equalsIgnoreCase(sha1)) {
            Files.createDirectories(target.getParent());
            Files.copy(cache, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            http.download(url, target, sha1, task);
        }
    }

    private static JsonObject read(ZipFile zip, String path) throws IOException {
        ZipEntry entry = zip.getEntry(path.startsWith("/") ? path.substring(1) : path);
        if (entry == null) {
            throw new IOException("安装器缺少 " + path);
        }
        try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String resolve(String value, Map<String, String> variables, Path staging, ZipFile zip, Set<String> chain)
        throws IOException {
        Matcher matcher = VARIABLE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!chain.add(key)) {
                throw new IOException("安装变量循环: " + key);
            }
            String replacement = variables.get(key);
            if (replacement == null) {
                throw new IOException("未知安装变量: " + key);
            }
            replacement = resolve(replacement, variables, staging, zip, chain);
            chain.remove(key);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        String resolved = result.toString();
        if (resolved.startsWith("'") && resolved.endsWith("'")) {
            return resolved.substring(1, resolved.length() - 1);
        }
        if (resolved.startsWith("[") && resolved.endsWith("]")) {
            return JsonFiles
                .safeResolve(staging.resolve("libraries"), VersionResolver.mavenPath(resolved.substring(1, resolved.length() - 1)))
                .toString();
        }
        if (resolved.startsWith("/")) {
            ZipEntry entry = zip.getEntry(resolved.substring(1));
            if (entry != null && !entry.isDirectory()) {
                Path dest = JsonFiles.safeResolve(staging.resolve("embedded"), resolved.substring(1));
                Files.createDirectories(dest.getParent());
                if (!Files.exists(dest)) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        Files.copy(input, dest);
                    }
                }
                return dest.toString();
            }
        }
        return resolved;
    }
}
