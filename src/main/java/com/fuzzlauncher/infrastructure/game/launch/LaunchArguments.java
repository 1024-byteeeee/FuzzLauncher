package com.fuzzlauncher.infrastructure.game.launch;

import com.fuzzlauncher.application.model.LauncherInfo;
import com.fuzzlauncher.application.port.JvmArgumentPreview;
import com.fuzzlauncher.application.usecase.game.LaunchParameters;
import com.fuzzlauncher.domain.account.GameSession;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.infrastructure.config.LauncherConfig;
import com.fuzzlauncher.infrastructure.filesystem.BaseVersions;
import com.fuzzlauncher.infrastructure.game.catalog.VersionResolver;
import com.fuzzlauncher.infrastructure.game.runtime.JvmDefaults;
import com.fuzzlauncher.infrastructure.game.runtime.JvmEnvironment;
import com.fuzzlauncher.infrastructure.persistence.JsonFiles;

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
        values.put("launcher_version", LauncherInfo.VERSION);
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

    public record Preview(JvmEnvironment environment, List<String> versionArguments) implements JvmArgumentPreview {
        public Preview {
            versionArguments = List.copyOf(versionArguments);
        }

        public boolean available() {
            return environment != null;
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

    public record MemoryArguments(Integer minimum, Integer maximum, List<String> jvmArguments) {
    }

    public static MemoryArguments parseMemory(String text) throws IOException {
        var parsed = LaunchParameters.parseMemory(text, LauncherConfig.get().defaults().jvmArguments());
        return new MemoryArguments(parsed.minimum(), parsed.maximum(), parsed.jvmArguments());
    }

    public static String display(List<String> values) {
        return LaunchParameters.display(values);
    }

    public static List<String> defaultJvm(GameInstance instance) {
        return LaunchParameters.defaultJvm(instance, LauncherConfig.get().defaults().jvmArguments());
    }

    public static List<String> splitLegacy(String text) throws IOException {
        return LaunchParameters.splitLegacy(text);
    }

    public static String substitute(String text, Map<String, String> values) throws IOException {
        return LaunchParameters.substitute(text, values);
    }
}
