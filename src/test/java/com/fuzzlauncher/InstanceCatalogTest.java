package com.fuzzlauncher;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class InstanceCatalogTest {
    @TempDir
    Path temp;
    private final TaskContext task = new TaskContext() {
        public void report(String message, double progress) {
        }

        public boolean cancelled() {
            return false;
        }
    };

    private GameInstance create(Path root, String id, String name) throws IOException {
        GameInstance item = new GameInstance(id, name, "base", LoaderType.VANILLA, null, IsolationOverride.ISOLATED, 5000,
            "C:/Java/bin/java.exe");
        JsonFiles.write(root.resolve("versions/base/base.json"),
            JsonParser.parseString("{\"id\":\"base\",\"mainClass\":\"Main\",\"libraries\":[]}"));
        Files.writeString(root.resolve("versions/base/base.jar"), "shared-client");
        JsonObject profile = new JsonObject();
        profile.addProperty("id", id);
        profile.addProperty("inheritsFrom", "base");
        JsonFiles.write(InstanceDirectories.profile(root, item), profile);
        InstanceCatalog.remember(root, item);
        Path folder = InstanceDirectories.directory(root, item);
        Files.createDirectories(folder.resolve("saves/world"));
        Files.writeString(folder.resolve("saves/world/level.dat"), "save");
        Files.createDirectories(folder.resolve("mods"));
        Files.writeString(folder.resolve("mods/example.jar"), "mod");
        Files.writeString(folder.resolve("options.txt"), "options");
        return item;
    }

    @Test
    void confirmedDeletionRemovesWholeOwnedInstanceAndLeavesOtherInstancesAndSharedCache() throws Exception {
        Path root = temp.resolve("game");
        var repo = new JsonInstanceRepository(temp.resolve("state"));
        GameInstance deleted = create(root, "first", "Delete me"), kept = create(root, "second", "Keep me");
        repo.save(deleted);
        repo.save(kept);
        InstanceCatalog.delete(root, deleted, repo, task);
        assertFalse(Files.exists(InstanceDirectories.directory(root, deleted)));
        assertEquals("save", Files.readString(InstanceDirectories.directory(root, kept).resolve("saves/world/level.dat")));
        assertEquals("shared-client", Files.readString(root.resolve("versions/base/base.jar")));
        assertEquals(List.of(kept), repo.findAll());
    }

    @Test
    void deletionOfBaseNamedInstanceKeepsOnlySharedBaseFiles() throws Exception {
        Path root = temp.resolve("game");
        var repo = new JsonInstanceRepository(temp.resolve("state"));
        GameInstance item = create(root, "base-instance", "base");
        repo.save(item);
        InstanceCatalog.delete(root, item, repo, task);
        assertTrue(Files.exists(root.resolve("versions/base/base.json")));
        assertTrue(Files.exists(root.resolve("versions/base/base.jar")));
        assertFalse(Files.exists(root.resolve("versions/base/saves")));
        assertFalse(Files.exists(InstanceDirectories.profile(root, item)));
        assertTrue(repo.findAll().isEmpty());
        assertTrue(InstanceCatalog.refresh(root, repo).isEmpty());
    }

    @Test
    void refreshDropsExternallyDeletedInstancesAndRecoversOwnedFoldersWithTheirSettings() throws Exception {
        Path root = temp.resolve("game");
        var repo = new JsonInstanceRepository(temp.resolve("state"));
        GameInstance item = create(root, "id", "Available");
        assertEquals(List.of(item), InstanceCatalog.refresh(root, repo));
        Files.delete(InstanceDirectories.profile(root, item));
        assertTrue(InstanceCatalog.refresh(root, repo).isEmpty());
        assertTrue(repo.findAll().isEmpty());
        assertTrue(Files.exists(InstanceDirectories.directory(root, item).resolve("saves/world/level.dat")),
            "Refresh must never delete remaining user files");
    }

    @Test
    void deletionRejectsWrongOwnershipBeforeRemovingAnything() throws Exception {
        Path root = temp.resolve("game");
        var repo = new JsonInstanceRepository(temp.resolve("state"));
        GameInstance item = create(root, "right", "Owned");
        repo.save(item);
        GameInstance wrong = new GameInstance("wrong", "Owned", "base", LoaderType.VANILLA, null, IsolationOverride.ISOLATED, 4096);
        assertThrows(IOException.class, () -> InstanceCatalog.delete(root, wrong, repo, task));
        assertTrue(Files.exists(InstanceDirectories.directory(root, item).resolve("saves/world/level.dat")));
        assertEquals(List.of(item), repo.findAll());
    }
}
