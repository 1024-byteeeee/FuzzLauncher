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
        if (!Files.exists(file) && !Files.exists(RecoverableJson.backup(file))) {
            return List.of();
        }
        return RecoverableJson.read(file, JsonInstanceRepository::decode);
    }

    @Override
    public synchronized void save(GameInstance instance) throws IOException {
        List<GameInstance> items = new ArrayList<>(findAll());
        for (GameInstance item : items) {
            if (!item.id().equals(instance.id()) && item.name().equalsIgnoreCase(instance.name())) {
                throw new IOException("实例名称已存在，请使用不同名称：" + instance.name());
            }
        }
        int existing = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(instance.id())) {
                existing = i;
                break;
            }
        }
        if (existing >= 0) {
            items.set(existing, instance);
        } else {
            items.add(instance);
        }
        RecoverableJson.write(file, Map.of("instances", items), JsonInstanceRepository::decode);
    }

    @Override
    public synchronized void remove(String id) throws IOException {
        List<GameInstance> items = new ArrayList<>(findAll());
        items.removeIf(item -> item.id().equals(id));
        RecoverableJson.write(file, Map.of("instances", items), JsonInstanceRepository::decode);
    }

    @Override
    public synchronized void replaceAll(List<GameInstance> instances) throws IOException {
        Set<String> names = new HashSet<>(), ids = new HashSet<>();
        for (GameInstance item : instances) {
            if (!names.add(item.name().toLowerCase(Locale.ROOT)) || !ids.add(item.id())) {
                throw new IOException("实例目录包含重复名称或标识：" + item.name());
            }
        }
        RecoverableJson.write(file, Map.of("instances", instances), JsonInstanceRepository::decode);
    }

    private static List<GameInstance> decode(com.google.gson.JsonObject json) {
        if (json.has("instances") && json.get("instances").isJsonArray()) {
            int index = 0;
            for (var value : json.getAsJsonArray("instances")) {
                if (!value.isJsonObject()) {
                    throw new IllegalArgumentException("instances[" + index + "] 不是实例对象");
                }
                var item = value.getAsJsonObject();
                String path = "instances[" + index++ + "].";
                RecoverableJson.integer(item, "memoryMb", path);
                RecoverableJson.integer(item, "minMemoryMb", path);
                RecoverableJson.enumField(item, "loader", com.fuzzlauncher.domain.LoaderType.class, path);
                RecoverableJson.enumField(item, "isolation", com.fuzzlauncher.domain.IsolationOverride.class, path);
            }
        }
        GameInstance[] instances = JsonFiles.GSON.fromJson(json.get("instances"), GameInstance[].class);
        if (instances == null) {
            throw new IllegalArgumentException("instances 字段缺失或无效");
        }
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (GameInstance instance : instances) {
            if (instance == null || !ids.add(instance.id()) || !names.add(instance.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("instances 中存在空条目或重复名称、标识");
            }
        }
        return List.of(instances);
    }
}
