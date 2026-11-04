package com.fuzzlauncher;

import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.infrastructure.platform.windows.WindowsPaths;
import com.fuzzlauncher.infrastructure.recovery.OperationJournal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LongPathRecoveryTest {
    @TempDir
    Path temp;

    @Test
    void nativePathsHandleDriveUncAndAlreadyExtendedForms() {
        assertEquals("\\\\?\\C:\\游戏\\file.jar", WindowsPaths.extended(Path.of("C:\\folder\\..\\游戏\\file.jar")));
        assertEquals("\\\\?\\UNC\\server\\share\\游戏\\file.jar",
            WindowsPaths.extended(Path.of("\\\\server\\share\\游戏\\file.jar")));
        assertEquals("\\\\?\\C:\\游戏\\file.jar", WindowsPaths.extended(Path.of("\\\\?\\C:\\游戏\\file.jar")));
    }

    private Path stagedFile(Path staging) throws Exception {
        Path directory = staging.resolve("profile/saves/world/datapacks/中文数据包/data/minecraft/advancements/recipes/building_blocks");
        while (directory.toAbsolutePath().toString().length() < 280) {
            directory = directory.resolve("nested-data-pack-directory");
        }
        Files.createDirectories(directory);
        return Files.writeString(directory.resolve("waxed_exposed_chiseled_copper_from_waxed_exposed_copper_stonecutting.json"), "{}");
    }

    @Test
    void longDataPackPathsCanBeJournaledAndRolledBack() throws Exception {
        Path staging = temp.resolve(".fuzz-staging/install-fixture");
        Path file = stagedFile(staging);
        Path profile = staging.resolve("profile"), target = temp.resolve("versions/imported");
        Files.createDirectories(target.getParent());
        var operation = new OperationJournal.Store(temp.resolve("operations"), temp).begin("long pack fixture", temp);
        operation.workspace(staging);
        operation.tree(target, profile, false);
        Files.move(profile, target);
        assertTrue(Files.isRegularFile(target.resolve(profile.relativize(file))));
        operation.recover();
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(staging));
        assertTrue(OperationJournal.pending(temp.resolve("operations")).isEmpty());
    }

    @Test
    void committedLongDataPackPathsAreKeptDuringWorkspaceCleanup() throws Exception {
        Path staging = temp.resolve(".fuzz-staging/install-fixture");
        Path file = stagedFile(staging);
        Path profile = staging.resolve("profile"), target = temp.resolve("versions/imported");
        Files.createDirectories(target.getParent());
        var operation = new OperationJournal.Store(temp.resolve("operations"), temp).begin("long pack fixture", temp);
        operation.workspace(staging);
        operation.tree(target, profile, false);
        Files.move(profile, target);
        operation.commit();
        operation.recover();
        assertEquals("{}", Files.readString(target.resolve(profile.relativize(file))));
        assertFalse(Files.exists(staging));
        assertTrue(OperationJournal.pending(temp.resolve("operations")).isEmpty());
    }

    @Test
    void cancellationDuringManifestGenerationDoesNotAuthorizePublication() throws Exception {
        Path staging = temp.resolve(".fuzz-staging/install-fixture");
        stagedFile(staging);
        Path target = temp.resolve("versions/imported");
        var operation = new OperationJournal.Store(temp.resolve("operations"), temp).begin("cancel pack fixture", temp);
        operation.workspace(staging);
        AtomicBoolean cancelled = new AtomicBoolean();
        TaskContext task = new TaskContext() {
            public void report(String message, double progress) {
                cancelled.set(true);
            }

            public boolean cancelled() {
                return cancelled.get();
            }
        };
        assertThrows(CancellationException.class, () -> operation.tree(target, staging.resolve("profile"), false, task));
        operation.recover();
        assertFalse(Files.exists(staging));
        assertFalse(Files.exists(target));
        assertTrue(OperationJournal.pending(temp.resolve("operations")).isEmpty());
    }

    @Test
    void extendedPathHandlingStillRejectsJunctionsAndKeepsExternalFiles() throws Exception {
        Path staging = Files.createDirectories(temp.resolve(".fuzz-staging/install-fixture/profile"));
        Path external = Files.createDirectories(temp.resolve("external"));
        Path sentinel = Files.writeString(external.resolve("keep.txt"), "keep");
        Path junction = staging.resolve("linked");
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), external.toString())
            .redirectErrorStream(true).start();
        assertEquals(0, process.waitFor());
        var operation = new OperationJournal.Store(temp.resolve("operations"), temp).begin("link pack fixture", temp);
        try {
            assertThrows(IOException.class, () -> operation.tree(temp.resolve("versions/imported"), staging, false));
            assertEquals("keep", Files.readString(sentinel));
        } finally {
            Files.delete(junction);
            operation.recover();
        }
    }
}
