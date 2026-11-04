package com.fuzzlauncher.application.port;

import com.fuzzlauncher.domain.game.GameVersion;
import com.fuzzlauncher.domain.game.LoaderType;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

public interface VersionCatalogPort {
    List<GameVersion> games() throws IOException, InterruptedException;

    List<GameVersion> games(boolean force, Consumer<List<GameVersion>> cached) throws IOException, InterruptedException;

    List<String> loaders(LoaderType type, String game) throws Exception;

    List<String> loaders(LoaderType type, String game, boolean force, Consumer<List<String>> cached) throws Exception;
}
