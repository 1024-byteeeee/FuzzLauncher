package com.fuzzlauncher.application.port;

import com.fuzzlauncher.application.model.FileOperationResult;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.mod.ModFile;

import java.nio.file.Path;
import java.util.List;

public interface ModRepository {
    List<ModFile> scan(Path directory, TaskContext task) throws Exception;

    FileOperationResult importFiles(Path directory, List<Path> sources, boolean rename, TaskContext task) throws Exception;

    FileOperationResult setEnabled(Path directory, List<ModFile> selected, boolean enabled, TaskContext task) throws Exception;

    FileOperationResult delete(Path directory, List<ModFile> selected, TaskContext task) throws Exception;
}
