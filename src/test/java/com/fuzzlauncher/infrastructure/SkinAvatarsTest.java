package com.fuzzlauncher.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.*;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SkinAvatarsTest {
  @TempDir Path temp;
  private static final String UUID = "0123456789abcdef0123456789abcdef";
  private static final String A = "https://textures.minecraft.net/texture/" + "a".repeat(64);
  private static final String B = "https://textures.minecraft.net/texture/" + "b".repeat(64);
  private HttpServer server;
  private byte[] first, second;
  private final AtomicInteger images = new AtomicInteger(), profiles = new AtomicInteger();
  private final TaskContext task =
      new TaskContext() {
        public void report(String message, double progress) {}

        public boolean cancelled() {
          return false;
        }
      };

  @BeforeEach
  void start() throws Exception {
    first = png(skin(Color.RED, new Color(0, 0, 255, 128)));
    second = png(skin(Color.GREEN, new Color(0, 0, 0, 0)));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] bytes;
          if (exchange.getRequestURI().getPath().equals("/profile")) {
            profiles.incrementAndGet();
            String texture =
                Base64.getEncoder()
                    .encodeToString(
                        ("{\"textures\":{\"SKIN\":{\"url\":\"" + A + "\"}}}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bytes =
                ("{\"properties\":[{\"name\":\"textures\",\"value\":\"" + texture + "\"}]}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          } else {
            images.incrementAndGet();
            bytes = exchange.getRequestURI().getPath().equals("/a") ? first : second;
          }
          try (exchange) {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private SkinAvatars loader() {
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    return new SkinAvatars(
        temp,
        new HttpTransport(
            url ->
                url.contains("sessionserver")
                    ? base + "/profile"
                    : url.equals(A) ? base + "/a" : base + "/b"));
  }

  private Account account(String url) {
    return new Account("home", UUID, "Steve", AccountType.MICROSOFT, url);
  }

  private static BufferedImage skin(Color face, Color hat) {
    BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    var g = image.createGraphics();
    try {
      g.setColor(face);
      g.fillRect(8, 8, 8, 8);
      g.setColor(hat);
      g.fillRect(40, 8, 8, 8);
    } finally {
      g.dispose();
    }
    return image;
  }

  private static byte[] png(BufferedImage image) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    return bytes.toByteArray();
  }

  @Test
  void faceHatCompositingCacheRestartAndChangedSkin() throws Exception {
    byte[] red = loader().load(account(A), task);
    Color pixel = new Color(ImageIO.read(new ByteArrayInputStream(red)).getRGB(0, 0), true);
    assertEquals(255, pixel.getAlpha());
    assertTrue(Math.abs(pixel.getBlue() - 128) <= 1);
    assertTrue(Math.abs(pixel.getRed() - 127) <= 1);
    assertArrayEquals(red, loader().load(account(A), task));
    assertEquals(1, images.get(), "Restart must reuse the cached PNG");
    var changed = ImageIO.read(new ByteArrayInputStream(loader().load(account(B), task)));
    assertEquals(Color.GREEN.getRGB(), changed.getRGB(0, 0));
    assertEquals(2, images.get());
    assertEquals(0, profiles.get());
  }

  @Test
  void legacyAccountsFetchOfficialTextureOnceAndOfflineAccountsDoNotFetch() throws Exception {
    assertNotNull(loader().load(account(""), task));
    assertEquals(1, profiles.get());
    assertEquals(1, images.get());
    assertNotNull(loader().load(account(""), task));
    assertEquals(1, profiles.get());
    assertNull(loader().load(Account.offline("Steve"), task));
    assertEquals(1, images.get());
    Account old =
        JsonFiles.GSON.fromJson(
            "{\"homeAccountId\":\"home\",\"uuid\":\"" + UUID + "\",\"name\":\"Steve\"}",
            Account.class);
    assertEquals("", old.skinUrl());
    assertFalse(old.isOffline());
    assertEquals(
        account(A), JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(account(A)), Account.class));
  }

  @Test
  void invalidImagesUrlsAndOptionalProfileDataDoNotPublishBadCache() throws Exception {
    second = png(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB));
    assertThrows(IOException.class, () -> loader().load(account(B), task));
    assertFalse(Files.exists(temp.resolve("skins/" + UUID + ".png")));
    try (var files = Files.list(temp.resolve("skins"))) {
      assertEquals(0, files.count());
    }
    assertEquals(A, SkinAvatars.textureUrl(A.replace("https:", "http:")));
    for (String url :
        List.of(
            "https://evil.test/texture/" + "a".repeat(64), A + "?token=x", "file:///tmp/skin.png"))
      assertThrows(IOException.class, () -> SkinAvatars.textureUrl(url));
    assertEquals(
        "",
        SkinAvatars.activeSkinUrl(
            JsonParser.parseString("{\"skins\":[{\"state\":{},\"url\":{}}]}").getAsJsonObject()));
    assertEquals(
        A,
        SkinAvatars.activeSkinUrl(
            JsonParser.parseString(
                    "{\"skins\":[{\"state\":\"INACTIVE\",\"url\":\"x\"},{\"state\":\"ACTIVE\",\"url\":\""
                        + A
                        + "\"}]}")
                .getAsJsonObject()));
  }

  @Test
  void opaqueLegacyHatIsNotRenderedOverTheFace() throws Exception {
    BufferedImage legacy = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
    var g = legacy.createGraphics();
    try {
      g.setColor(Color.RED);
      g.fillRect(8, 8, 8, 8);
    } finally {
      g.dispose();
    }
    assertEquals(Color.RED.getRGB(), SkinAvatars.face(legacy).getRGB(0, 0));
  }
}
