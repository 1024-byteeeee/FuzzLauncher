package com.fuzzlauncher;

import com.fuzzlauncher.infrastructure.BrowserFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class BrowserFilesTest {
    @TempDir
    Path temp;

    @Test
    void nestedCopyMoveRenameAndCollisionsPreserveOriginalData() throws Exception {
        Path source = Files.createDirectories(temp.resolve("source/世界/mods"));
        Files.writeString(source.resolve("mod.jar"), "original");
        Path world = source.getParent(), destination = Files.createDirectory(temp.resolve("destination"));
        Path copied = BrowserFiles.paste(world, destination, false, false);
        assertEquals("original", Files.readString(copied.resolve("mods/mod.jar")));
        assertTrue(Files.exists(world));
        assertThrows(FileAlreadyExistsException.class, () -> BrowserFiles.paste(world, destination, false, false));
        Path duplicate = BrowserFiles.paste(world, destination, false, true);
        assertNotEquals(copied, duplicate);
        assertEquals("original", Files.readString(duplicate.resolve("mods/mod.jar")));
        Path moved = BrowserFiles.paste(duplicate, world.getParent(), true, false);
        assertFalse(Files.exists(duplicate));
        assertEquals("original", Files.readString(moved.resolve("mods/mod.jar")));
        Path renamed = BrowserFiles.rename(moved, "重命名");
        assertFalse(Files.exists(moved));
        assertTrue(Files.exists(renamed.resolve("mods/mod.jar")));
        assertEquals(world, BrowserFiles.paste(world, world.getParent(), true, false));
        try (var entries = Files.list(destination)) {
            assertFalse(entries.anyMatch(p -> p.getFileName().toString().startsWith(".fuzz-file-")));
        }
    }

    @Test
    void invalidNamesAndSelfDescendantPasteNeverModifyFiles() throws Exception {
        Path folder = BrowserFiles.createFolder(temp, "文件夹");
        Path child = Files.createDirectory(folder.resolve("child"));
        Path data = Files.writeString(folder.resolve("keep.txt"), "keep");
        for (String name : new String[] { "", " ", "..", "../escape", "bad:name", "NUL", "CON.txt", "trailing.", "trailing " }) {
            assertThrows(IOException.class, () -> BrowserFiles.createFolder(temp, name), name);
        }
        assertThrows(IOException.class, () -> BrowserFiles.paste(folder, folder, false, true));
        assertThrows(IOException.class, () -> BrowserFiles.paste(folder, child, true, true));
        assertThrows(IOException.class, () -> BrowserFiles.rename(temp.getRoot(), "root"));
        assertThrows(FileAlreadyExistsException.class, () -> BrowserFiles.rename(data, "child"));
        assertEquals("keep", Files.readString(data));
        assertThrows(NoSuchFileException.class, () -> BrowserFiles.paste(temp.resolve("missing"), folder, true, false));
    }

    @Test
    void lockedFileFailureCleansPartialCopyAndPreservesSourceAndReadOnlyFlags() throws Exception {
        Path source = Files.createDirectory(temp.resolve("locked-source"));
        Path readOnly = Files.writeString(source.resolve("a-readonly.txt"), "read only");
        Files.setAttribute(readOnly, "dos:readonly", true);
        Path locked = Files.writeString(source.resolve("z-locked.txt"), "locked");
        Path destination = Files.createDirectory(temp.resolve("locked-destination"));
        var kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE;
        var handle = kernel.CreateFile(locked.toString(), com.sun.jna.platform.win32.WinNT.GENERIC_READ, 0, null,
            com.sun.jna.platform.win32.WinNT.OPEN_EXISTING, com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_NORMAL, null);
        assertNotEquals(com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE, handle);
        try {
            assertThrows(IOException.class, () -> BrowserFiles.paste(source, destination, false, false));
            assertTrue(Files.readAttributes(readOnly, java.nio.file.attribute.DosFileAttributes.class).isReadOnly());
            try (var entries = Files.list(destination)) {
                assertEquals(0, entries.count());
            }
        } finally {
            kernel.CloseHandle(handle);
            Files.setAttribute(readOnly, "dos:readonly", false);
        }
        assertEquals("locked", Files.readString(locked));
        assertEquals("read only", Files.readString(readOnly));
    }

    @Test
    void copyingIntoAnAliasOfDescendantIsRejected() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("source/child"));
        Path alias = temp.resolve("alias");
        var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", alias.toString(), folder.toString()).redirectErrorStream(true)
            .start();
        assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes()));
        try {
            assertThrows(IOException.class, () -> BrowserFiles.paste(folder.getParent(), alias, false, true));
            Path destination = Files.createDirectory(temp.resolve("target"));
            assertThrows(IOException.class, () -> BrowserFiles.paste(alias, destination, false, false));
            assertFalse(Files.exists(destination.resolve("alias")));
            try (var entries = Files.list(destination)) {
                assertEquals(0, entries.count());
            }
        } finally {
            Files.delete(alias);
        }
    }
}
