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
      assertEquals(List.of("base"), dirs.map(p -> p.getFileName().toString()).toList());
    }
    try (var paths = Files.walk(root.resolve(".fuzz-staging"))) {
      assertTrue(paths.anyMatch(p -> p.getFileName().toString().equals("incomplete-profile")));
    }
  }
}
