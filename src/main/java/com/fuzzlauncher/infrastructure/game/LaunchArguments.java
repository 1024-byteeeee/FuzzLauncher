package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.JsonFiles;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

public final class LaunchArguments {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

  public List<String> build(
      Path root,
      Path gameDir,
      Path natives,
      GameInstance instance,
      GameSession session,
      JsonObject version,
      List<Path> libraries)
      throws IOException {
    String client = version.get("_clientVersion").getAsString();
    List<Path> classpath = new ArrayList<>(libraries);
    classpath.add(root.resolve("versions").resolve(client).resolve(client + ".jar"));
    Map<String, String> values = new HashMap<>();
    values.put("natives_directory", natives.toAbsolutePath().toString());
    values.put("launcher_name", "FuzzLauncher");
    values.put("launcher_version", "0.2.0");
    values.put(
        "classpath",
        String.join(
            java.io.File.pathSeparator,
            classpath.stream().map(p -> p.toAbsolutePath().toString()).toList()));
    values.put("classpath_separator", java.io.File.pathSeparator);
    values.put("library_directory", root.resolve("libraries").toAbsolutePath().toString());
    values.put("auth_player_name", session.account().name());
    values.put("auth_uuid", session.account().uuid());
    values.put("auth_access_token", session.accessToken());
    values.put(
        "auth_session",
        session.account().isOffline()
            ? "0"
            : "token:" + session.accessToken() + ":" + session.account().uuid());
    values.put("version_name", instance.id());
    values.put("game_directory", gameDir.toAbsolutePath().toString());
    values.put("assets_root", root.resolve("assets").toAbsolutePath().toString());
    values.put(
        "assets_index_name",
        version.has("assetIndex")
            ? version.getAsJsonObject("assetIndex").get("id").getAsString()
            : "");
    values.put(
        "game_assets",
        root.resolve("assets/virtual")
            .resolve(values.get("assets_index_name"))
            .toAbsolutePath()
            .toString());
    values.put("user_type", session.account().isOffline() ? "legacy" : "msa");
    values.put("user_properties", "{}");
    values.put("version_type", JsonFiles.string(version, "type", "release"));
    values.put("clientid", "");
    values.put("auth_xuid", "");
    List<String> result =
        new ArrayList<>(
            List.of("-Xms256M", "-Xmx" + instance.memoryMb() + "M", "-Dfile.encoding=UTF-8"));
    JsonObject arguments =
        version.has("arguments") ? version.getAsJsonObject("arguments") : new JsonObject();
    List<String> jvm =
        arguments.has("jvm")
            ? VersionResolver.argumentValues(arguments.getAsJsonArray("jvm"))
            : List.of();
    if (jvm.isEmpty())
      result.addAll(
          List.of(
              "-Djava.library.path=" + natives.toAbsolutePath(), "-cp", values.get("classpath")));
    else for (String argument : jvm) result.add(substitute(argument, values));
    if (version.has("logging") && version.getAsJsonObject("logging").has("client")) {
      JsonObject logging = version.getAsJsonObject("logging").getAsJsonObject("client");
      Path path =
          JsonFiles.safeResolve(
              root.resolve("assets/log_configs"),
              logging.getAsJsonObject("file").get("id").getAsString());
      result.add(
          logging
              .get("argument")
              .getAsString()
              .replace("${path}", path.toAbsolutePath().toString()));
    }
    result.add(version.get("mainClass").getAsString());
    // Legacy Forge overrides minecraftArguments even if its vanilla parent had no structured
    // arguments.
    List<String> gameArguments =
        arguments.has("game") && !arguments.getAsJsonArray("game").isEmpty()
            ? VersionResolver.argumentValues(arguments.getAsJsonArray("game"))
            : splitLegacy(JsonFiles.string(version, "minecraftArguments", ""));
    if (gameArguments.isEmpty()) throw new IOException("版本缺少游戏启动参数");
    for (String argument : gameArguments) result.add(substitute(argument, values));
    return result;
  }

  public static String substitute(String input, Map<String, String> values) throws IOException {
    Matcher matcher = PLACEHOLDER.matcher(input);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String value = values.get(matcher.group(1));
      if (value == null) throw new IOException("未支持的启动参数: " + matcher.group(1));
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
    for (char c : input.toCharArray()) {
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
    if (quote != 0) throw new IOException("旧版启动参数存在未闭合引号");
    if (started) result.add(token.toString());
    return result;
  }
}
