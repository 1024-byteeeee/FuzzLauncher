package com.fuzzlauncher.application.model;

import com.fuzzlauncher.domain.game.LoaderType;
import com.fuzzlauncher.domain.modpack.ModpackFormat;

import java.util.List;

public record ModpackInfo(ModpackFormat format, String name, String version, String author, String description, String game,
    LoaderType loader, String loaderVersion, List<ModpackContent> optionalFiles, String problem) {
    public ModpackInfo {
        optionalFiles = List.copyOf(optionalFiles);
    }
}
