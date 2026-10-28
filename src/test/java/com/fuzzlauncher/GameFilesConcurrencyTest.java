package com.fuzzlauncher;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.infrastructure.HttpTransport;
import com.fuzzlauncher.infrastructure.game.GameFiles;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameFilesConcurrencyTest {
  @TempDir Path temp;

  @Test
  void librariesUseBoundedParallelismAndVerifiedCacheAvoidsRedownload() throws Exception {
    int count = Math.min(12, GameFiles.DOWNLOAD_CONCURRENCY);
    CountDownLatch entered = new CountDownLatch(count);
    AtomicInteger active = new AtomicInteger(),
        peak = new AtomicInteger(),
        requests = new AtomicInteger();
    byte[] body = "library-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Path reference = temp.resolve("reference");
    Files.write(reference, body);
    String sha = HttpTransport.digest(reference, "SHA-1");
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(handlers);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          peak.accumulateAndGet(active.incrementAndGet(), Math::max);
          entered.countDown();
          try (exchange) {
            try {
              entered.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } finally {
            active.decrementAndGet();
          }
        });
    server.start();
    try {
      JsonObject version = new JsonObject();
      JsonArray libraries = new JsonArray();
      version.add("libraries", libraries);
      for (int i = 0; i < count; i++) {
        JsonObject item = new JsonObject(),
            downloads = new JsonObject(),
            artifact = new JsonObject();
        item.addProperty("name", "test:library" + i + ":1");
        artifact.addProperty("path", "test/library" + i + ".jar");
        artifact.addProperty(
            "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/library" + i);
        artifact.addProperty("sha1", sha);
        downloads.add("artifact", artifact);
        item.add("downloads", downloads);
        libraries.add(item);
      }
      GameFiles files = new GameFiles(new HttpTransport());
      TaskContext context =
          new TaskContext() {
            public void report(String message, double progress) {}

            public boolean cancelled() {
              return false;
            }
          };
      files.prepare(temp.resolve("game"), version, context);
      assertEquals(count, peak.get());
      assertTrue(peak.get() <= GameFiles.DOWNLOAD_CONCURRENCY);
      files.prepare(temp.resolve("game"), version, context);
      assertEquals(count, requests.get());
    } finally {
      server.stop(0);
      handlers.shutdownNow();
    }
  }
}
