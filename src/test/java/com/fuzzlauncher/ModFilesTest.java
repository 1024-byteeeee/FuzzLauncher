package com.fuzzlauncher;

import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.application.usecase.mod.ManageContent;
import com.fuzzlauncher.domain.game.LoaderType;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.instance.IsolationMode;
import com.fuzzlauncher.domain.instance.IsolationOverride;
import com.fuzzlauncher.domain.content.ContentFile;
import com.fuzzlauncher.domain.content.ContentKind;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.infrastructure.config.SettingsDefaults;
import com.fuzzlauncher.infrastructure.mod.ContentFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.DosFileAttributes;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import static com.fuzzlauncher.ModFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ModFilesTest {
    @TempDir
    Path temp;
    private final ContentFiles files = new ContentFiles();

    @Test
    void schematicDirectoryUsesLitematicaOverrideAndListsOnlySupportedFilesAndFolders() throws Exception {
        var repository = new ContentFiles(ContentKind.SCHEMATICS);
        var service = new ManageContent(ContentKind.SCHEMATICS, repository);
        var instance = new GameInstance("fixture", "隔离实例", "1.21.1", LoaderType.FABRIC, "1",
            IsolationOverride.ISOLATED, 4096);
        var settings = SettingsDefaults.create(temp.toString(), IsolationMode.ALL);
        Path gameDirectory = temp.resolve("versions/隔离实例");
        Path fallback = gameDirectory.resolve("schematics");
        assertEquals(fallback, service.directory(settings, instance));
        Path config = gameDirectory.resolve("config/litematica.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "invalid JSON");
        assertEquals(fallback, service.directory(settings, instance));
        Files.writeString(config, """
            {"Generic":{"customSchematicBaseDirectoryEnabled":true,"customSchematicBaseDirectory":"投影/蓝图"}}
            """);
        Path custom = gameDirectory.resolve("投影/蓝图");
        assertEquals(custom, service.directory(settings, instance));
        Files.createDirectories(custom);
        repository.createFolder(custom, "建筑", TASK);
        Path source = Files.writeString(temp.resolve("房子.litematic"), "opaque fixture");
        assertEquals(1, service.importFiles(custom, List.of(source), false, TASK).completed());
        Files.writeString(custom.resolve("ignore.txt"), "not a schematic");
        var entries = service.scan(custom, TASK);
        assertEquals(Set.of("建筑", "房子.litematic"), entries.stream().map(ContentFile::name).collect(Collectors.toSet()));
        assertTrue(entries.stream().anyMatch(ContentFile::directory));
        assertEquals("opaque fixture", Files.readString(custom.resolve("房子.litematic")));
        Files.writeString(config, """
            {"Generic":{"customSchematicBaseDirectoryEnabled":true,"customSchematicBaseDirectory":%s}}
            """.formatted(new com.google.gson.Gson().toJson(temp.resolve("外部投影").toString())));
        assertEquals(temp.resolve("外部投影"), service.directory(settings, instance));
    }

    @Test
    void resourcePackArchivesAndFoldersShareSafeImportAndToggleOperations() throws Exception {
        var packs = new ContentFiles(ContentKind.RESOURCE_PACKS);
        Path directory = temp.resolve("resourcepacks");
        String metadata = """
            {"pack":{"pack_format":34,"description":{"text":"复古","extra":[{"text":"材质"}]}}}
            """;
        Path archive = jar(temp.resolve("source/像素.zip"), Map.of("pack.mcmeta", metadata));
        Path folder = Files.createDirectories(temp.resolve("source/草地方块"));
        Files.writeString(folder.resolve("pack.mcmeta"), metadata);
        Files.createDirectories(folder.resolve("assets/minecraft"));
        Files.writeString(folder.resolve("assets/minecraft/fixture.txt"), "keep");
        Path invalid = jar(temp.resolve("source/invalid.zip"), Map.of("pack.mcmeta", "{\"pack\":5}"));
        var imported = packs.importFiles(directory, List.of(archive, folder, invalid), false, TASK);
        assertEquals(2, imported.completed());
        assertEquals(1, imported.failures().size());
        assertFalse(Files.exists(directory.resolve("invalid.zip")));
        var entries = packs.scan(directory, TASK);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(entry -> entry.enabled() && entry.version().equals("格式 34")
            && entry.metadata().getFirst().description().equals("复古材质")));
        assertEquals(2, packs.setEnabled(directory, entries, false, TASK).completed());
        assertTrue(Files.exists(directory.resolve("像素.zip.disabled")));
        assertTrue(Files.exists(directory.resolve("草地方块/pack.mcmeta.disabled")));
        assertFalse(Files.exists(directory.resolve("草地方块/pack.mcmeta")));
        var disabled = packs.scan(directory, TASK);
        assertEquals(2, disabled.size());
        assertTrue(disabled.stream().noneMatch(ContentFile::enabled));
        assertEquals(2, packs.setEnabled(directory, disabled, true, TASK).completed());
        assertEquals("keep", Files.readString(directory.resolve("草地方块/assets/minecraft/fixture.txt")));
        assertEquals(metadata, Files.readString(directory.resolve("草地方块/pack.mcmeta")));
        assertEquals(0, packs.importFiles(directory, List.of(archive, folder), false, TASK).completed());
        assertEquals(2, packs.importFiles(directory, List.of(archive, folder), true, TASK).completed());
        assertTrue(Files.isRegularFile(directory.resolve("像素 (2).zip")));
        assertTrue(Files.isRegularFile(directory.resolve("草地方块 (2)/pack.mcmeta")));
    }

    @Test
    void enabledDisabledBrokenAndUnknownFilesAreListedAndCanBeToggled() throws Exception {
        Path mods = temp.resolve("mods");
        Path enabled = fabric(mods.resolve("a.jar"), "enabled");
        fabric(mods.resolve("b.jar.disabled"), "disabled");
        Files.writeString(mods.resolve("broken.jar"), "broken");
        jar(mods.resolve("unknown.zip"), Map.of("fixture.txt", "fixture"));
        Files.writeString(mods.resolve("ignore.txt"), "ignore");
        List<ContentFile> scanned = files.scan(mods, TASK);
        assertEquals(4, scanned.size());
        assertEquals(1, scanned.stream().filter(mod -> !mod.enabled()).count());
        var selected = scanned.stream().filter(mod -> mod.path().equals(enabled)).findFirst().orElseThrow();
        assertEquals(1, files.setEnabled(mods, List.of(selected), false, TASK).completed());
        assertFalse(Files.exists(enabled));
        assertTrue(Files.exists(mods.resolve("a.jar.disabled")));
        selected = files.scan(mods, TASK).stream().filter(mod -> mod.name().equals("enabled")).findFirst().orElseThrow();
        assertEquals(1, files.setEnabled(mods, List.of(selected), true, TASK).completed());
        assertTrue(Files.exists(enabled));
    }

    @Test
    void enablingNeverOverwritesAnExistingGeneration() throws Exception {
        Path mods = temp.resolve("mods");
        Path original = fabric(mods.resolve("mod.jar"), "original");
        fabric(mods.resolve("mod.jar.disabled"), "disabled");
        byte[] bytes = Files.readAllBytes(original);
        var selected = files.scan(mods, TASK).stream().filter(mod -> !mod.enabled()).findFirst().orElseThrow();
        var report = files.setEnabled(mods, List.of(selected), true, TASK);
        assertEquals(0, report.completed());
        assertEquals(1, report.failures().size());
        assertArrayEquals(bytes, Files.readAllBytes(original));
        assertTrue(Files.exists(selected.path()));
    }

    @Test
    void importingPreservesSourcesSkipsConflictsAndCanKeepBoth() throws Exception {
        Path mods = temp.resolve("mods"), source = fabric(temp.resolve("incoming/mod.jar"), "incoming");
        Path existing = fabric(mods.resolve("mod.jar.disabled"), "existing");
        byte[] original = Files.readAllBytes(existing);
        assertEquals(0, files.importFiles(mods, List.of(source), false, TASK).completed());
        assertArrayEquals(original, Files.readAllBytes(existing));
        assertEquals(1, files.importFiles(mods, List.of(source), true, TASK).completed());
        assertTrue(Files.isRegularFile(mods.resolve("mod (2).jar")));
        assertTrue(Files.exists(source));
        assertEquals(2, files.scan(mods, TASK).size());
        assertNoTemporaryDirectories(mods);
    }

    @Test
    void corruptImportIsReportedWhileValidFilesStillImport() throws Exception {
        Path valid = fabric(temp.resolve("incoming/valid.jar"), "valid");
        Path invalid = Files.writeString(temp.resolve("incoming/bad.jar"), "not a jar");
        Path mods = temp.resolve("mods");
        var result = files.importFiles(mods, List.of(invalid, valid), false, TASK);
        assertEquals(1, result.completed());
        assertEquals(1, result.failures().size());
        assertFalse(Files.exists(mods.resolve("bad.jar")));
        assertTrue(Files.exists(mods.resolve("valid.jar")));
        assertTrue(Files.exists(invalid));
        assertNoTemporaryDirectories(mods);
    }

    @Test
    void cancellationBeforePublicationLeavesNoImportedOrTemporaryFiles() throws Exception {
        Path source = fabric(temp.resolve("incoming/mod.jar"), "incoming"), mods = temp.resolve("mods");
        TaskContext cancelledAtCommit = new TaskContext() {
            public void report(String message, double progress) {
            }

            public boolean cancelled() {
                return false;
            }

            public void commit(Commit action) {
                throw new CancellationException("fixture cancellation");
            }
        };
        assertThrows(CancellationException.class, () -> files.importFiles(mods, List.of(source), false, cancelledAtCommit));
        assertTrue(Files.exists(source));
        try (var remaining = Files.list(mods)) {
            assertEquals(0, remaining.count());
        }
    }

    @Test
    void staleSelectionsAndFilesFromAnotherDirectoryCannotBeChanged() throws Exception {
        Path mods = temp.resolve("mods"), other = temp.resolve("other");
        Path file = fabric(mods.resolve("mod.jar"), "first");
        var stale = files.scan(mods, TASK).getFirst();
        fabric(file, "replacement-with-a-different-size");
        var report = files.setEnabled(mods, List.of(stale), false, TASK);
        assertEquals(0, report.completed());
        assertTrue(report.failures().getFirst().contains("变化"));
        assertEquals("replacement-with-a-different-size", files.scan(mods, TASK).getFirst().metadata().getFirst().id());
        fabric(other.resolve("outside.jar"), "outside");
        var outside = files.scan(other, TASK).getFirst();
        assertEquals(0, files.setEnabled(mods, List.of(outside), false, TASK).completed());
        assertEquals(0, files.delete(mods, List.of(outside), TASK).completed());
        assertTrue(Files.exists(outside.path()));
    }

    @Test
    void sameNameSourcesAreReservedWithinTheBatchAndCannotOverwriteEachOther() throws Exception {
        Path first = fabric(temp.resolve("one/mod.jar"), "first"), second = fabric(temp.resolve("two/mod.jar"), "second");
        Path mods = temp.resolve("mods");
        assertEquals(2, files.importFiles(mods, List.of(first, second), true, TASK).completed());
        assertEquals(Set.of("first", "second"), files.scan(mods, TASK).stream().map(mod -> mod.metadata().getFirst().id()).collect(Collectors.toSet()));
        assertNoTemporaryDirectories(mods);
    }

    @Test
    void deletionRecyclesWholeArchivesAndReportsIndividualFailures() throws Exception {
        Path mods = temp.resolve("mods"), trash = Files.createDirectory(temp.resolve("trash"));
        Path first = fabric(mods.resolve("first.jar"), "first");
        Path second = fabric(mods.resolve("second.jar.disabled"), "second");
        ContentFiles service = new ContentFiles(path -> {
            if (path.getFileName().toString().equals("second.jar.disabled")) {
                throw new IOException("fixture recycle failure");
            }
            Files.move(path, trash.resolve(path.getFileName()));
        });
        var report = service.delete(mods, service.scan(mods, TASK), TASK);
        assertEquals(1, report.completed());
        assertEquals(1, report.failures().size());
        assertFalse(Files.exists(first));
        assertTrue(Files.isRegularFile(trash.resolve("first.jar")));
        assertTrue(Files.isRegularFile(second));
    }

    @Test
    void corruptReadOnlySourceDoesNotLeaveAReadOnlyStagingFile() throws Exception {
        Path source = temp.resolve("source.jar"), mods = temp.resolve("mods");
        Files.writeString(source, "not a jar");
        Files.setAttribute(source, "dos:readonly", true);
        try {
            var report = files.importFiles(mods, List.of(source), false, TASK);
            assertEquals(0, report.completed());
            assertEquals(1, report.failures().size());
            assertTrue(Files.readAttributes(source, DosFileAttributes.class).isReadOnly());
            assertNoTemporaryDirectories(mods);
        } finally {
            Files.setAttribute(source, "dos:readonly", false);
        }
    }

    @Test
    void modDirectoryUsesTheLaunchIsolationPolicy() {
        Path root = temp.resolve("game");
        LauncherSettings settings = SettingsDefaults.create(root.toString(), IsolationMode.ALL);
        var isolated = new GameInstance("isolated", "My Instance", "1.21", LoaderType.FABRIC, "0.16.0", IsolationOverride.INHERIT, 4096);
        var shared = new GameInstance("shared", "Shared", "1.21", LoaderType.FABRIC, "0.16.0", IsolationOverride.SHARED, 4096);
        ManageContent service = new ManageContent(new ContentFiles());
        assertEquals(root.resolve("versions/My Instance/mods").toAbsolutePath(), service.directory(settings, isolated));
        assertEquals(root.resolve("mods").toAbsolutePath(), service.directory(settings, shared));
        assertFalse(ContentFiles.supported(root.getRoot()));
        assertFalse(ContentFiles.supported(null));
    }

    private static void assertNoTemporaryDirectories(Path directory) throws Exception {
        try (var entries = Files.list(directory)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".fuzz-mod-import-")));
        }
    }
}
