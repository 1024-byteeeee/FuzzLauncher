package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.infrastructure.JsonFiles;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class VersionResolver {
  public JsonObject resolve(Path root, String id) throws IOException {
    return resolve(root, id, new HashSet<>());
  }

  private JsonObject resolve(Path root, String id, Set<String> visited) throws IOException {
    if (!visited.add(id)) throw new IOException("版本继承出现循环: " + id);
    Path path = JsonFiles.safeResolve(root.resolve("versions"), id + "/" + id + ".json");
    JsonObject child = JsonFiles.read(path);
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
