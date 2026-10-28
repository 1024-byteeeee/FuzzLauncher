package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.application.TaskContext;
import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

public final class HttpTransport {
  private final HttpClient client =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NORMAL)
          .connectTimeout(Duration.ofSeconds(20))
          .build();

  public JsonObject getJson(String url) throws IOException, InterruptedException {
    return json(HttpRequest.newBuilder(URI.create(url)).GET().build());
  }

  public JsonObject authorizedGet(String url, String token)
      throws IOException, InterruptedException {
    return json(
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build());
  }

  public JsonObject postJson(String url, JsonObject body) throws IOException, InterruptedException {
    return json(
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build());
  }

  private JsonObject json(HttpRequest request) throws IOException, InterruptedException {
    HttpRequest bounded =
        HttpRequest.newBuilder(request, (name, value) -> true)
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", "FuzzLauncher/0.2.0")
            .build();
    HttpResponse<String> response = client.send(bounded, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2)
      throw new IOException(
          "服务请求失败 HTTP " + response.statusCode() + " (" + request.uri().getHost() + ")");
    try {
      return JsonParser.parseString(response.body()).getAsJsonObject();
    } catch (JsonParseException | IllegalStateException ex) {
      throw new IOException("服务返回无效 JSON: " + request.uri().getHost(), ex);
    }
  }

  public String getText(String url) throws IOException, InterruptedException {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2)
      throw new IOException("元数据请求失败 HTTP " + response.statusCode());
    return response.body();
  }

  public void download(String url, Path destination, String sha1, TaskContext task)
      throws IOException, InterruptedException {
    task.checkCancelled();
    if (Files.isRegularFile(destination)
        && sha1 != null
        && !sha1.isBlank()
        && digest(destination, "SHA-1").equalsIgnoreCase(sha1)) return;
    Files.createDirectories(destination.toAbsolutePath().getParent());
    IOException failure = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      task.checkCancelled();
      Path partial =
          Files.createTempFile(destination.toAbsolutePath().getParent(), ".download-", ".part");
      try {
        HttpResponse<InputStream> response =
            client.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "FuzzLauncher/0.2.0")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
          if (response.statusCode() / 100 != 2)
            throw new IOException(
                "下载失败 HTTP " + response.statusCode() + ": " + URI.create(url).getHost());
          try (OutputStream output = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
              task.checkCancelled();
              output.write(buffer, 0, count);
            }
          }
        }
        if (sha1 != null && !sha1.isBlank() && !digest(partial, "SHA-1").equalsIgnoreCase(sha1))
          throw new IOException("文件 SHA-1 校验失败: " + destination.getFileName());
        try {
          Files.move(
              partial,
              destination,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
          Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return;
      } catch (IOException ex) {
        failure = ex;
      } finally {
        Files.deleteIfExists(partial);
      }
    }
    throw Objects.requireNonNull(failure);
  }

  public static String digest(Path file, String algorithm) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance(algorithm);
      try (InputStream input = Files.newInputStream(file)) {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalArgumentException(algorithm, ex);
    }
  }
}
