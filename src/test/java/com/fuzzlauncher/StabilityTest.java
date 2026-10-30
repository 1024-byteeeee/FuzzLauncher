package com.fuzzlauncher;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.LaunchArguments;

import com.google.gson.Gson;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class StabilityTest {
    @TempDir
    Path temp;

    private final TaskContext task = task(() -> false);

    private static TaskContext task(java.util.function.BooleanSupplier cancelled) {
        return new TaskContext() {
            public void report(String text, double value) {
            }

            public boolean cancelled() {
                return cancelled.getAsBoolean();
            }
        };
    }

    @Test
    void argumentsRoundTripQuotesWhitespaceEmptyTokensAndWindowsPaths() throws Exception {
        List<String> values = List.of("", "normal", "a b", "a\tb", "a\nb", "-Dname=John's", "-Dtext=\"a b\"", "C:\\Program Files\\Java\\",
            "\\\\server\\share\\a b\\", "a\\\"b", "a\\\\\"b", "中文路径\\文件", "'", "\"", "\\");
        assertEquals(values, LaunchArguments.splitLegacy(LaunchArguments.display(values)));
        assertEquals(List.of("--name", "a b", "C:\\Java\\bin"), LaunchArguments.splitLegacy("--name 'a b' C:\\Java\\bin"));
        assertThrows(IOException.class, () -> LaunchArguments.splitLegacy("\"unclosed"));
    }

    @Test
    void largeMemoryValuesCannotWrapBackIntoTheAllowedRange() throws Exception {
        for (String value : List.of("9007199254740993G", "9223372036854775807G", "999999999999999999999999G", "65G", "65537M")) {
            assertThrows(IOException.class, () -> LaunchArguments.parseMemory("-Xmx" + value), value);
        }
        assertEquals(65536, LaunchArguments.parseMemory("-Xmx64G").maximum());
        for (String value : List.of("-Xms0M", "-Xms127M", "-Xmx511M", "-Xmx0")) {
            assertThrows(IOException.class, () -> LaunchArguments.parseMemory(value));
        }
    }

    private LauncherSettings settings(String root) {
        return new LauncherSettings(temp.resolve(root).toString(), IsolationMode.ALL);
    }

    @Test
    void damagedSettingsRequireExplicitRecoveryAndRetainCorruptInput() throws Exception {
        SettingsStore store = new SettingsStore(temp);
        store.save(settings("first"));
        store.save(settings("second"));
        Path file = temp.resolve("settings.json");
        Files.writeString(file, "{broken");
        RecoverableJson.Damaged error = assertThrows(RecoverableJson.Damaged.class, store::load);
        assertTrue(error.hasValidBackup());
        assertEquals("{broken", Files.readString(file));
        error.restore();
        assertEquals(settings("first"), store.load());
        try (var files = Files.list(temp)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("settings.json.corrupt-")));
        }
        Files.delete(file);
        assertTrue(assertThrows(RecoverableJson.Damaged.class, store::load).hasValidBackup());
    }

    @Test
    void damagedInstanceListIsNotOverwrittenByAFollowingSave() throws Exception {
        var repository = new JsonInstanceRepository(temp);
        var first = new GameInstance("first", "First", "1.21.1", LoaderType.VANILLA, "", IsolationOverride.ISOLATED, 4096);
        repository.save(first);
        repository.save(new GameInstance("second", "Second", "1.21.1", LoaderType.VANILLA, "", IsolationOverride.ISOLATED, 4096));
        Path file = temp.resolve("instances.json");
        Files.writeString(file, "{\"instances\":null}");
        assertThrows(RecoverableJson.Damaged.class, () -> repository.save(first));
        assertEquals("{\"instances\":null}", Files.readString(file));
        var error = assertThrows(RecoverableJson.Damaged.class, repository::findAll);
        error.restore();
        assertEquals(List.of(first), repository.findAll());
    }

    @Test
    void journalRollsBackUncommittedFilesAndKeepsCommittedOnes() throws Exception {
        Path root = Files.createDirectory(temp.resolve("game"));
        Path records = temp.resolve("operations");
        Path file = root.resolve("profile.json");
        Files.writeString(file, "before");
        var store = new OperationJournal.Store(records, temp);
        var operation = store.begin("fixture", root);
        operation.file(file, "after".getBytes());
        Files.writeString(file, "after");
        assertThrows(IOException.class, () -> store.begin("another", root));
        OperationJournal.pending(records).getFirst().recover();
        assertEquals("before", Files.readString(file));
        assertTrue(OperationJournal.pending(records).isEmpty());
        operation = store.begin("committed", root);
        operation.file(file, "after".getBytes());
        Files.writeString(file, "after");
        operation.commit();
        OperationJournal.pending(records).getFirst().recover();
        assertEquals("after", Files.readString(file));
    }

    @Test
    void recoveryRefusesToDeleteUnexpectedUserFiles() throws Exception {
        Path source = Files.createDirectory(temp.resolve("staged"));
        Files.writeString(source.resolve("owned"), "contents");
        Path target = temp.resolve("published");
        Path records = temp.resolve("operations");
        var operation = new OperationJournal.Store(records, temp).begin("tree", temp);
        operation.tree(target, source, false);
        Files.move(source, target);
        Files.writeString(target.resolve("user-file"), "keep");
        assertThrows(IOException.class, () -> OperationJournal.pending(records).getFirst().recover());
        assertEquals("contents", Files.readString(target.resolve("owned")));
        assertEquals("keep", Files.readString(target.resolve("user-file")));
        Files.delete(target.resolve("user-file"));
        OperationJournal.pending(records).getFirst().recover();
        assertFalse(Files.exists(target));
    }

    @Test
    void rollbackOnAnInterruptedWorkerReleasesTheJournalAndRestoresTheInterruptFlag() throws Exception {
        Path file = temp.resolve("value"), records = temp.resolve("operations");
        Files.writeString(file, "before");
        var operation = new OperationJournal.Store(records, temp).begin("cancel fixture", temp);
        operation.file(file, "after".getBytes());
        Files.writeString(file, "after");
        try {
            Thread.currentThread().interrupt();
            operation.recover();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals("before", Files.readString(file));
        assertTrue(OperationJournal.pending(records).isEmpty());
    }

    @Test
    void committedMigrationRetainsTheNewDirectoryAndDoesNotDeleteTheOldSourceDuringRecovery() throws Exception {
        Path source = Files.createDirectory(temp.resolve("old")), target = temp.resolve("new"), records = temp.resolve("operations");
        Files.writeString(source.resolve("world"), "world data");
        var operation = new OperationJournal.Store(records, temp.resolve("data")).begin("migration", source, target);
        var migration = GameRootMigration.prepare(source, target, List.of(), task, operation);
        migration.commit();
        operation.commit();
        OperationJournal.pending(records).getFirst().recover();
        assertEquals("world data", Files.readString(source.resolve("world")));
        assertEquals("world data", Files.readString(target.resolve("world")));
    }

    @Test
    void malformedConfigurationIdentifiesTheFileAndField() throws Exception {
        SettingsStore settings = new SettingsStore(temp);
        Files.writeString(temp.resolve("settings.json"), "{\"gameRoot\":\"game\",\"isolation\":\"ALL\",\"downloadThreads\":1.5}");
        var error = assertThrows(RecoverableJson.Damaged.class, settings::load);
        assertTrue(error.getMessage().contains("settings.json"));
        assertTrue(error.getMessage().contains("downloadThreads"));
        assertFalse(error.hasValidBackup());
        Files.writeString(temp.resolve("settings.json"), "{\"gameRoot\":\"game\",\"isolation\":\"unknown\"}");
        assertTrue(assertThrows(RecoverableJson.Damaged.class, settings::load).getMessage().contains("isolation"));
    }

    @Test
    void journalRecognizesTheProgramsOwnRollbackOfALegacyConfiguration() throws Exception {
        Path data = Files.createDirectory(temp.resolve("legacy-data")), records = temp.resolve("operations");
        Path file = data.resolve("settings.json");
        String legacy = "{\"gameRoot\":\"game\",\"isolation\":\"ALL\"}";
        Files.writeString(file, legacy);
        SettingsStore store = new SettingsStore(data);
        LauncherSettings before = store.load(), after = settings("updated-game");
        var operation = new OperationJournal.Store(records, data).begin("legacy rollback", data);
        operation.configuration(file, after, before);
        store.save(after);
        store.save(before); // Includes newly introduced default fields, unlike the original raw JSON.
        OperationJournal.pending(records).getFirst().recover();
        assertEquals(legacy, Files.readString(file));
        assertEquals(before, store.load());
    }

    @Test
    void missingLocalSettingsStillSelectTheirBackupInsteadOfAnotherLegacyDirectory() throws Exception {
        Path launcher = Files.createDirectory(temp.resolve("launcher")), bootstrap = temp.resolve("legacy");
        new SettingsStore(bootstrap).save(settings("legacy-game"));
        Path local = launcher.resolve("data");
        new SettingsStore(local).save(settings("local-game"));
        Files.delete(local.resolve("settings.json"));
        assertEquals(local.toAbsolutePath().normalize(), DataLocations.resolve(bootstrap, launcher));
    }

    @Test
    void migrationRecoveryRestoresConfigurationAndPreservesTheSource() throws Exception {
        Path source = Files.createDirectory(temp.resolve("old"));
        Files.writeString(source.resolve("world"), "world data");
        Path target = temp.resolve("new");
        Path data = Files.createDirectory(temp.resolve("data"));
        SettingsStore settings = new SettingsStore(data);
        LauncherSettings before = new LauncherSettings(source.toString(), IsolationMode.ALL);
        LauncherSettings after = new LauncherSettings(target.toString(), IsolationMode.ALL);
        settings.save(before);
        Path records = temp.resolve("operations");
        var operation = new OperationJournal.Store(records, data).begin("migration", source, target);
        // Deliberately do not close the in-memory migration: model a process disappearing after publication.
        GameRootMigration.prepare(source, target, List.of(data), task, operation);
        operation.configuration(data.resolve("settings.json"), after);
        settings.save(after);
        OperationJournal.pending(records).getFirst().recover();
        assertEquals(before, settings.load());
        assertEquals("world data", Files.readString(source.resolve("world")));
        assertFalse(Files.exists(target));
    }

    @Test
    void cancelledCopyRemovesItsPartialTarget() throws Exception {
        Path source = temp.resolve("large.bin"), target = temp.resolve("copy.bin");
        Files.write(source, new byte[1024 * 1024]);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> CancellableFiles.copy(source, target, task(() -> checks.incrementAndGet() > 3)));
        assertFalse(Files.exists(target));
        assertEquals(1024 * 1024, Files.size(source));
    }

    @Test
    void cancellingADownloadWaitingForAPermitDoesNotLeakThePermit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        server.createContext("/file", exchange -> {
            entered.countDown();
            try {
                release.await();
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[] { 1, 2 });
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            HttpTransport http = new HttpTransport();
            http.setDownloadConcurrency(1);
            Future<?> first = workers.submit(() -> {
                http.download(url, temp.resolve("one"), "", task);
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicBoolean cancelled = new AtomicBoolean();
            Future<?> waiting = workers.submit(() -> {
                http.download(url, temp.resolve("two"), "", task(cancelled::get));
                return null;
            });
            cancelled.set(true);
            assertInstanceOf(CancellationException.class,
                assertThrows(ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS)).getCause());
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            http.download(url, temp.resolve("three"), "", task);
            assertTrue(Files.exists(temp.resolve("three")));
            assertFalse(Files.exists(temp.resolve("two")));
        } finally {
            release.countDown();
            workers.shutdownNow();
            server.stop(0);
        }
    }

    public static class CrashProbe {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            var operation = new OperationJournal.Store(root.resolve("operations"), root).begin("crash fixture", root);
            operation.file(root.resolve("value"), "after".getBytes());
            Files.writeString(root.resolve("value"), "after");
            Files.writeString(root.resolve("ready"), "ready");
            Thread.sleep(60000);
        }
    }

    @Test
    void recoverySurvivesForcedTerminationOfASeparateProcess() throws Exception {
        Files.writeString(temp.resolve("value"), "before");
        String classpath = String.join(File.pathSeparator,
            java.util.stream.Stream.of(CrashProbe.class, OperationJournal.class, Gson.class, Native.class, Kernel32.class).map(type -> {
                try {
                    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                } catch (java.net.URISyntaxException ex) {
                    throw new IllegalStateException(ex);
                }
            }).distinct().toList());
        Path log = temp.resolve("probe.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), "-cp", classpath,
            CrashProbe.class.getName(), temp.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(temp.resolve("ready")) && child.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(temp.resolve("ready")), () -> {
                try {
                    return Files.readString(log);
                } catch (IOException ex) {
                    return ex.toString();
                }
            });
            ProcessCleanup.terminate(child);
            OperationJournal.pending(temp.resolve("operations")).getFirst().recover();
            assertEquals("before", Files.readString(temp.resolve("value")));
        } finally {
            ProcessCleanup.terminate(child);
        }
    }
}
