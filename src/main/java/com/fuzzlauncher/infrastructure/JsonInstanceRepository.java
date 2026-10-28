package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.application.InstanceRepository;
import com.fuzzlauncher.domain.GameInstance;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Serializes read-modify-write operations and publishes configuration atomically. */
public final class JsonInstanceRepository implements InstanceRepository {
  private final Path file;

  public JsonInstanceRepository(Path directory) {
    file = directory.resolve("instances.json");
  }

  @Override
  public synchronized List<GameInstance> findAll() throws IOException {
    if (!Files.exists(file)) return List.of();
    try {
      GameInstance[] instances =
          JsonFiles.GSON.fromJson(JsonFiles.read(file).get("instances"), GameInstance[].class);
      if (instances == null) throw new IOException("实例清单不完整");
      return List.of(instances);
    } catch (com.google.gson.JsonParseException ex) {
      throw new IOException("实例配置损坏，请恢复备份", ex);
    }
  }

  @Override
  public synchronized void save(GameInstance instance) throws IOException {
    List<GameInstance> items = new ArrayList<>(findAll());
    for (GameInstance item : items)
      if (!item.id().equals(instance.id()) && item.name().equalsIgnoreCase(instance.name()))
        throw new IOException("实例名称已存在，请使用不同名称：" + instance.name());
    int existing = -1;
    for (int i = 0; i < items.size(); i++)
      if (items.get(i).id().equals(instance.id())) {
        existing = i;
        break;
      }
    if (existing >= 0) items.set(existing, instance);
    else items.add(instance);
    JsonFiles.write(file, Map.of("instances", items));
  }

  @Override
  public synchronized void remove(String id) throws IOException {
    List<GameInstance> items = new ArrayList<>(findAll());
    items.removeIf(item -> item.id().equals(id));
    JsonFiles.write(file, Map.of("instances", items));
  }

  @Override
  public synchronized void replaceAll(List<GameInstance> instances) throws IOException {
    Set<String> names = new HashSet<>(), ids = new HashSet<>();
    for (GameInstance item : instances)
      if (!names.add(item.name().toLowerCase(Locale.ROOT)) || !ids.add(item.id()))
        throw new IOException("实例目录包含重复名称或标识：" + item.name());
    JsonFiles.write(file, Map.of("instances", instances));
  }
}
