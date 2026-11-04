package com.fuzzlauncher.application.port;

import com.fuzzlauncher.application.model.ExportDefaults;
import com.fuzzlauncher.application.model.ModpackContent;
import com.fuzzlauncher.application.model.ModpackInfo;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.modpack.ModpackFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public interface ModpackOperations {
    boolean updatesAvailable();

    ModpackInfo inspect(Path archive) throws IOException;

    GameInstance importPack(Path root, Path archive, String name, boolean optional, Set<String> excluded, TaskContext task)
        throws Exception;

    GameInstance update(Path root, GameInstance instance, TaskContext task) throws Exception;

    void export(Path source, GameInstance instance, ModpackFormat format, String name, String version, String author, String description,
        String fileApi, Set<String> entries, Path destination, boolean replace, TaskContext task) throws Exception;

    List<ModpackContent> scanExport(Path source, GameInstance instance, TaskContext task) throws IOException;

    ExportDefaults exportDefaults(Path source) throws IOException;

    void validateFileApi(String url) throws IOException;

    void validatePath(String path) throws IOException;
}
