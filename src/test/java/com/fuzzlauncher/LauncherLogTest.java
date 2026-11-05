package com.fuzzlauncher;

import com.fuzzlauncher.infrastructure.platform.LauncherLog;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LauncherLogTest {
    @TempDir
    Path data;

    @Test
    void clearsRotatedAndCurrentLogsReportsLockedFilesAndResumesWriting() throws Exception {
        LauncherLog.configure(data);
        Path logs = data.resolve("logs");
        Path rotated = logs.resolve("launcher.log.2");
        Path locked = logs.resolve("game.log");
        Path other = logs.resolve("keep.txt");
        try {
            Files.writeString(rotated, "old rotation");
            Files.writeString(locked, "locked");
            Files.writeString(other, "keep");
            LauncherLog.LOGGER.info("old session marker");
            var kernel = Kernel32.INSTANCE;
            var handle = kernel.CreateFile(locked.toString(), WinNT.GENERIC_READ, 0, null, WinNT.OPEN_EXISTING, WinNT.FILE_ATTRIBUTE_NORMAL, null);
            assertNotEquals(WinBase.INVALID_HANDLE_VALUE, handle);
            try {
                var result = LauncherLog.clear(data, ModFixtures.TASK);
                assertEquals(2, result.completed());
                assertEquals(1, result.failures().size());
                assertTrue(result.failures().getFirst().startsWith("game.log"));
            } finally {
                kernel.CloseHandle(handle);
            }
            assertEquals("locked", Files.readString(locked));
            assertEquals("keep", Files.readString(other));
            assertFalse(Files.exists(rotated));
            LauncherLog.LOGGER.info("new session marker");
            String current = Files.readString(logs.resolve("launcher.log.0"));
            assertTrue(current.contains("new session marker"));
            assertFalse(current.contains("old session marker"));
        } finally {
            LauncherLog.close();
        }
    }
}
