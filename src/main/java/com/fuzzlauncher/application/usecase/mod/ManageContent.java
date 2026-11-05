package com.fuzzlauncher.application.usecase.mod;

import com.fuzzlauncher.application.model.FileOperationResult;
import com.fuzzlauncher.application.port.ContentRepository;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.instance.IsolationPolicy;
import com.fuzzlauncher.domain.content.ContentFile;
import com.fuzzlauncher.domain.content.ContentKind;
import com.fuzzlauncher.domain.settings.LauncherSettings;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Resolves the same game directory as launch before delegating local content operations. */
public final class ManageContent {
    private final ContentRepository files;
    private final ContentKind kind;

    public ManageContent(ContentRepository files) {
        this(ContentKind.MODS, files);
    }

    public ManageContent(ContentKind kind, ContentRepository files) {
        this.kind = Objects.requireNonNull(kind);
        this.files = Objects.requireNonNull(files);
    }

    public ContentKind kind() {
        return kind;
    }

    public Path directory(LauncherSettings settings, GameInstance instance) {
        Path gameDirectory = new IsolationPolicy().gameDirectory(Path.of(settings.gameRoot()), instance, settings.isolation());
        return files.directory(gameDirectory, kind).toAbsolutePath().normalize();
    }

    public List<ContentFile> scan(Path directory, TaskContext task) throws Exception {
        return files.scan(directory, task);
    }

    public FileOperationResult importFiles(Path directory, List<Path> sources, boolean renameCollisions, TaskContext task) throws Exception {
        return files.importFiles(directory, sources, renameCollisions, task);
    }

    public FileOperationResult setEnabled(Path directory, List<ContentFile> selected, boolean enabled, TaskContext task) throws Exception {
        return files.setEnabled(directory, selected, enabled, task);
    }

    public FileOperationResult delete(Path directory, List<ContentFile> selected, TaskContext task) throws Exception {
        return files.delete(directory, selected, task);
    }

    public void createFolder(Path directory, String name, TaskContext task) throws Exception {
        files.createFolder(directory, name, task);
    }
}
