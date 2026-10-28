package com.fuzzlauncher.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.application.TaskContext;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MirrorFallbackTest {
  @TempDir Path temp;

  @Test
  void mirrorHttpFailureFallsBackForJsonAndText() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger mirrors = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            boolean mirror = exchange.getRequestURI().getPath().startsWith("/mirror");
            if (mirror) mirrors.incrementAndGet();
            byte[] body = "{\"value\":\"official\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(mirror ? 404 : 200, body.length);
            exchange.getResponseBody().write(body);
          }
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/official";
      HttpTransport http = new HttpTransport(value -> value.replace("/official", "/mirror"));
      assertEquals("official", http.getJson(url).get("value").getAsString());
      assertTrue(http.getText(url).contains("official"));
      assertEquals(2, mirrors.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void corruptMirrorFileIsNeverPublishedAndOfficialFallbackIsVerified() throws Exception {
    byte[] expected = "verified-official-file".getBytes(StandardCharsets.UTF_8);
    Path reference = temp.resolve("reference");
    Files.write(reference, expected);
    String sha1 = HttpTransport.digest(reference, "SHA-1");
    AtomicInteger mirrors = new AtomicInteger();
    AtomicInteger officials = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            boolean mirror = exchange.getRequestURI().getPath().startsWith("/mirror");
            if (mirror) mirrors.incrementAndGet();
            else officials.incrementAndGet();
            byte[] body = mirror ? "corrupt".getBytes(StandardCharsets.UTF_8) : expected;
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
        });
    server.start();
    try {
      HttpTransport http = new HttpTransport(value -> value.replace("/official", "/mirror"));
      Path target = temp.resolve("downloads/file.jar");
      http.download(
          "http://127.0.0.1:" + server.getAddress().getPort() + "/official",
          target,
          sha1,
          new TaskContext() {
            public void report(String message, double progress) {}

            public boolean cancelled() {
              return false;
            }
          });
      assertArrayEquals(expected, Files.readAllBytes(target));
      assertEquals(1, mirrors.get());
      assertEquals(1, officials.get());
      try (var files = Files.list(target.getParent())) {
        assertEquals(1, files.count());
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void credentialRequestsNeverInvokeMirrorRouting() throws Exception {
    AtomicInteger routes = new AtomicInteger();
    AtomicInteger authorized = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            if ("Bearer unit-test-token"
                .equals(exchange.getRequestHeaders().getFirst("Authorization")))
              authorized.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
        });
    server.start();
    try {
      HttpTransport http =
          new HttpTransport(
              value -> {
                routes.incrementAndGet();
                return value + "mirror";
              });
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth";
      http.authorizedGet(url, "unit-test-token");
      http.postJson(url, new JsonObject());
      assertEquals(0, routes.get());
      assertEquals(1, authorized.get());
    } finally {
      server.stop(0);
    }
  }
}
