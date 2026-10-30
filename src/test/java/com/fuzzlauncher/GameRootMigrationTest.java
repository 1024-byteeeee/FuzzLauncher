package com.fuzzlauncher;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class GameRootMigrationTest {
    @TempDir
    Path temp;
    private final TaskContext task = new TaskContext() {
        public void report(String message, double progress) {
        }

        public boolean cancelled() {
            return false;
        }
    };

    private Path source() throws IOException {
        Path root = Files.createDirectories(temp.resolve("old-game"));
        for (String file : List.of("versions/My Instance/saves/world/level.dat", "versions/My Instance/mods/mod.jar",
            "assets/objects/aa/hash", "libraries/example.jar", "saves/shared/level.dat", "options.txt")) {
            Path target = root.resolve(file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "contents " + file);
        }
        Files.createDirectories(root.resolve("empty-folder"));
        return root;
    }

    @Test
    void defaultRootUsesExecutableDirectoryAndPreservesExistingSettings() throws Exception {
        String previous = System.getProperty("jpackage.app-path");
        try {
            Path launcher = Files.createDirectories(temp.resolve("launcher"));
            System.setProperty("jpackage.app-path", launcher.resolve("FuzzLauncher.exe").toString());
            SettingsStore store = new SettingsStore(temp.resolve("config"));
            assertEquals(launcher.resolve("game").toString(), store.load().gameRoot());
            var existing = new LauncherSettings(temp.resolve("existing").toString(), IsolationMode.ALL);
            store.save(existing);
            assertEquals(existing, store.load());
        } finally {
            if (previous == null) {
                System.clearProperty("jpackage.app-path");
            } else {
                System.setProperty("jpackage.app-path", previous);
            }
        }
    }

    @Test
    void comparingMissingLocationsDoesNotRequireAnAvailableSourceDrive() throws Exception {
        Path target = Files.createDirectory(temp.resolve("available"));
        Path missing = temp.resolve("missing-root");
        assertFalse(GameRootMigration.sameDirectory(missing, target));
        assertTrue(GameRootMigration.sameDirectory(target.resolve("."), target));
        for (char drive = 'A'; drive <= 'Z'; drive++) {
            Path root = Path.of(drive + ":\\");
            if (!Files.exists(root)) {
                assertFalse(GameRootMigration.sameDirectory(root.resolve("old-game"), target));
                break;
            }
        }
    }

    @Test
    void copiesAllGameDataThenCleansSourceOnlyAfterCommit() throws Exception {
        Path source = source(), target = temp.resolve("new-game");
        try (var migration = GameRootMigration.prepare(source, target, List.of(), task)) {
            assertTrue(Files.isRegularFile(source.resolve("options.txt")));
            try (var paths = Files.walk(source)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    assertEquals(-1, Files.mismatch(file, target.resolve(source.relativize(file))));
                }
            }
            assertTrue(Files.isDirectory(target.resolve("empty-folder")));
            migration.commit();
            migration.cleanSource();
        }
        assertFalse(Files.exists(source));
        assertTrue(Files.isRegularFile(target.resolve("versions/My Instance/saves/world/level.dat")));
        assertNoWorkspace();
    }

    @Test
    void rollbackCancellationAndInvalidTargetsNeverDeleteOriginals() throws Exception {
        Path source = source(), target = Files.createDirectory(temp.resolve("empty-target"));
        try (var migration = GameRootMigration.prepare(source, target, List.of(), task)) {
            assertTrue(Files.exists(target.resolve("options.txt")));
        }
        assertTrue(Files.exists(source.resolve("options.txt")));
        try (var paths = Files.list(target)) {
            assertEquals(0, paths.count());
        }
        Files.writeString(target.resolve("keep.txt"), "keep");
        assertThrows(IOException.class, () -> GameRootMigration.prepare(source, target, List.of(), task));
        assertEquals("keep", Files.readString(target.resolve("keep.txt")));
        for (Path invalid : List.of(source, source.resolve("nested"), source.getParent())) {
            assertThrows(IOException.class, () -> GameRootMigration.prepare(source, invalid, List.of(), task));
        }
        assertThrows(IOException.class,
            () -> GameRootMigration.prepare(source, temp.resolve("protected"), List.of(source.resolve("config")), task));
        TaskContext cancelled = new TaskContext() {
            boolean stop;

            public void report(String message, double progress) {
                stop = true;
            }

            public boolean cancelled() {
                return stop;
            }
        };
        Path cancelledTarget = temp.resolve("cancelled-target");
        assertThrows(CancellationException.class, () -> GameRootMigration.prepare(source, cancelledTarget, List.of(), cancelled));
        assertFalse(Files.exists(cancelledTarget));
        assertTrue(Files.isRegularFile(source.resolve("options.txt")));
        assertNoWorkspace();
    }

    @Test
    void changedSourceIsNotDeletedDuringCleanup() throws Exception {
        Path source = source(), target = temp.resolve("changed-target");
        try (var migration = GameRootMigration.prepare(source, target, List.of(), task)) {
            migration.commit();
            Files.writeString(source.resolve("options.txt"), "changed after copy");
            assertThrows(IOException.class, migration::cleanSource);
            assertEquals("changed after copy", Files.readString(source.resolve("options.txt")));
            assertEquals("contents options.txt", Files.readString(target.resolve("options.txt")));
        }
    }

    @Test
    void sourceChangesDuringCopyFailBeforePublication() throws Exception {
        Path source = source(), target = temp.resolve("unstable-target");
        TaskContext mutating = new TaskContext() {
            public boolean cancelled() {
                return false;
            }

            public void report(String message, double progress) {
                if (message.equals("正在校验迁移文件")) {
                    try {
                        Files.writeString(source.resolve("new-file.txt"), "new");
                    } catch (IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                }
            }
        };
        assertThrows(IOException.class, () -> GameRootMigration.prepare(source, target, List.of(), mutating));
        assertFalse(Files.exists(target));
        assertTrue(Files.exists(source.resolve("new-file.txt")));
        assertNoWorkspace();
    }

    @Test
    void junctionsNeverCopyOrDeleteExternalData() throws Exception {
        Path source = source(), target = temp.resolve("junction-target");
        Path external = Files.createDirectory(temp.resolve("external"));
        Files.writeString(external.resolve("keep.txt"), "keep");
        Path junction = source.resolve("linked");
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), external.toString())
            .redirectErrorStream(true).start();
        assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes()));
        try {
            assertThrows(IOException.class, () -> GameRootMigration.prepare(source, target, List.of(), task));
            assertEquals("keep", Files.readString(external.resolve("keep.txt")));
            assertFalse(Files.exists(target));
            assertNoWorkspace();
        } finally {
            Files.delete(junction);
        }
    }

    private void assertNoWorkspace() throws IOException {
        try (var paths = Files.list(temp)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".fuzz-migration-")));
        }
    }

    @Test
    void lockedFilesRollBackWithoutChangingReadOnlySourceFiles() throws Exception {
        Path source = source(), target = temp.resolve("locked-target");
        Path readOnly = Files.writeString(source.resolve("a-readonly.txt"), "read only");
        Files.setAttribute(readOnly, "dos:readonly", true);
        Path locked = Files.writeString(source.resolve("z-locked.txt"), "locked");
        var kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE;
        var handle = kernel.CreateFile(locked.toString(), com.sun.jna.platform.win32.WinNT.GENERIC_READ, 0, null,
            com.sun.jna.platform.win32.WinNT.OPEN_EXISTING, com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_NORMAL, null);
        assertNotEquals(com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE, handle);
        try {
            assertThrows(IOException.class, () -> GameRootMigration.prepare(source, target, List.of(), task));
            assertFalse(Files.exists(target));
            assertTrue(Files.readAttributes(readOnly, java.nio.file.attribute.DosFileAttributes.class).isReadOnly());
            assertNoWorkspace();
        } finally {
            kernel.CloseHandle(handle);
            Files.setAttribute(readOnly, "dos:readonly", false);
        }
        assertEquals("locked", Files.readString(locked));
        assertEquals("read only", Files.readString(readOnly));
    }

    @Test
    void cleanupRejectsAReplacedParentDirectoryLink() throws Exception {
        Path source = source(), target = temp.resolve("parent-link-target");
        try (var migration = GameRootMigration.prepare(source, target, List.of(), task)) {
            Path originals = source.resolve("versions");
            Path backup = temp.resolve("versions-backup");
            Files.move(originals, backup);
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", originals.toString(), backup.toString())
                .redirectErrorStream(true).start();
            assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes()));
            migration.commit();
            try {
                assertThrows(IOException.class, migration::cleanSource);
                assertTrue(Files.exists(backup.resolve("My Instance/saves/world/level.dat")));
            } finally {
                Files.delete(originals);
            }
        }
    }
}
