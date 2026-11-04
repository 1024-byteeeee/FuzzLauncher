package com.fuzzlauncher.infrastructure.modpack;

import com.fuzzlauncher.application.model.ExportDefaults;
import com.fuzzlauncher.application.model.ModpackContent;
import com.fuzzlauncher.application.model.ModpackInfo;
import com.fuzzlauncher.application.port.ModpackOperations;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.modpack.ModpackFormat;
import com.fuzzlauncher.infrastructure.persistence.JsonFiles;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class LocalModpackOperations implements ModpackOperations {
    private final LocalModpacks service;

    public LocalModpackOperations(LocalModpacks service) {
        this.service = service;
    }

    public boolean updatesAvailable() {
        return service != null;
    }

    public ModpackInfo inspect(Path archive) throws IOException {
        return describe(ModpackArchive.inspect(archive));
    }

    public static ModpackInfo describe(ModpackArchive.Info info) {
        List<ModpackContent> optional = List.of();
        String problem = "";
        try {
            optional = ModpackArchive.optionalFiles(info).stream().map(e -> new ModpackContent(e.path(), e.directory(), e.size())).toList();
        } catch (IOException | RuntimeException ex) {
            problem = "无法读取可选文件：" + ex.getMessage();
        }
        return new ModpackInfo(info.format(), info.name(), info.version(), info.author(), info.description(), info.game(), info.loader(),
            info.loaderVersion(), optional, problem);
    }

    public GameInstance importPack(Path root, Path archive, String name, boolean optional, Set<String> excluded, TaskContext task)
        throws Exception {
        if (service == null) {
            throw new IOException("整合包服务尚未初始化");
        }
        return service.importPack(root, archive, name, optional, excluded, task);
    }

    public GameInstance update(Path root, GameInstance instance, TaskContext task) throws Exception {
        return service == null ? instance : service.update(root, instance, task);
    }

    public void export(Path source, GameInstance instance, ModpackFormat format, String name, String version, String author,
        String description, String fileApi, Set<String> entries, Path destination, boolean replace, TaskContext task) throws Exception {
        ModpackArchive.export(source, instance, format, name, version, author, description, fileApi, entries, destination, replace, task);
    }

    public List<ModpackContent> scanExport(Path source, GameInstance instance, TaskContext task) throws IOException {
        return ModpackArchive.scanExport(source, instance, task).stream().map(e -> new ModpackContent(e.path(), e.directory(), e.size()))
            .toList();
    }

    public ExportDefaults exportDefaults(Path source) throws IOException {
        Path file = source.resolve(ModpackArchive.STATE);
        if (!Files.isRegularFile(file)) {
            return new ExportDefaults("1.0.0", "", "", "");
        }
        var manifest = JsonFiles.read(file).getAsJsonObject("manifest");
        return new ExportDefaults(JsonFiles.string(manifest, "versionId", JsonFiles.string(manifest, "version", "1.0.0")),
            JsonFiles.string(manifest, "author", ""), JsonFiles.string(manifest, "summary", JsonFiles.string(manifest, "description", "")),
            JsonFiles.string(manifest, "fileApi", ""));
    }

    public void validateFileApi(String url) throws IOException {
        ModpackArchive.baseUrl(url);
    }

    public void validatePath(String path) throws IOException {
        ModpackArchive.validatePath(path);
    }
}
