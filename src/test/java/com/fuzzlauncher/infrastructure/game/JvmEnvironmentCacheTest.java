package com.fuzzlauncher.infrastructure.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JvmEnvironmentCacheTest {
    @TempDir
    Path temp;

    @Test
    void runtimeAndReleaseChangesInvalidateSuccessfulProbes() throws Exception {
        Path executable = temp.resolve("bin/java.exe");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "fixture");
        Path release = temp.resolve("release");
        Files.writeString(release, "JAVA_VERSION=21");
        AtomicInteger calls = new AtomicInteger();
        JvmEnvironment.Probe probe = path -> environment(calls.incrementAndGet());
        var first = JvmEnvironment.inspect(executable, probe);
        assertSame(first, JvmEnvironment.inspect(executable, probe));
        var timestamp = Files.getLastModifiedTime(release);
        Files.writeString(release, "JAVA_VERSION=25");
        Files.setLastModifiedTime(release, timestamp);
        assertNotSame(first, JvmEnvironment.inspect(executable, probe));
        Files.writeString(executable, "replacement executable");
        assertEquals(3, JvmEnvironment.inspect(executable, probe).major());
        Files.delete(release);
        assertEquals(4, JvmEnvironment.inspect(executable, probe).major());
        assertEquals(4, calls.get());
    }

    @Test
    void concurrentRequestsShareOneProbeAndFailuresAreNotCached() throws Exception {
        Path executable = temp.resolve("bin/java.exe");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "fixture");
        assertThrows(IOException.class, () -> JvmEnvironment.inspect(executable, path -> {
            throw new IOException("fixture failure");
        }));
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        JvmEnvironment.Probe probe = path -> {
            calls.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return environment(21);
        };
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> JvmEnvironment.inspect(executable, probe));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = workers.submit(() -> JvmEnvironment.inspect(executable, probe));
            release.countDown();
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void interruptedProbeDoesNotPopulateCache() throws Exception {
        Path executable = temp.resolve("bin/java.exe");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "fixture");
        assertThrows(InterruptedException.class, () -> JvmEnvironment.inspect(executable, path -> {
            throw new InterruptedException();
        }));
        assertEquals(25, JvmEnvironment.inspect(executable, path -> environment(25)).major());
    }

    private static JvmEnvironment environment(int major) {
        return new JvmEnvironment(major, true, true, 0, "UTF-8", Set.of());
    }
}
