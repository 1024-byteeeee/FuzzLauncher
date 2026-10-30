package com.fuzzlauncher;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.*;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BaseVersionsTest {
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
    void allLoadersUseLauncherCacheOutsideCustomGameRootIncludingReinstallation() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            String metadata = "{\"id\":\"1.21.8\",\"mainClass\":\"Main\",\"arguments\":{\"game\":[\"--username\",\"${auth_player_name}\"]},\"libraries\":[],\"downloads\":{\"client\":{\"url\":\""
                + baseUrl + "/client\"}}}";
            for (var entry : Map.of("/client", "client fixture", "/metadata", metadata).entrySet()) {
                server.createContext(entry.getKey(), exchange -> {
                    byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
            }
            Path root = temp.resolve("custom-game-root");
            BaseVersions cache = new BaseVersions(temp.resolve("launcher/baseVerisons"));
            GameFiles files = new GameFiles(new HttpTransport(), cache);
            VersionResolver resolver = new VersionResolver(cache);
            var repository = new JsonInstanceRepository(temp.resolve("state"));
            LoaderInstaller loader = (gameRoot, workspace, game, version, context) -> JsonParser
                .parseString("{\"mainClass\":\"LoaderMain\",\"libraries\":[]}").getAsJsonObject();
            var adapters = Map.of(LoaderType.FABRIC, loader, LoaderType.FORGE, loader, LoaderType.NEOFORGE, loader);
            var install = new InstallGame(repository, files, resolver, adapters);
            var game = new GameVersion("1.21.8", "release", baseUrl + "/metadata", "");
            for (var type : LoaderType.values()) {
                var instance = install.execute(root, game, type, type == LoaderType.VANILLA ? null : "1", type.name(), task);
                var resolved = resolver.resolveInstance(root, instance);
                assertEquals("1.21.8", resolved.get("_clientVersion").getAsString());
                if (type != LoaderType.VANILLA) {
                    assertEquals("2", install.reinstallLoader(root, instance, "2", task).loaderVersion());
                }
            }
            assertEquals("client fixture", Files.readString(cache.client(root, "1.21.8")));
            assertFalse(Files.exists(root.resolve("baseVerisons")));
            assertFalse(Files.exists(root.resolve("versions/1.21.8")));
            assertFalse(Files.exists(root.resolve(".fuzz-staging")));
            try (var folders = Files.list(root.resolve("versions"))) {
                assertEquals(4, folders.count());
            }
            assertEquals(4, InstanceCatalog.scan(root, repository.findAll(), cache).size());
            var instance = repository.findAll().getFirst();
            var resolved = resolver.resolveInstance(root, instance);
            var args = new LaunchArguments(cache).build(root, InstanceDirectories.directory(root, instance), root.resolve("natives"),
                instance, GameSession.offline(Account.offline("Fixture")), resolved, List.of());
            String clientPath = cache.client(root, "1.21.8").toString();
            assertTrue(args.stream().anyMatch(value -> value.contains(clientPath)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void legacyBaseMovesOutOfVersionsWithoutMovingOwnedInstanceData() throws Exception {
        Path root = temp.resolve("game");
        Path folder = root.resolve("versions/1.21.8");
        JsonObject vanilla = JsonParser.parseString("{\"id\":\"1.21.8\",\"mainClass\":\"Main\",\"libraries\":[]}").getAsJsonObject();
        JsonFiles.write(folder.resolve("1.21.8.json"), vanilla);
        Files.writeString(folder.resolve("1.21.8.jar"), "client");
        GameInstance instance = new GameInstance("id", "1.21.8", "1.21.8", LoaderType.VANILLA, null, IsolationOverride.ISOLATED, 4096);
        JsonFiles.write(folder.resolve(InstanceDirectories.PROFILE), JsonParser.parseString("{\"id\":\"id\",\"inheritsFrom\":\"1.21.8\"}"));
        Files.createDirectories(folder.resolve("saves"));
        Files.writeString(folder.resolve("saves/world.dat"), "world");
        BaseVersions cache = new BaseVersions(temp.resolve("launcher/baseVerisons"));
        var resolver = new VersionResolver(cache);
        assertEquals("Main", resolver.resolveInstance(root, instance).get("mainClass").getAsString());
        assertFalse(Files.exists(folder.resolve("1.21.8.jar")));
        assertTrue(Files.isRegularFile(InstanceDirectories.profile(root, instance)));
        var repository = new JsonInstanceRepository(temp.resolve("state"));
        repository.save(instance);
        var renamed = instance.withSettings("新实例", instance.isolation(), instance.memoryMb());
        InstanceDirectories.save(root, instance, renamed, repository);
        assertFalse(Files.exists(folder));
        assertEquals("world", Files.readString(InstanceDirectories.directory(root, renamed).resolve("saves/world.dat")));
        assertEquals("Main", resolver.resolveInstance(root, renamed).get("mainClass").getAsString());
        cache.migrate(root, "1.21.8"); // Repeated startup is harmless.
    }

    @Test
    void baseOnlyFoldersAreRemovedButDifferentCacheContentIsPreserved() throws Exception {
        Path root = temp.resolve("game");
        BaseVersions cache = new BaseVersions(temp.resolve("launcher/baseVerisons"));
        Path legacy = root.resolve("versions/base");
        JsonFiles.write(legacy.resolve("base.json"), Map.of("id", "base"));
        Files.writeString(legacy.resolve("base.jar"), "client");
        cache.migrate(root, "base");
        assertFalse(Files.exists(legacy));
        assertEquals("client", Files.readString(cache.client(root, "base")));
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("base.jar"), "different client");
        JsonFiles.write(legacy.resolve("base.json"), Map.of("id", "base", "custom", true));
        cache.migrate(root, "base");
        assertEquals("different client", Files.readString(legacy.resolve("base.jar")));
        assertEquals("client", Files.readString(cache.client(root, "base")));
    }
}
