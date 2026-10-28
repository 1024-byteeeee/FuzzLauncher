package com.fuzzlauncher;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.infrastructure.HttpTransport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpDownloadResumeTest {
  @TempDir Path temp;

  @Test
  void interruptedDownloadResumesWithRangeAndValidator() throws Exception {
    verifyResume(false);
  }

  @Test
  void ignoredRangeRestartsInsteadOfAppendingWrongContent() throws Exception {
    verifyResume(true);
  }

  private void verifyResume(boolean ignoreRange) throws Exception {
    byte[] bytes = new byte[256 * 1024];
    new Random(7).nextBytes(bytes);
    Path reference = temp.resolve("reference");
    Files.write(reference, bytes);
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> range = new AtomicReference<>();
    AtomicReference<String> validator = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/file",
        exchange -> {
          try (exchange) {
            int attempt = requests.incrementAndGet();
            exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().add("ETag", "\"fixture-v1\"");
            if (attempt == 1) {
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes, 0, 65536);
            } else {
              range.set(exchange.getRequestHeaders().getFirst("Range"));
              validator.set(exchange.getRequestHeaders().getFirst("If-Range"));
              int offset = ignoreRange ? 0 : 65536;
              if (!ignoreRange)
                exchange
                    .getResponseHeaders()
                    .add(
                        "Content-Range",
                        "bytes " + offset + "-" + (bytes.length - 1) + "/" + bytes.length);
              exchange.sendResponseHeaders(ignoreRange ? 200 : 206, bytes.length - offset);
              exchange.getResponseBody().write(bytes, offset, bytes.length - offset);
            }
          } catch (IOException ignored) {
            /* Deliberately truncate the first response. */
          }
        });
    server.start();
    try {
      HttpTransport http = new HttpTransport();
      Path target = temp.resolve("downloads/file.jar");
      http.download(
          "http://127.0.0.1:" + server.getAddress().getPort() + "/file",
          target,
          HttpTransport.digest(reference, "SHA-1"),
          new TaskContext() {
            public void report(String message, double progress) {}

            public boolean cancelled() {
              return false;
            }
          });
      assertArrayEquals(bytes, Files.readAllBytes(target));
      assertEquals(2, requests.get());
      assertEquals("bytes=65536-", range.get());
      assertEquals("\"fixture-v1\"", validator.get());
      assertEquals(bytes.length, http.downloadedBytes() - 65536 + (ignoreRange ? 0 : 65536));
      try (var files = Files.list(target.getParent())) {
        assertEquals(1, files.count());
      }
    } finally {
      server.stop(0);
    }
  }
}
