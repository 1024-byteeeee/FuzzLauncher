package com.fuzzlauncher;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.*;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class ModpacksTest {
    @TempDir
    Path temp;
    private HttpServer server;
    private String base;
    private final Map<String, byte[]> responses = new java.util.concurrent.ConcurrentHashMap<>();
    private final TaskContext task = new TaskContext() {
        public void report(String message, double value) {
        }

        public boolean cancelled() {
            return false;
        }
    };

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = responses.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GameVersion game(String id) {
        responses.put("/" + id + ".jar", "client fixture".getBytes(StandardCharsets.UTF_8));
        String metadata = "{\"id\":\"" + id + "\",\"mainClass\":\"FixtureMain\",\"libraries\":[],\"downloads\":{\"client\":{\"url\":\""
            + base + "/" + id + ".jar\"}}}";
        responses.put("/" + id + ".json", metadata.getBytes(StandardCharsets.UTF_8));
        return new GameVersion(id, "release", base + "/" + id + ".json", "");
    }

    private InstallGame installer(InstanceRepository repo, boolean broken) {
        LoaderInstaller loader = (root, staging, game, version, context) -> JsonParser
            .parseString(broken ? "{\"inheritsFrom\":\"base\",\"libraries\":[{\"name\":\"missing:generated:1\",\"url\":\"\"}]}"
                : "{\"inheritsFrom\":\"" + game.id() + "\"}")
            .getAsJsonObject();
        return new InstallGame(repo, new GameFiles(new HttpTransport()), new VersionResolver(), Map.of(LoaderType.FABRIC, loader));
    }

    @Test
    void allFourFormatsImportAsIsolatedRegisteredPlayableProfiles() throws Exception {
        GameVersion game = game("base");
        Path source = Files.createDirectories(temp.resolve("source/mods")).getParent();
        Files.writeString(source.resolve("mods/test.jar"), "fixture mod");
        Path root = temp.resolve("game");
        Files.createDirectories(root.resolve("mods"));
        Files.writeString(root.resolve("mods/user.jar"), "shared mods");
        var repo = new JsonInstanceRepository(temp.resolve("data"));
        var service = new Modpacks(new HttpTransport(), () -> List.of(game), installer(repo, false), repo);
        GameInstance original = new GameInstance("export", "pack", "base", LoaderType.FABRIC, "0.16.0", IsolationOverride.INHERIT, 4096);
        for (ModpackFormat format : ModpackFormat.values()) {
            Path zip = temp.resolve(format.name() + format.extension());
            ModpackArchive.export(source, original, format, "pack", "1", "author", "description", base, Set.of("mods"), zip, false, task);
            GameInstance imported = service.importPack(root, zip, "import-" + format, true, task);
            assertEquals(IsolationOverride.ISOLATED, imported.isolation());
            assertEquals(LoaderType.FABRIC, imported.loader());
            Path folder = InstanceDirectories.directory(root, imported);
            assertEquals("fixture mod", Files.readString(folder.resolve("mods/test.jar")));
            assertTrue(Files.isRegularFile(folder.resolve(InstanceDirectories.PROFILE)));
            assertEquals("FixtureMain", new VersionResolver().resolveInstance(root, imported).get("mainClass").getAsString());
        }
        assertEquals(4, repo.findAll().size());
        assertEquals("shared mods", Files.readString(root.resolve("mods/user.jar")));
    }

    @Test
    void failedImportCleansPublishedPackWithoutTouchingUserFiles() throws Exception {
        GameVersion game = game("base");
        Path source = Files.createDirectories(temp.resolve("source/config")).getParent();
        Files.writeString(source.resolve("config/settings.txt"), "fixture");
        GameInstance original = new GameInstance("export", "pack", "base", LoaderType.FABRIC, "0.16.0", IsolationOverride.ISOLATED, 4096);
        Path zip = temp.resolve("broken.zip");
        ModpackArchive.export(source, original, ModpackFormat.MCBBS, "pack", "1", "", "", "", Set.of("config"), zip, false, task);
        var repo = new JsonInstanceRepository(temp.resolve("data"));
        var service = new Modpacks(new HttpTransport(), () -> List.of(game), installer(repo, true), repo);
        Path root = temp.resolve("game");
        Files.createDirectories(root);
        Files.writeString(root.resolve("user.txt"), "keep");
        assertThrows(IOException.class, () -> service.importPack(root, zip, "broken", true, task));
        assertTrue(repo.findAll().isEmpty());
        assertFalse(Files.exists(root.resolve("versions/broken")));
        assertEquals("keep", Files.readString(root.resolve("user.txt")));
    }

    private JsonObject manifest(String version, String game, Map<String, String> files) throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("name", "server pack");
        result.addProperty("version", version);
        result.addProperty("fileApi", base);
        JsonArray addons = new JsonArray();
        JsonObject component = new JsonObject();
        component.addProperty("id", "game");
        component.addProperty("version", game);
        addons.add(component);
        result.add("addons", addons);
        JsonArray entries = new JsonArray();
        for (var file : files.entrySet()) {
            Path hashFile = Files.createTempFile(temp, "hash-", ".tmp");
            Files.writeString(hashFile, file.getValue());
            JsonObject entry = new JsonObject();
            entry.addProperty("path", file.getKey());
            entry.addProperty("hash", ModpackArchive.digest(hashFile, "SHA-1"));
            entries.add(entry);
            Files.delete(hashFile);
            responses.put("/overrides/" + file.getKey(), file.getValue().getBytes(StandardCharsets.UTF_8));
        }
        result.add("files", entries);
        return result;
    }

    private Path installState(Path root, GameInstance instance, JsonObject manifest, Map<String, String> files) throws Exception {
        Path folder = Files.createDirectories(InstanceDirectories.directory(root, instance));
        for (var entry : files.entrySet()) {
            Path file = folder.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        JsonFiles.write(folder.resolve(ModpackArchive.STATE), Map.of("format", "SERVER", "manifest", manifest));
        JsonObject profile = new JsonObject();
        profile.addProperty("id", instance.id());
        profile.addProperty("inheritsFrom", instance.gameVersion());
        profile.add("fuzzInstance", JsonFiles.GSON.toJsonTree(instance));
        JsonFiles.write(folder.resolve(InstanceDirectories.PROFILE), profile);
        return folder;
    }

    @Test
    void serverUpdateAddsReplacesRemovesAndPreservesLocalEdits() throws Exception {
        GameInstance instance = new GameInstance("server", "server", "base", LoaderType.VANILLA, null, IsolationOverride.ISOLATED, 4096);
        var repo = new JsonInstanceRepository(temp.resolve("data"));
        repo.save(instance);
        Map<String, String> original = Map.of("mods/update.jar", "old", "mods/remove.jar", "remove", "mods/disabled.jar",
            "disabled original", "config/local.txt", "default", "config/removed-local.txt", "default");
        JsonObject old = manifest("1", "base", original);
        Path root = temp.resolve("game"), folder = installState(root, instance, old, original);
        Files.writeString(folder.resolve("config/local.txt"), "user edited");
        Files.writeString(folder.resolve("config/removed-local.txt"), "user kept");
        Files.writeString(folder.resolve("mods/collision.jar"), "untracked local mod");
        Files.move(folder.resolve("mods/disabled.jar"), folder.resolve("mods/disabled.jar.disabled"));
        JsonObject remote = manifest("2", "base", Map.of("mods/update.jar", "new", "mods/added.jar", "added", "mods/disabled.jar",
            "server changed disabled mod", "config/local.txt", "server changed", "mods/collision.jar", "server mod"));
        responses.put("/server-manifest.json", remote.toString().getBytes(StandardCharsets.UTF_8));
        var service = new Modpacks(new HttpTransport(), List::<GameVersion>of, installer(repo, false), repo);
        service.update(root, instance, task);
        assertEquals("new", Files.readString(folder.resolve("mods/update.jar")));
        assertEquals("added", Files.readString(folder.resolve("mods/added.jar")));
        assertFalse(Files.exists(folder.resolve("mods/remove.jar")));
        assertFalse(Files.exists(folder.resolve("mods/disabled.jar")));
        assertEquals("disabled original", Files.readString(folder.resolve("mods/disabled.jar.disabled")));
        assertEquals("user edited", Files.readString(folder.resolve("config/local.txt")));
        assertEquals("user kept", Files.readString(folder.resolve("config/removed-local.txt")));
        assertEquals("untracked local mod", Files.readString(folder.resolve("mods/collision.jar")));
        assertEquals("2", JsonFiles.read(folder.resolve(ModpackArchive.STATE)).getAsJsonObject("manifest").get("version").getAsString());
    }

    @Test
    void serverCommitFailureRestoresAllManagedFilesAndManifest() throws Exception {
        GameInstance instance = new GameInstance("server", "server", "base", LoaderType.VANILLA, null, IsolationOverride.ISOLATED, 4096);
        JsonInstanceRepository actual = new JsonInstanceRepository(temp.resolve("data"));
        actual.save(instance);
        InstanceRepository failing = new InstanceRepository() {
            public List<GameInstance> findAll() throws IOException {
                return actual.findAll();
            }

            public void save(GameInstance value) throws IOException {
                throw new IOException("fixture repository failure");
            }

            public void remove(String id) throws IOException {
                actual.remove(id);
            }
        };
        JsonObject old = manifest("1", "base", Map.of("mods/old.jar", "old"));
        Path root = temp.resolve("game"), folder = installState(root, instance, old, Map.of("mods/old.jar", "old"));
        JsonObject remote = manifest("2", "base", Map.of("mods/new.jar", "new"));
        responses.put("/server-manifest.json", remote.toString().getBytes(StandardCharsets.UTF_8));
        var service = new Modpacks(new HttpTransport(), List::<GameVersion>of, installer(failing, false), failing);
        assertThrows(IOException.class, () -> service.update(root, instance, task));
        assertEquals("old", Files.readString(folder.resolve("mods/old.jar")));
        assertFalse(Files.exists(folder.resolve("mods/new.jar")));
        assertEquals("1", JsonFiles.read(folder.resolve(ModpackArchive.STATE)).getAsJsonObject("manifest").get("version").getAsString());
    }

    @Test
    void serverMinecraftVersionUpdateRebuildsProfilePreservingIdentityAndData() throws Exception {
        GameVersion next = game("next");
        GameInstance instance = new GameInstance("server", "server", "base", LoaderType.VANILLA, null, IsolationOverride.ISOLATED, 6144);
        var repo = new JsonInstanceRepository(temp.resolve("data"));
        repo.save(instance);
        JsonObject old = manifest("1", "base", Map.of());
        Path root = temp.resolve("game"), folder = installState(root, instance, old, Map.of("saves/world.dat", "world"));
        JsonObject remote = manifest("2", "next", Map.of());
        JsonObject fabric = new JsonObject();
        fabric.addProperty("id", "fabric");
        fabric.addProperty("version", "0.16.0");
        remote.getAsJsonArray("addons").add(fabric);
        responses.put("/server-manifest.json", remote.toString().getBytes(StandardCharsets.UTF_8));
        var service = new Modpacks(new HttpTransport(), () -> List.of(next), installer(repo, false), repo);
        GameInstance updated = service.update(root, instance, task);
        assertEquals(instance.id(), updated.id());
        assertEquals(instance.name(), updated.name());
        assertEquals(6144, updated.memoryMb());
        assertEquals("next", updated.gameVersion());
        assertEquals(LoaderType.FABRIC, updated.loader());
        assertEquals("0.16.0", updated.loaderVersion());
        assertEquals(updated, repo.findAll().getFirst());
        assertEquals("next", JsonFiles.read(folder.resolve(InstanceDirectories.PROFILE)).get("inheritsFrom").getAsString());
        assertEquals("FixtureMain", new VersionResolver().resolveInstance(root, updated).get("mainClass").getAsString());
        assertEquals("world", Files.readString(folder.resolve("saves/world.dat")));
    }
}
