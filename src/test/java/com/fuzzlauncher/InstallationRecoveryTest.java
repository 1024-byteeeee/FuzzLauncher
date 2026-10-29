package com.fuzzlauncher;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class InstallationRecoveryTest {
  @TempDir Path temp;
  private final TaskContext task =
      new TaskContext() {
        public void report(String message, double value) {}

        public boolean cancelled() {
          return false;
        }
      };
  private HttpServer server;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private String endpoint(String path, String body) {
    server.createContext(
        path,
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  @Test
  void officialMappingDownloadUsesManifestAndChecksHashWithoutOldHost() throws Exception {
    Path expected = temp.resolve("expected.txt");
    Files.writeString(expected, "official mapping fixture");
    String hash = HttpTransport.digest(expected, "SHA-1");
    String url = endpoint("/mapping", "official mapping fixture");
    JsonObject vanilla = new JsonObject();
    vanilla.addProperty("id", "base");
    JsonObject download = new JsonObject();
    download.addProperty("url", url);
    download.addProperty("sha1", hash);
    JsonObject downloads = new JsonObject();
    downloads.add("client_mappings", download);
    vanilla.add("downloads", downloads);
    JsonObject profile =
        JsonParser.parseString(
                """
{"json":"/version.json","data":{"MOJMAPS":{"client":"[fixture:mapping:1@txt]"}},"libraries":[],
 "processors":[{"jar":"fixture:processor:1","args":["--task","DOWNLOAD_MOJMAPS","--side","{SIDE}","--output","{MOJMAPS}"],
    "outputs":{"{MOJMAPS}":"HASH"}},
    {"sides":["server"],"jar":"never:execute:1","args":[]}]}
""")
            .getAsJsonObject();
    profile
        .getAsJsonArray("processors")
        .get(0)
        .getAsJsonObject()
        .getAsJsonObject("outputs")
        .addProperty("{MOJMAPS}", hash);
    Path installer = temp.resolve("installer.zip");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(installer))) {
      zip.putNextEntry(new ZipEntry("version.json"));
      zip.write("{\"id\":\"loader\"}".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    Path staging = Files.createDirectories(temp.resolve("staging"));
    JsonObject result =
        new ClientProcessors(new HttpTransport())
            .execute(temp, staging, installer, profile, vanilla, Path.of("java"), task);
    assertEquals("loader", result.get("id").getAsString());
    assertEquals(
        "official mapping fixture",
        Files.readString(staging.resolve("libraries/fixture/mapping/1/mapping-1.txt")));
  }

  @Test
  void failedPreparationDoesNotRegisterInstanceOrPublishBrokenVersion() throws Exception {
    String client = endpoint("/client", "client fixture");
    JsonObject base = new JsonObject();
    base.addProperty("id", "base");
    base.addProperty("mainClass", "FixtureMain");
    base.add("libraries", new JsonArray());
    JsonObject download = new JsonObject();
    download.addProperty("url", client);
    JsonObject downloads = new JsonObject();
    downloads.add("client", download);
    base.add("downloads", downloads);
    String metadata = endpoint("/metadata", base.toString());
    LoaderInstaller broken =
        (root, staging, game, loaderVersion, context) ->
            JsonParser.parseString(
                    """
                    {"inheritsFrom":"base","libraries":[{"name":"missing:generated:1","url":""}]}
                    """)
                .getAsJsonObject();
    JsonInstanceRepository repo = new JsonInstanceRepository(temp.resolve("state"));
    Path root = temp.resolve("game");
    InstallGame install =
        new InstallGame(
            repo,
            new GameFiles(new HttpTransport()),
            new VersionResolver(),
            Map.of(LoaderType.FORGE, broken));
    assertThrows(
        IOException.class,
        () ->
            install.execute(
                root,
                new GameVersion("base", "release", metadata, ""),
                LoaderType.FORGE,
                "1",
                "失败测试",
                task));
    assertTrue(repo.findAll().isEmpty());
    try (var dirs = Files.list(root.resolve("versions"))) {
      assertEquals(0, dirs.count());
    }
    assertFalse(Files.exists(root.resolve(".fuzz-staging")));
  }

  private GameVersion fixtureGame() {
    String client = endpoint("/fixture-client", "client fixture");
    String json =
        "{\"id\":\"base\",\"mainClass\":\"FixtureMain\",\"libraries\":[],\"downloads\":{\"client\":{\"url\":\""
            + client
            + "\"}}}";
    return new GameVersion("base", "release", endpoint("/fixture-metadata", json), "");
  }

  @Test
  void cancellationAfterPublicationDeletesNestedBaseNamedInstance() throws Exception {
    Path root = temp.resolve("published-cancel");
    var repo = new JsonInstanceRepository(temp.resolve("published-state"));
    var installer =
        new InstallGame(repo, new GameFiles(new HttpTransport()), new VersionResolver(), Map.of());
    TaskContext cancelled =
        new TaskContext() {
          public void report(String message, double value) {}

          public boolean cancelled() {
            if (!Files.exists(root.resolve("versions/base/" + InstanceDirectories.PROFILE)))
              return false;
            try {
              Path nested = root.resolve("versions/base/nested/download/partial.part");
              Files.createDirectories(nested.getParent());
              Files.writeString(nested, "partial");
            } catch (IOException ex) {
              throw new UncheckedIOException(ex);
            }
            return true;
          }
        };
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> installer.execute(root, fixtureGame(), LoaderType.VANILLA, null, "base", cancelled));
    assertFalse(Files.exists(root.resolve("versions/base")));
    assertFalse(Files.exists(root.resolve(".fuzz-staging")));
    assertTrue(repo.findAll().isEmpty());
  }

  @Test
  void cancellationBeforeLoaderPublicationDeletesNestedWorkspaceAndNewBase() throws Exception {
    var repo = new JsonInstanceRepository(temp.resolve("cancel-state"));
    Path root = temp.resolve("cancel-game");
    LoaderInstaller cancelled =
        (gameRoot, staging, game, loaderVersion, context) -> {
          Files.createDirectories(staging.resolve("libraries/nested/partial"));
          Files.writeString(staging.resolve("libraries/nested/partial/download.part"), "partial");
          throw new java.util.concurrent.CancellationException("cancel fixture");
        };
    var installer =
        new InstallGame(
            repo,
            new GameFiles(new HttpTransport()),
            new VersionResolver(),
            Map.of(LoaderType.FABRIC, cancelled));
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> installer.execute(root, fixtureGame(), LoaderType.FABRIC, "1", "取消实例", task));
    assertFalse(Files.exists(root.resolve("versions/base")));
    assertFalse(Files.exists(root.resolve("versions/取消实例")));
    assertFalse(Files.exists(root.resolve(".fuzz-staging")));
    assertTrue(repo.findAll().isEmpty());
  }

  @Test
  void failedRegistrationDeletesPublishedNestedDirectoryAndPreservesExistingInstance()
      throws Exception {
    var repo = new JsonInstanceRepository(temp.resolve("existing-state"));
    Path root = temp.resolve("existing-game");
    GameVersion game = fixtureGame();
    var installer =
        new InstallGame(repo, new GameFiles(new HttpTransport()), new VersionResolver(), Map.of());
    GameInstance existing = installer.execute(root, game, LoaderType.VANILLA, null, "已有实例", task);
    Files.createDirectories(InstanceDirectories.directory(root, existing).resolve("saves/world"));
    Path saved = InstanceDirectories.directory(root, existing).resolve("saves/world/level.dat");
    Files.writeString(saved, "existing world");
    assertFalse(Files.exists(root.resolve(".fuzz-staging")));
    InstanceRepository failing =
        new InstanceRepository() {
          public List<GameInstance> findAll() throws IOException {
            return repo.findAll();
          }

          public void remove(String id) throws IOException {
            repo.remove(id);
          }

          public void save(GameInstance item) throws IOException {
            Path partial =
                InstanceDirectories.directory(root, item).resolve("nested/partial/download.part");
            Files.createDirectories(partial.getParent());
            Files.writeString(partial, "partial");
            throw new IOException("fixture registration failure");
          }
        };
    var broken =
        new InstallGame(
            failing, new GameFiles(new HttpTransport()), new VersionResolver(), Map.of());
    assertThrows(
        IOException.class,
        () -> broken.execute(root, game, LoaderType.VANILLA, null, "失败实例", task));
    assertFalse(Files.exists(root.resolve("versions/失败实例")));
    assertFalse(Files.exists(root.resolve(".fuzz-staging")));
    assertTrue(Files.isRegularFile(root.resolve("baseVerisons/base/base.jar")));
    assertEquals("existing world", Files.readString(saved));
    assertEquals(List.of(existing), repo.findAll());
  }

  @Test
  void failedVanillaMetadataDownloadRemovesNewVersionDirectory() throws Exception {
    String metadata = endpoint("/invalid-metadata", "invalid json");
    Path root = temp.resolve("metadata-game");
    var repo = new JsonInstanceRepository(temp.resolve("metadata-state"));
    var installer =
        new InstallGame(repo, new GameFiles(new HttpTransport()), new VersionResolver(), Map.of());
    assertThrows(
        Exception.class,
        () ->
            installer.execute(
                root,
                new GameVersion("base", "release", metadata, ""),
                LoaderType.VANILLA,
                null,
                "下载失败",
                task));
    assertFalse(Files.exists(root.resolve("versions/base")));
    assertFalse(Files.exists(root.resolve(".fuzz-staging")));
    assertTrue(repo.findAll().isEmpty());
  }

  @Test
  void installationsUseDisplayNameFoldersIncludingTheSharedBaseVersionName() throws Exception {
    String client = endpoint("/named-client", "client fixture");
    JsonObject base =
        JsonParser.parseString("{\"id\":\"base\",\"mainClass\":\"FixtureMain\",\"libraries\":[]}")
            .getAsJsonObject();
    JsonObject download = new JsonObject(), downloads = new JsonObject();
    download.addProperty("url", client);
    downloads.add("client", download);
    base.add("downloads", downloads);
    String metadata = endpoint("/named-metadata", base.toString());
    var repo = new JsonInstanceRepository(temp.resolve("named-state"));
    Path root = temp.resolve("named-game");
    var installer =
        new InstallGame(repo, new GameFiles(new HttpTransport()), new VersionResolver(), Map.of());
    var game = new GameVersion("base", "release", metadata, "");
    GameInstance first = installer.execute(root, game, LoaderType.VANILLA, null, "base", task);
    GameInstance second = installer.execute(root, game, LoaderType.VANILLA, null, "另一个实例", task);
    assertTrue(Files.isRegularFile(root.resolve("baseVerisons/base/base.json")));
    assertTrue(Files.isRegularFile(root.resolve("baseVerisons/base/base.jar")));
    assertFalse(Files.exists(root.resolve("versions/base/base.json")));
    assertFalse(Files.exists(root.resolve("versions/base/base.jar")));
    for (GameInstance item : List.of(first, second)) {
      assertTrue(Files.isRegularFile(InstanceDirectories.profile(root, item)));
      assertFalse(Files.exists(root.resolve("versions").resolve(item.id())));
      assertEquals(
          root.resolve("versions").resolve(item.name()),
          new IsolationPolicy().gameDirectory(root, item, IsolationMode.ALL));
      assertEquals(
          "base",
          new VersionResolver().resolveInstance(root, item).get("_clientVersion").getAsString());
    }
    assertThrows(
        IOException.class,
        () -> installer.execute(root, game, LoaderType.VANILLA, null, "BASE", task));
    assertEquals(2, repo.findAll().size());
  }
}
