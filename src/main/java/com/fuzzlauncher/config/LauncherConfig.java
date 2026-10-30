package com.fuzzlauncher.config;

import com.fuzzlauncher.domain.*;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Validated release data plus a restricted local override, loaded once at startup. */
public record LauncherConfig(Defaults defaults, Network network, Downloads downloads, Map<String, String> services,
    Authentication authentication, JavaRuntime java, List<JvmRule> jvmRules) {

    private static volatile LauncherConfig current;
    private static final Gson GSON = new Gson();

    public LauncherConfig {
        services = Map.copyOf(services);
        jvmRules = List.copyOf(jvmRules);
    }

    public static LauncherConfig get() {
        LauncherConfig result = current;
        if (result == null) {
            synchronized (LauncherConfig.class) {
                result = current;
                if (result == null) {
                    try {
                        current = result = load(null);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }
            }
        }
        return result;
    }

    public static void initialize(Path dataDirectory) throws IOException {
        current = load(dataDirectory.resolve("config/launcher.json"));
    }

    public static LauncherConfig load(Path override) throws IOException {
        try {
            JsonObject data = new JsonObject();
            for (String resource : List.of("defaults", "downloads", "services", "compatibility", "jvm")) {
                try (InputStream input = LauncherConfig.class.getResourceAsStream("/com/fuzzlauncher/config/" + resource + ".json")) {
                    if (input == null) {
                        throw new IOException("缺少内置配置：" + resource);
                    }
                    try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                        read(reader).entrySet().forEach(e -> data.add(e.getKey(), e.getValue()));
                    }
                }
            }
            if (override != null && Files.exists(override)) {
                try (Reader reader = Files.newBufferedReader(override)) {
                    JsonObject custom = read(reader);
                    require(custom.has("schemaVersion") && custom.get("schemaVersion").getAsInt() == 1, "schemaVersion 必须为 1");
                    for (String key : custom.keySet()) {
                        require(Set.of("schemaVersion", "defaults", "network").contains(key), "不允许覆盖配置项：" + key);
                    }
                    merge(data, custom, "");
                }
            }
            require(data.get("schemaVersion").getAsInt() == 1, "不支持的内置配置版本");
            LauncherConfig result = GSON.fromJson(data, LauncherConfig.class);
            result.validate();
            return result;
        } catch (RuntimeException ex) {
            throw new IOException("启动器配置无效" + (override == null ? "" : "（" + override + "）") + "：" + ex.getMessage(), ex);
        }
    }

    private static JsonObject read(Reader reader) throws IOException {
        JsonReader json = new JsonReader(reader);
        json.setStrictness(Strictness.STRICT);
        JsonObject result = JsonParser.parseReader(json).getAsJsonObject();
        require(json.peek() == JsonToken.END_DOCUMENT, "JSON 对象之后存在多余内容");
        return result;
    }

    private static void merge(JsonObject base, JsonObject custom, String path) {
        for (var entry : custom.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            require(base.has(key), "未知配置项：" + path + key);
            JsonElement original = base.get(key);
            require(!value.isJsonNull(), "配置不能为空：" + path + key);
            if (original.isJsonObject()) {
                require(value.isJsonObject(), "配置应为对象：" + path + key);
                merge(original.getAsJsonObject(), value.getAsJsonObject(), path + key + ".");
            } else {
                require(original.isJsonArray() == value.isJsonArray(), "配置类型错误：" + path + key);
                if (original.isJsonPrimitive()) {
                    JsonPrimitive expected = original.getAsJsonPrimitive();
                    require(value.isJsonPrimitive(), "配置类型错误：" + path + key);
                    JsonPrimitive actual = value.getAsJsonPrimitive();
                    require(expected.isNumber() == actual.isNumber() && expected.isString() == actual.isString(), "配置类型错误：" + path + key);
                    if (expected.isNumber()) {
                        require(actual.getAsBigDecimal().stripTrailingZeros().scale() <= 0, "配置必须为整数：" + path + key);
                        actual.getAsBigDecimal().intValueExact();
                    }
                }
                base.add(key, value.deepCopy());
            }
        }
    }

    private void validate() {
        require(defaults.minMemoryMb >= 128 && defaults.minMemoryMb <= defaults.maxMemoryMb && defaults.maxMemoryMb >= 512
            && defaults.maxMemoryMb <= 65536, "默认内存范围无效");
        require(defaults.gameDirectory != null && defaults.gameDirectory.matches("[a-zA-Z0-9_-]+"), "默认游戏目录必须为目录名");
        Objects.requireNonNull(defaults.isolation, "默认隔离模式无效");
        Objects.requireNonNull(defaults.downloadSource(), "默认下载源无效");
        Objects.requireNonNull(defaults.launcherVisibility, "默认可见性无效");
        for (String argument : defaults.jvmArguments) {
            require(!argument.isBlank() && argument.startsWith("-") && !argument.startsWith("-Xms") && !argument.startsWith("-Xmx")
                && !argument.equals("-cp") && !argument.equals("-classpath") && !argument.equals("-jar")
                && !argument.chars().anyMatch(c -> c == 0 || c == '\n' || c == '\r'), "默认 JVM 参数无效：" + argument);
        }
        bounded(network.connectTimeoutSeconds, 1, 300, "连接超时");
        bounded(network.requestTimeoutSeconds, 1, 3600, "请求超时");
        bounded(network.downloadTimeoutSeconds, 1, 3600, "下载超时");
        bounded(network.idleTimeoutSeconds, 1, 600, "无数据超时");
        bounded(network.attempts, 1, 10, "下载尝试次数");
        bounded(network.threadsPerProcessor, 1, 16, "每核下载线程数");
        bounded(network.maxConcurrency, 1, 128, "最大下载并发");
        bounded(network.progressIntervalMillis, 100, 10000, "进度更新间隔");
        for (DownloadSource source : DownloadSource.values()) {
            Source value = Objects.requireNonNull(downloads.sources.get(source.name()), "缺少下载源：" + source.name());
            require(!value.label.isBlank(), "下载源名称为空");
            https(value.website);
        }
        for (Route rule : downloads.rules) {
            rule.validate();
        }
        for (Route rule : downloads.fallbacks) {
            rule.validate();
        }
        for (String name : Set.of("manifest", "fabricMeta", "forgeMaven", "neoMaven", "libraries", "assets", "javaApi", "sessionProfile",
            "textureBase", "minecraftBase")) {
            https(service(name));
        }
        services.values().forEach(LauncherConfig::https);
        UUID.fromString(authentication.clientId);
        https(authentication.authority);
        https(authentication.xboxAuth);
        https(authentication.xstsAuth);
        require(!authentication.scopes.isEmpty(), "登录 scope 为空");
        bounded(java.fallbackMajor, 8, 99, "默认 Java 版本");
        for (JavaRule rule : java.rules) {
            Pattern.compile(rule.pattern);
            bounded(rule.major, 8, 99, "兼容 Java 版本");
        }
        for (String vendor : java.vendorDirectories) {
            require(!vendor.isBlank() && !vendor.contains("/") && !vendor.contains("\\") && !vendor.equals(".."), "Java 厂商目录无效");
        }
        for (String value : List.of(java.architecture, java.imageType, java.os, java.vendor)) {
            require(value.matches("[a-zA-Z0-9_-]+"), "Java 下载参数无效");
        }
        for (JvmRule rule : jvmRules) {
            require(rule.minJava >= 8 && rule.maxJava >= rule.minJava && rule.maxJava <= 99, "JVM 规则版本范围无效");
            require(Set.of("common", "hotspot", "g1", "large64", "compact64", "32bit", "windows", "windows10").contains(rule.group),
                "JVM 规则分组无效");
            for (String argument : rule.arguments) {
                require(argument.startsWith("-") && !argument.contains("\n") && !argument.contains("\r") && !argument.contains("\0"),
                    "JVM 规则参数无效");
            }
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void bounded(int value, int minimum, int maximum, String name) {
        require(value >= minimum && value <= maximum, name + "应在 " + minimum + "–" + maximum + " 之间");
    }

    private static void https(String value) {
        URI uri = URI.create(value);
        require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null
            && uri.getFragment() == null && (uri.getPort() == -1 || uri.getPort() == 443), "服务地址无效：" + value);
    }

    public String service(String name) {
        return Objects.requireNonNull(services.get(name), "缺少服务配置：" + name);
    }

    public String loaderArtifact(LoaderType type, String game) {
        return type == LoaderType.FORGE || java.neoForgeLegacyGames.contains(game) ? "forge" : "neoforge";
    }

    public String loaderMaven(LoaderType type, String game) {
        return type == LoaderType.FORGE ? service("forgeMaven") : service("neoMaven") + loaderArtifact(type, game) + "/";
    }

    public record Defaults(int minMemoryMb, int maxMemoryMb, String gameDirectory, IsolationMode isolation,
        @com.google.gson.annotations.SerializedName("downloadSource") String downloadSourceId, LauncherVisibility launcherVisibility,
        List<String> jvmArguments) {
        public Defaults {
            jvmArguments = List.copyOf(jvmArguments);
        }

        // Gson's enum adapter calls toString(), which reads the configured display label.
        // Decode the identifier ourselves so loading configuration does not recurse into itself.
        public DownloadSource downloadSource() {
            return DownloadSource.valueOf(downloadSourceId);
        }
    }

    public record Network(int connectTimeoutSeconds, int requestTimeoutSeconds, int downloadTimeoutSeconds, int idleTimeoutSeconds,
        int attempts, int threadsPerProcessor, int maxConcurrency, int progressIntervalMillis) {
        public int concurrency() {
            return Math.min(Runtime.getRuntime().availableProcessors() * threadsPerProcessor, maxConcurrency);
        }
    }

    public record Source(String label, String website) {
    }

    public record JvmRule(int minJava, int maxJava, String group, List<String> arguments) {
        public JvmRule {
            arguments = List.copyOf(arguments);
        }
    }

    public record Route(List<String> hosts, String pathPrefix, String target) {
        public Route {
            hosts = List.copyOf(hosts);
        }

        private void validate() {
            require(!hosts.isEmpty() && hosts.stream().allMatch(host -> host.matches("[a-z0-9.-]+")), "镜像来源主机无效");
            require(pathPrefix.startsWith("/") && pathPrefix.endsWith("/"), "镜像路径前缀无效");
            https(target);
            require(target.endsWith("/"), "镜像目标地址必须以 / 结尾");
        }

        public Optional<String> resolve(URI original) {
            if (hosts.stream().noneMatch(host -> host.equalsIgnoreCase(original.getHost()))
                || !original.getRawPath().startsWith(pathPrefix)) {
                return Optional.empty();
            }
            return Optional.of(target + original.getRawPath().substring(pathPrefix.length())
                + (original.getRawQuery() == null ? "" : "?" + original.getRawQuery()));
        }
    }

    public record Downloads(List<String> autoMirrorRegions, Map<String, Source> sources, List<Route> rules, List<Route> fallbacks) {
        public Downloads {
            autoMirrorRegions = List.copyOf(autoMirrorRegions);
            sources = Map.copyOf(sources);
            rules = List.copyOf(rules);
            fallbacks = List.copyOf(fallbacks);
        }
    }

    public record Authentication(String clientId, String authority, Set<String> scopes, String xboxAuth, String xstsAuth) {
        public Authentication {
            scopes = Set.copyOf(scopes);
        }
    }

    public record JavaRule(String pattern, int major) {
    }

    public record JavaRuntime(List<String> vendorDirectories, String architecture, String imageType, String os, String vendor,
        int fallbackMajor, List<JavaRule> rules, Set<String> neoForgeLegacyGames) {
        public JavaRuntime {
            vendorDirectories = List.copyOf(vendorDirectories);
            rules = List.copyOf(rules);
            neoForgeLegacyGames = Set.copyOf(neoForgeLegacyGames);
        }

        public int requiredMajor(String version) {
            return rules.stream().filter(rule -> version.matches(rule.pattern)).mapToInt(JavaRule::major).findFirst().orElse(fallbackMajor);
        }
    }
}
