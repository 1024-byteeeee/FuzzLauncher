package com.fuzzlauncher.application;

import com.fuzzlauncher.domain.GameInstance;

import java.io.IOException;
import java.util.List;

public interface InstanceRepository {
    List<GameInstance> findAll() throws IOException;

    void save(GameInstance instance) throws IOException;

    void remove(String id) throws IOException;

    default void replaceAll(java.util.List<GameInstance> instances) throws IOException {
        for (GameInstance old : findAll()) {
            if (instances.stream().noneMatch(item -> item.id().equals(old.id()))) {
                remove(old.id());
            }
        }
        for (GameInstance item : instances) {
            save(item);
        }
    }
}
