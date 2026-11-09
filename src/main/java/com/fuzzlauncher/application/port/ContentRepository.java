package com.fuzzlauncher.application.port;

import com.fuzzlauncher.application.model.FileOperationResult;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.content.ContentFile;
import com.fuzzlauncher.domain.content.ContentKind;

import java.nio.file.Path;
import java.util.List;

public interface ContentRepository {
    default Path directory(Path gameDirectory, ContentKind kind) {
        return gameDirectory.resolve(kind.directory());
    }

    boolean canImport(Path source);

    List<ContentFile> scan(Path directory, TaskContext task) throws Exception;

    FileOperationResult importFiles(Path directory, List<Path> sources, boolean rename, TaskContext task) throws Exception;

    FileOperationResult setEnabled(Path directory, List<ContentFile> selected, boolean enabled, TaskContext task) throws Exception;

    FileOperationResult delete(Path directory, List<ContentFile> selected, TaskContext task) throws Exception;

    void createFolder(Path directory, String name, TaskContext task) throws Exception;
}
