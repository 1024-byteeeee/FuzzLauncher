package com.fuzzlauncher;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real official adapters, no account authorization or game launch, with assets deliberately
 * omitted.
 */
@EnabledIfSystemProperty(named = "fuzz.reinstallIntegration", matches = "true")
class LoaderReinstallIntegrationTest {
    @Test
    void cleanOfficialLoaderReinstallationAndDowngrade() throws Exception {
        Path directory = Path.of("build/reinstall-integration").toAbsolutePath();
        Path root = directory.resolve("game");
        Files.createDirectories(root);
        HttpTransport http = new HttpTransport();
        VersionCatalog catalog = new VersionCatalog(http);
        var resolver = new VersionResolver();
        var files = new GameFiles(http);
        var runtimes = new JavaRuntimeManager(http, directory.resolve("runtimes"));
        var repository = new JsonInstanceRepository(directory);
        TaskContext task = new TaskContext() {
            private String last = "";

            public boolean cancelled() {
                return false;
            }

            public synchronized void report(String message, double progress) {
                if (!last.equals(message) && (message.startsWith("运行处理器") || message.startsWith("正在提交") || message.startsWith("加载器已")
                    || message.startsWith("下载 ") && message.endsWith("安装器"))) {
                    System.out.println(message);
                    last = message;
                }
            }
        };
        GameVersion game = catalog.games().stream().filter(item -> item.id().equals("1.21.1")).findFirst().orElseThrow();
        Path base = root.resolve("baseVerisons/1.21.1");
        http.download(game.url(), base.resolve("1.21.1.json"), game.sha1(), task);
        JsonObject vanilla = JsonFiles.read(base.resolve("1.21.1.json"));
        JsonObject client = vanilla.getAsJsonObject("downloads").getAsJsonObject("client");
        http.download(client.get("url").getAsString(), base.resolve("1.21.1.jar"), client.get("sha1").getAsString(), task);
        for (String selected : System.getProperty("fuzz.reinstallLoaders", "FABRIC").split(",")) {
            LoaderType type = LoaderType.valueOf(selected);
            var candidates = catalog.loaders(type, game.id());
            assertTrue(candidates.size() >= 2);
            String id = "integration-" + type.name().toLowerCase(Locale.ROOT);
            GameInstance instance = repository.findAll().stream().filter(item -> item.id().equals(id)).findFirst().orElse(
                new GameInstance(id, "Reinstall " + type, game.id(), type, "uninstalled-fixture", IsolationOverride.ISOLATED, 4096));
            if (!Files.exists(InstanceDirectories.profile(root, instance))) {
                JsonObject descriptor = new JsonObject();
                descriptor.addProperty("id", id);
                descriptor.addProperty("inheritsFrom", game.id());
                descriptor.add("fuzzInstance", JsonFiles.GSON.toJsonTree(instance));
                JsonFiles.write(InstanceDirectories.profile(root, instance), descriptor);
                repository.save(instance);
            }
            Path world = InstanceDirectories.directory(root, instance).resolve("saves/fixture.txt");
            Files.createDirectories(world.getParent());
            Files.writeString(world, "preserved world");
            LoaderInstaller installer = type == LoaderType.FABRIC ? new FabricInstaller(http)
                : new OfficialLoaderInstaller(type, http, runtimes, resolver);
            var operation = new ReinstallLoader(repository, files, resolver, Map.of(type, installer));
            for (String version : candidates.subList(0, 2)) {
                System.out.println("Clean reinstallation: " + type + " " + version);
                instance = operation.execute(root, instance, version, task);
                JsonObject resolved = resolver.resolveInstance(root, instance);
                assertNotNull(resolved.get("mainClass"));
                for (var artifact : files.libraries(root, resolved)) {
                    assertTrue(Files.isRegularFile(artifact.path()));
                }
                assertEquals("preserved world", Files.readString(world));
                assertEquals(version, instance.loaderVersion());
            }
        }
        assertFalse(Files.exists(root.resolve(".fuzz-staging")));
    }
}
