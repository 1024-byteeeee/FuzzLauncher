package com.fuzzlauncher;

import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.infrastructure.filesystem.StagedFiles;
import com.fuzzlauncher.infrastructure.network.HttpTransport;
import com.fuzzlauncher.infrastructure.recovery.OperationJournal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class StagedFilesTest {
    @TempDir
    Path temp;

    private final TaskContext task = new TaskContext() {
        public void report(String message, double progress) {
        }

        public boolean cancelled() {
            return false;
        }
    };

    @Test
    void streamedHashesFollowOverrideLayersAndRejectBadManifests() throws Exception {
        Path file = temp.resolve("file");
        StagedFiles staged = new StagedFiles();
        byte[] original = "original".getBytes(StandardCharsets.UTF_8), override = "override".getBytes(StandardCharsets.UTF_8);
        assertEquals(original.length, staged.write(new ByteArrayInputStream(original), file, original.length, task));
        String previous = staged.digest(file, "SHA-1", task);
        FileTime modified = Files.getLastModifiedTime(file);
        staged.write(new ByteArrayInputStream(override), file, override.length, task);
        Files.setLastModifiedTime(file, modified);
        assertThrows(IOException.class, () -> staged.verify(file, previous, "SHA-1", task));
        for (String algorithm : new String[] { "SHA-1", "SHA-256", "SHA-512" }) {
            staged.verify(file, HttpTransport.digest(file, algorithm), algorithm, task);
        }
    }

    @Test
    void modifiedFilesAreRehashedBeforeRecoveryRecordsArePublished() throws Exception {
        Path root = Files.createDirectories(temp.resolve(".fuzz-staging/install-fixture/profile"));
        Path file = root.resolve("file"), target = temp.resolve("published");
        StagedFiles staged = new StagedFiles();
        staged.write(new ByteArrayInputStream(new byte[] { 1 }), file, 1, task);
        Files.write(file, new byte[] { 2, 3 });
        staged.persist(root, task);
        var operation = new OperationJournal.Store(temp.resolve("operations"), temp).begin("generation fixture", temp);
        operation.tree(target, root, false, task, staged);
        Files.move(root, target);
        operation.recover();
        assertFalse(Files.exists(target));
    }

    @Test
    void sameSizeChangesAndExplicitInvalidationCannotReuseAnOldGeneration() throws Exception {
        Path file = temp.resolve("file");
        StagedFiles staged = new StagedFiles();
        staged.write(new ByteArrayInputStream(new byte[] { 1 }), file, 1, task);
        FileTime original = Files.getLastModifiedTime(file);
        Files.write(file, new byte[] { 2 });
        Files.setLastModifiedTime(file, FileTime.fromMillis(original.toMillis() + 2000));
        assertEquals(HttpTransport.digest(file, "SHA-256"), staged.digest(file, "SHA-256", task));
        FileTime same = Files.getLastModifiedTime(file);
        Files.write(file, new byte[] { 3 });
        Files.setLastModifiedTime(file, same);
        staged.invalidate(file);
        assertEquals(HttpTransport.digest(file, "SHA-256"), staged.digest(file, "SHA-256", task));
    }

    @Test
    void cachedHashesDoNotHideUserChangesDuringRollback() throws Exception {
        Path root = Files.createDirectories(temp.resolve("staged"));
        Path file = root.resolve("file"), target = temp.resolve("published");
        StagedFiles staged = new StagedFiles();
        staged.write(new ByteArrayInputStream(new byte[] { 1 }), file, 1, task);
        staged.persist(root, task);
        var operation = new OperationJournal.Store(temp.resolve("operations"), temp).begin("rollback fixture", temp);
        operation.tree(target, root, false, task, staged);
        Files.move(root, target);
        Files.writeString(target.resolve("file"), "user changes");
        assertThrows(IOException.class, operation::recover);
        assertEquals("user changes", Files.readString(target.resolve("file")));
    }

    @Test
    void expansionLimitAndCancellationCannotLeaveAnOldCachedHash() throws Exception {
        Path file = temp.resolve("file");
        StagedFiles staged = new StagedFiles();
        staged.write(new ByteArrayInputStream(new byte[] { 1 }), file, 1, task);
        assertThrows(IOException.class, () -> staged.write(new ByteArrayInputStream(new byte[] { 2, 3 }), file, 1, task));
        assertEquals(HttpTransport.digest(file, "SHA-256"), staged.digest(file, "SHA-256", task));
        TaskContext cancelled = new TaskContext() {
            public void report(String message, double progress) {
            }

            public boolean cancelled() {
                return true;
            }
        };
        assertThrows(CancellationException.class, () -> staged.persist(temp, cancelled));
        assertThrows(CancellationException.class, () -> staged.digest(file, "SHA-256", cancelled));
    }
}
