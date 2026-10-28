package com.fuzzlauncher;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.auth.MicrosoftAuthentication;
import com.fuzzlauncher.infrastructure.game.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CoreTest {
  @TempDir Path temp;
  private final TaskContext context =
      new TaskContext() {
        public void report(String text, double progress) {}

        public boolean cancelled() {
          return false;
        }
      };

  private GameInstance instance(LoaderType loader, IsolationOverride override) {
    return new GameInstance("test-instance", "测试", "1.20.4", loader, "1", override, 4096);
  }

  @Test
  void isolationMatrixAndExplicitOverrides() {
    IsolationPolicy policy = new IsolationPolicy();
    for (IsolationMode global : IsolationMode.values())
      for (LoaderType loader : LoaderType.values())
        for (IsolationOverride override : IsolationOverride.values()) {
          boolean expected =
              override == IsolationOverride.ISOLATED
                  || override == IsolationOverride.INHERIT
                      && (global == IsolationMode.ALL
                          || global == IsolationMode.MODDED && loader != LoaderType.VANILLA);
          assertEquals(
              expected ? temp.resolve("versions/测试") : temp,
              policy.gameDirectory(temp, instance(loader, override), global));
        }
  }

  @Test
  void independentInstanceSettingsRoundTripAndRemoveKeepsData() throws Exception {
    JsonInstanceRepository repo = new JsonInstanceRepository(temp);
    GameInstance item = instance(LoaderType.FABRIC, IsolationOverride.ISOLATED);
    repo.save(item);
    assertEquals(List.of(item), repo.findAll());
    repo.save(item.withSettings("另一实例", IsolationOverride.SHARED, 2048));
    assertEquals(1, repo.findAll().size());
    GameInstance second =
        new GameInstance(
            "second",
            "second",
            "1.20.4",
            LoaderType.VANILLA,
            null,
            IsolationOverride.INHERIT,
            4096);
    repo.save(second);
    repo.save(item);
    assertEquals(List.of(item, second), repo.findAll());
    repo.remove(second.id());
    Path save = temp.resolve("versions/test-instance/saves/world/level.dat");
    Files.createDirectories(save.getParent());
    Files.writeString(save, "world");
    repo.remove(item.id());
    assertTrue(repo.findAll().isEmpty());
    assertEquals("world", Files.readString(save));
  }

  @Test
  void invalidInputsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GameInstance(
                "../outside",
                "test",
                "1.20",
                LoaderType.VANILLA,
                null,
                IsolationOverride.INHERIT,
                4096));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            instance(LoaderType.FORGE, IsolationOverride.INHERIT)
                .withSettings("", IsolationOverride.INHERIT, 512));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            instance(LoaderType.FORGE, IsolationOverride.INHERIT)
                .withSettings("valid", IsolationOverride.INHERIT, 0));
  }

  @Test
  void versionInheritanceOverridesLibraryVersionAndAppendsArguments() throws Exception {
    JsonObject parent =
        JsonParser.parseString(
                "{\"id\":\"base\",\"mainClass\":\"Base\",\"libraries\":[{\"name\":\"a:b:1\"}],\"arguments\":{\"game\":[\"--username\",\"${auth_player_name}\"]}}")
            .getAsJsonObject();
    JsonObject child =
        JsonParser.parseString(
                "{\"id\":\"child\",\"inheritsFrom\":\"base\",\"mainClass\":\"Fabric\",\"libraries\":[{\"name\":\"a:b:2\"},{\"name\":\"x:y:1\"}],\"arguments\":{\"game\":[\"--fabric\"]}}")
            .getAsJsonObject();
    JsonFiles.write(temp.resolve("versions/base/base.json"), parent);
    JsonFiles.write(temp.resolve("versions/child/child.json"), child);
    JsonObject resolved = new VersionResolver().resolve(temp, "child");
    assertEquals("Fabric", resolved.get("mainClass").getAsString());
    assertEquals("base", resolved.get("_clientVersion").getAsString());
    assertEquals(2, resolved.getAsJsonArray("libraries").size());
    assertEquals(
        "a:b:2",
        resolved.getAsJsonArray("libraries").get(0).getAsJsonObject().get("name").getAsString());
    assertEquals(3, resolved.getAsJsonObject("arguments").getAsJsonArray("game").size());
    child.addProperty("inheritsFrom", "child");
    JsonFiles.write(temp.resolve("versions/child/child.json"), child);
    assertThrows(IOException.class, () -> new VersionResolver().resolve(temp, "child"));
  }

  @Test
  void rulesHonorPlatformAndDoNotEnableDemoMode() {
    assertFalse(
        VersionResolver.allowed(
            JsonParser.parseString(
                    "{\"rules\":[{\"action\":\"allow\",\"os\":{\"name\":\"linux\"}}]}")
                .getAsJsonObject()));
    assertFalse(
        VersionResolver.allowed(
            JsonParser.parseString(
                    "{\"rules\":[{\"action\":\"allow\",\"features\":{\"is_demo_user\":true}}]}")
                .getAsJsonObject()));
    assertTrue(
        VersionResolver.allowed(
            JsonParser.parseString(
                    "{\"rules\":[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"osx\"}}]}")
                .getAsJsonObject()));
  }

  @Test
  void legacyQuotesAndUnresolvedArgumentsFailClearly() throws Exception {
    assertEquals(
        List.of("--gameDir", "C:\\有空格 test", "--name", ""),
        LaunchArguments.splitLegacy("--gameDir \"C:\\有空格 test\" --name \"\""));
    assertThrows(IOException.class, () -> LaunchArguments.splitLegacy("\"broken"));
    assertThrows(IOException.class, () -> LaunchArguments.substitute("${unknown}", Map.of()));
  }

  @Test
  void launchArgumentsUseExactlyTheResolvedIsolationDirectoryAndRealSession() throws Exception {
    GameInstance item = instance(LoaderType.VANILLA, IsolationOverride.ISOLATED);
    Path directory = new IsolationPolicy().gameDirectory(temp, item, IsolationMode.ALL);
    JsonObject version =
        JsonParser.parseString(
                "{\"_clientVersion\":\"1.20.4\",\"mainClass\":\"net.minecraft.client.main.Main\",\"arguments\":{\"game\":[\"--gameDir\",\"${game_directory}\",\"--accessToken\",\"${auth_access_token}\"]}}")
            .getAsJsonObject();
    var session =
        new GameSession(
            new Account("test-home", "test-uuid", "test-player"),
            "unit-test-token",
            Instant.now().plusSeconds(600));
    List<String> command =
        new LaunchArguments()
            .build(temp, directory, temp.resolve("native"), item, session, version, List.of());
    assertEquals(
        directory.toAbsolutePath().toString(), command.get(command.indexOf("--gameDir") + 1));
    assertEquals("unit-test-token", command.get(command.indexOf("--accessToken") + 1));
    assertFalse(command.contains("--demo"));
    assertFalse(session.toString().contains("unit-test-token"));
  }

  @Test
  void cancelledLoginNeverOpensBrowserOrCreatesAnOfflineIdentity() {
    MicrosoftAuthentication auth = new MicrosoftAuthentication(new HttpTransport(), temp);
    TaskContext cancelled =
        new TaskContext() {
          public void report(String text, double progress) {}

          public boolean cancelled() {
            return true;
          }
        };
    assertThrows(CancellationException.class, () -> auth.login(cancelled));
    assertFalse(Files.exists(temp.resolve("account.json")));
  }

  @Test
  void legacySettingsIgnoreClientIdAndPreserveGameSettings() throws Exception {
    JsonObject legacy = new JsonObject();
    String root = temp.resolve("custom-game").toString();
    legacy.addProperty("gameRoot", root);
    legacy.addProperty("isolation", "ALL");
    legacy.addProperty("microsoftClientId", "legacy-custom-id");
    JsonFiles.write(temp.resolve("settings.json"), legacy);
    SettingsStore store = new SettingsStore(temp);
    LauncherSettings settings = store.load();
    assertEquals(root, settings.gameRoot());
    assertEquals(IsolationMode.ALL, settings.isolation());
    store.save(settings);
    assertFalse(JsonFiles.read(temp.resolve("settings.json")).has("microsoftClientId"));
    assertEquals(settings, store.load());
  }

  @Test
  void dpapiDoesNotStorePlaintext() throws Exception {
    WindowsSecretStore store = new WindowsSecretStore(temp.resolve("credentials"));
    store.write("private-test-value");
    assertEquals("private-test-value", store.read());
    assertFalse(
        new String(Files.readAllBytes(temp.resolve("credentials")), StandardCharsets.ISO_8859_1)
            .contains("private-test-value"));
  }

  @Test
  void zipSlipAndPathTraversalCannotEscapeTarget() throws Exception {
    assertThrows(IOException.class, () -> JsonFiles.safeResolve(temp, "../outside"));
    Path zip = temp.resolve("evil.zip");
    try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(zip))) {
      stream.putNextEntry(new ZipEntry("../escape"));
      stream.write(1);
      stream.closeEntry();
    }
    assertThrows(
        IOException.class, () -> ZipFiles.extract(zip, temp.resolve("extracted"), false, context));
    assertFalse(Files.exists(temp.resolve("escape")));
  }

  @Test
  void corruptDownloadDoesNotReplaceExistingFileAndCancellationStopsWork() throws Exception {
    Path destination = temp.resolve("file.jar");
    Files.writeString(destination, "existing");
    String expected = HttpTransport.digest(destination, "SHA-1");
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/file",
        exchange -> {
          requests.incrementAndGet();
          byte[] bytes = "corrupt".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      HttpTransport http = new HttpTransport();
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
      // Existing valid content is reused without issuing a request.
      http.download(url, destination, expected, context);
      assertEquals(0, requests.get());
      assertThrows(
          IOException.class,
          () ->
              http.download(url, destination, "0000000000000000000000000000000000000000", context));
      assertEquals("existing", Files.readString(destination));
      assertEquals(3, requests.get());
      TaskContext cancelled =
          new TaskContext() {
            public void report(String s, double p) {}

            public boolean cancelled() {
              return true;
            }
          };
      assertThrows(
          CancellationException.class,
          () -> http.download(url, temp.resolve("new.jar"), "", cancelled));
    } finally {
      server.stop(0);
    }
  }
}
