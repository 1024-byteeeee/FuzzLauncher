package com.fuzzlauncher.application.model;

import com.fuzzlauncher.domain.instance.GameInstance;

import java.util.List;

public record SettingsSaved(List<GameInstance> instances, String warning) {
    public SettingsSaved {
        instances = List.copyOf(instances);
    }
}
