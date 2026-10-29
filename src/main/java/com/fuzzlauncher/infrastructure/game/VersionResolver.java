package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.domain.GameInstance;
import com.fuzzlauncher.infrastructure.BaseVersions;
import com.fuzzlauncher.infrastructure.InstanceDirectories;
import com.fuzzlauncher.infrastructure.JsonFiles;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class VersionResolver {
  private final BaseVersions baseVersions;

  public VersionResolver() {
    this(new BaseVersions());
  }

  public VersionResolver(BaseVersions baseVersions) {
    this.baseVersions = baseVersions;
  }

  public BaseVersions baseVersions() {
    return baseVersions;
  }

  public JsonObject resolve(Path root, String id) throws IOException {
    return resolve(root, id, new HashSet<>());
  }

  public JsonObject resolveProfile(Path root, JsonObject profile) throws IOException {
    return resolveChild(root, profile.deepCopy(), profile.get("id").getAsString(), new HashSet<>());
  }

  public JsonObject resolveInstance(Path root, GameInstance instance) throws IOException {
    InstanceDirectories.migrate(root, instance);
    Path profile = InstanceDirectories.profile(root, instance);
    if (!Files.isRegularFile(profile)) return resolve(root, instance.id());
    JsonObject child = JsonFiles.read(profile);
    if (!instance.id().equals(JsonFiles.string(child, "id", "")))
      throw new IOException("实例目录属于其他实例，不能启动：" + instance.name());
    JsonObject resolved = resolveChild(root, child, instance.id(), new HashSet<>());
    if (!child.has("inheritsFrom")) {
      String client = resolved.get("_clientVersion").getAsString();
      Path jar = JsonFiles.safeResolve(profile.getParent(), client + ".jar");
      if (!child.has("jar") || Files.isRegularFile(jar))
        resolved.addProperty(
            "_clientJarPath", root.toAbsolutePath().normalize().relativize(jar).toString());
    }
    return resolved;
  }

  private JsonObject resolve(Path root, String id, Set<String> visited) throws IOException {
    if (!visited.add(id)) throw new IOException("版本继承出现循环: " + id);
    baseVersions.migrate(root, id);
    Path path = baseVersions.metadata(root, id);
    JsonObject child = JsonFiles.read(path);
    return resolveChild(root, child, id, visited);
  }

  private JsonObject resolveChild(Path root, JsonObject child, String id, Set<String> visited)
      throws IOException {
    if (!child.has("inheritsFrom")) {
      child.addProperty("_clientVersion", JsonFiles.string(child, "jar", id));
      return child;
    }
    JsonObject parent = resolve(root, child.get("inheritsFrom").getAsString(), visited);
    JsonObject merged = parent.deepCopy();
    for (var field : child.entrySet()) {
      if (!field.getKey().equals("libraries") && !field.getKey().equals("arguments"))
        merged.add(field.getKey(), field.getValue().deepCopy());
    }
    Map<String, JsonElement> libraries = new LinkedHashMap<>();
    for (JsonObject source : List.of(parent, child)) {
      if (source.has("libraries"))
        for (JsonElement element : source.getAsJsonArray("libraries")) {
          String name = element.getAsJsonObject().get("name").getAsString();
          String[] p = name.split(":");
          String key = p[0] + ":" + p[1] + (p.length > 3 ? ":" + p[3] : "");
          libraries.put(key, element.deepCopy());
        }
    }
    JsonArray array = new JsonArray();
    libraries.values().forEach(array::add);
    merged.add("libraries", array);
    JsonObject arguments = new JsonObject();
    for (String kind : List.of("game", "jvm")) {
      JsonArray values = new JsonArray();
      for (JsonObject source : List.of(parent, child))
        if (source.has("arguments") && source.getAsJsonObject("arguments").has(kind))
          source
              .getAsJsonObject("arguments")
              .getAsJsonArray(kind)
              .forEach(v -> values.add(v.deepCopy()));
      arguments.add(kind, values);
    }
    merged.add("arguments", arguments);
    if (child.has("jar")) merged.addProperty("_clientVersion", child.get("jar").getAsString());
    return merged;
  }

  public static boolean allowed(JsonObject item) {
    if (!item.has("rules")) return true;
    boolean allowed = false;
    for (JsonElement element : item.getAsJsonArray("rules")) {
      JsonObject rule = element.getAsJsonObject();
      boolean matches = true;
      if (rule.has("os")) {
        JsonObject os = rule.getAsJsonObject("os");
        if (os.has("name")) matches &= os.get("name").getAsString().equals("windows");
        if (os.has("arch"))
          matches &= System.getProperty("os.arch").matches(os.get("arch").getAsString());
        if (os.has("version"))
          matches &= System.getProperty("os.version").matches(os.get("version").getAsString());
      }
      if (rule.has("features"))
        for (var feature : rule.getAsJsonObject("features").entrySet())
          matches &= !feature.getValue().getAsBoolean();
      if (matches) allowed = JsonFiles.string(rule, "action", "disallow").equals("allow");
    }
    return allowed;
  }

  public static String mavenPath(String coordinate) {
    String[] extension = coordinate.split("@", 2);
    String[] p = extension[0].split(":");
    if (p.length < 3) throw new IllegalArgumentException("无效 Maven 坐标: " + coordinate);
    return p[0].replace('.', '/')
        + "/"
        + p[1]
        + "/"
        + p[2]
        + "/"
        + p[1]
        + "-"
        + p[2]
        + (p.length > 3 ? "-" + p[3] : "")
        + "."
        + (extension.length == 2 ? extension[1] : "jar");
  }

  public static List<String> argumentValues(JsonArray array) {
    List<String> result = new ArrayList<>();
    for (JsonElement element : array) {
      if (element.isJsonPrimitive()) result.add(element.getAsString());
      else if (allowed(element.getAsJsonObject())) {
        JsonElement value = element.getAsJsonObject().get("value");
        if (value.isJsonArray()) value.getAsJsonArray().forEach(v -> result.add(v.getAsString()));
        else result.add(value.getAsString());
      }
    }
    return result;
  }
}
