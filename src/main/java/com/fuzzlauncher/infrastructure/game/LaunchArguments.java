package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.config.LauncherConfig;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.BaseVersions;
import com.fuzzlauncher.infrastructure.JsonFiles;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

public final class LaunchArguments {
    private final BaseVersions baseVersions;

    public LaunchArguments() {
        this(new BaseVersions());
    }

    public LaunchArguments(BaseVersions baseVersions) {
        this.baseVersions = baseVersions;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    public List<String> build(Path root, Path gameDir, Path natives, GameInstance instance, GameSession session, JsonObject version,
        List<Path> libraries) throws IOException {
        return build(root, gameDir, natives, instance, session, version, libraries, null);
    }

    public List<String> build(Path root, Path gameDir, Path natives, GameInstance instance, GameSession session, JsonObject version,
        List<Path> libraries, JvmEnvironment environment) throws IOException {
        String client = version.get("_clientVersion").getAsString();
        List<Path> classpath = new ArrayList<>(libraries);
        classpath.add(version.has("_clientJarPath") ? JsonFiles.safeResolve(root, version.get("_clientJarPath").getAsString())
            : baseVersions.client(root, client));
        Map<String, String> values = new HashMap<>();
        values.put("natives_directory", natives.toAbsolutePath().toString());
        values.put("launcher_name", "FuzzLauncher");
        values.put("launcher_version", com.fuzzlauncher.Launcher.VERSION);
        values.put("classpath",
            String.join(java.io.File.pathSeparator, classpath.stream().map(p -> p.toAbsolutePath().toString()).toList()));
        values.put("classpath_separator", java.io.File.pathSeparator);
        values.put("library_directory", root.resolve("libraries").toAbsolutePath().toString());
        values.put("auth_player_name", session.account().name());
        values.put("auth_uuid", session.account().uuid());
        values.put("auth_access_token", session.accessToken());
        values.put("auth_session", session.account().isOffline() ? "0" : "token:" + session.accessToken() + ":" + session.account().uuid());
        values.put("version_name", instance.name());
        values.put("game_directory", gameDir.toAbsolutePath().toString());
        values.put("assets_root", root.resolve("assets").toAbsolutePath().toString());
        values.put("assets_index_name", version.has("assetIndex") ? version.getAsJsonObject("assetIndex").get("id").getAsString() : "");
        values.put("game_assets", root.resolve("assets/virtual").resolve(values.get("assets_index_name")).toAbsolutePath().toString());
        values.put("user_type", session.account().isOffline() ? "legacy" : "msa");
        values.put("user_properties", "{}");
        values.put("version_type", JsonFiles.string(version, "type", "release"));
        values.put("clientid", "");
        values.put("auth_xuid", "");
        List<String> result = new ArrayList<>(defaultJvm(instance));
        JsonObject arguments = version.has("arguments") ? version.getAsJsonObject("arguments") : new JsonObject();
        List<String> jvm = arguments.has("jvm") ? VersionResolver.argumentValues(arguments.getAsJsonArray("jvm")) : List.of();
        if (environment != null) {
            List<String> existing = new ArrayList<>(result);
            existing.addAll(jvm);
            existing.addAll(instance.jvmArguments());
            result.addAll(JvmDefaults.generate(environment, existing));
        }
        if (jvm.isEmpty()) {
            result.addAll(List.of("-Djava.library.path=" + natives.toAbsolutePath(), "-cp", values.get("classpath")));
        } else {
            for (String argument : jvm) {
                result.add(substitute(argument, values));
            }
        }
        result.addAll(instance.jvmArguments());
        if (version.has("logging") && version.getAsJsonObject("logging").has("client")) {
            JsonObject logging = version.getAsJsonObject("logging").getAsJsonObject("client");
            Path path = JsonFiles.safeResolve(root.resolve("assets/log_configs"), logging.getAsJsonObject("file").get("id").getAsString());
            result.add(logging.get("argument").getAsString().replace("${path}", path.toAbsolutePath().toString()));
        }
        result.add(version.get("mainClass").getAsString());
        // Legacy Forge overrides minecraftArguments even if its vanilla parent had no structured
        // arguments.
        List<String> gameArguments = arguments.has("game") && !arguments.getAsJsonArray("game").isEmpty()
            ? VersionResolver.argumentValues(arguments.getAsJsonArray("game"))
            : splitLegacy(JsonFiles.string(version, "minecraftArguments", ""));
        if (gameArguments.isEmpty()) {
            throw new IOException("版本缺少游戏启动参数");
        }
        for (String argument : gameArguments) {
            result.add(substitute(argument, values));
        }
        result.addAll(instance.gameArguments());
        return result;
    }

    public record MemoryArguments(Integer minimum, Integer maximum, List<String> jvmArguments) {
    }

    public record Preview(JvmEnvironment environment, List<String> versionArguments) {
        public Preview {
            versionArguments = List.copyOf(versionArguments);
        }

        public List<String> generated(String editableArguments) throws IOException {
            if (environment == null) {
                return List.of();
            }
            List<String> occupied = new ArrayList<>(versionArguments);
            occupied.addAll(splitLegacy(editableArguments));
            return JvmDefaults.generate(environment, occupied);
        }
    }

    public static MemoryArguments parseMemory(String text) throws IOException {
        List<String> tokens = splitLegacy(text);
        List<String> rest = new ArrayList<>();
        Integer minimum = null, maximum = null;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("-Xms") || token.startsWith("-Xmx")) {
                boolean initialHeap = token.startsWith("-Xms");
                String prefix = initialHeap ? "-Xms" : "-Xmx";
                boolean separated = token.equals(prefix);
                Integer value = memoryValue(token, prefix, i, tokens);
                int minimumValue = initialHeap ? 128 : 512;
                if (value < minimumValue) {
                    throw new IOException(prefix + " 应在 " + minimumValue + "–65536 MB 之间");
                }
                if (separated) {
                    i++;
                }
                if (initialHeap) {
                    minimum = value;
                } else {
                    maximum = value;
                }
                continue;
            }
            if (LauncherConfig.get().defaults().jvmArguments().contains(token)) {
                continue;
            }
            rest.add(token);
        }
        return new MemoryArguments(minimum, maximum, rest);
    }

    public static String display(List<String> tokens) {
        return tokens.stream().map(LaunchArguments::quote).collect(java.util.stream.Collectors.joining(" "));
    }

    private static String quote(String token) {
        if (!token.isEmpty() && token.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'')) {
            return token;
        }
        StringBuilder result = new StringBuilder("\"");
        int slashes = 0;
        for (char c : token.toCharArray()) {
            if (c == '\\') {
                slashes++;
                continue;
            }
            result.append("\\".repeat(c == '"' ? slashes * 2 + 1 : slashes));
            result.append(c);
            slashes = 0;
        }
        return result.append("\\".repeat(slashes * 2)).append('"').toString();
    }

    public static List<String> defaultJvm(GameInstance instance) {
        List<String> result = new ArrayList<>();
        result.add("-Xms" + instance.minMemoryMb() + "M");
        result.add("-Xmx" + instance.memoryMb() + "M");
        result.addAll(LauncherConfig.get().defaults().jvmArguments());
        return List.copyOf(result);
    }

    private static Integer memoryValue(String token, String prefix, int index, List<String> tokens) throws IOException {
        Matcher joined = Pattern.compile(prefix + "([0-9]+)([mMgG])?").matcher(token);
        if (joined.matches()) {
            return parseMegabytes(joined.group(1), joined.group(2), token);
        }
        if (!token.equals(prefix)) {
            throw new IOException("无效的内存参数: " + token);
        }
        if (index + 1 >= tokens.size()) {
            throw new IOException(prefix + " 缺少内存数值");
        }
        String value = tokens.get(index + 1);
        Matcher separate = Pattern.compile("([0-9]+)([mMgG])?").matcher(value);
        if (!separate.matches()) {
            throw new IOException("无效的内存参数: " + value);
        }
        return parseMegabytes(separate.group(1), separate.group(2), value);
    }

    private static int parseMegabytes(String digits, String unit, String token) throws IOException {
        long value;
        try {
            value = Long.parseLong(digits);
        } catch (NumberFormatException ex) {
            throw new IOException("无效的内存参数: " + token);
        }
        if (unit != null && (unit.equals("g") || unit.equals("G"))) {
            if (value > 65536 / 1024) {
                throw new IOException("内存参数超出 65536 MB 上限: " + token);
            }
            value *= 1024;
        }
        if (value > 65536) {
            throw new IOException("内存参数超出 65536 MB 上限: " + token);
        }
        return (int) value;
    }

    public static String substitute(String input, Map<String, String> values) throws IOException {
        Matcher matcher = PLACEHOLDER.matcher(input);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new IOException("未支持的启动参数: " + matcher.group(1));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static List<String> splitLegacy(String input) throws IOException {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int index = 0; index < input.length(); index++) {
            char c = input.charAt(index);
            if (c == '\\') {
                int start = index;
                while (index < input.length() && input.charAt(index) == '\\') {
                    index++;
                }
                int count = index - start;
                char next = index < input.length() ? input.charAt(index) : 0;
                if ((next == '"' || next == '\'') && (quote == 0 || quote == next)) {
                    token.append("\\".repeat(count / 2));
                    if (count % 2 != 0) {
                        token.append(next);
                    } else {
                        quote = quote == 0 ? next : 0;
                    }
                } else {
                    token.append("\\".repeat(count));
                    index--;
                }
                started = true;
                continue;
            }
            if ((c == '\"' || c == '\'') && (quote == 0 || quote == c)) {
                quote = quote == 0 ? c : 0;
                started = true;
            } else if (Character.isWhitespace(c) && quote == 0) {
                if (started) {
                    result.add(token.toString());
                    token.setLength(0);
                    started = false;
                }
            } else {
                token.append(c);
                started = true;
            }
        }
        if (quote != 0) {
            throw new IOException("旧版启动参数存在未闭合引号");
        }
        if (started) {
            result.add(token.toString());
        }
        return result;
    }
}
