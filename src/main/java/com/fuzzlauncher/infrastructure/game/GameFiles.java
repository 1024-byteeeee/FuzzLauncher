package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.GameVersion;
import com.fuzzlauncher.infrastructure.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Downloads vanilla manifests, libraries, natives and assets using official metadata. */
public final class GameFiles {
  public record Artifact(String url, Path path, String sha1, boolean nativeJar) {}

  private final HttpTransport http;

  public GameFiles(HttpTransport http) {
    this.http = http;
  }

  public void installVanilla(Path root, GameVersion version, TaskContext task) throws Exception {
    task.report("下载版本描述 " + version.id(), -1);
    Path json =
        JsonFiles.safeResolve(
            root.resolve("versions"), version.id() + "/" + version.id() + ".json");
    http.download(version.url(), json, version.sha1(), task);
    JsonObject metadata = JsonFiles.read(json);
    JsonObject client = metadata.getAsJsonObject("downloads").getAsJsonObject("client");
    task.report("下载游戏客户端 " + version.id(), -1);
    http.download(
        client.get("url").getAsString(),
        json.resolveSibling(version.id() + ".jar"),
        JsonFiles.string(client, "sha1", ""),
        task);
    prepare(root, metadata, task);
  }

  public List<Artifact> libraries(Path root, JsonObject version) throws IOException {
    List<Artifact> result = new ArrayList<>();
    if (!version.has("libraries")) return result;
    for (JsonElement element : version.getAsJsonArray("libraries")) {
      JsonObject library = element.getAsJsonObject();
      if (!VersionResolver.allowed(library)) continue;
      String coordinate = library.get("name").getAsString();
      if (library.has("downloads")) {
        JsonObject downloads = library.getAsJsonObject("downloads");
        if (downloads.has("artifact"))
          add(
              result,
              root,
              downloads.getAsJsonObject("artifact"),
              coordinate.contains(":natives-windows"));
        if (library.has("natives") && library.getAsJsonObject("natives").has("windows")) {
          String classifier =
              library
                  .getAsJsonObject("natives")
                  .get("windows")
                  .getAsString()
                  .replace("${arch}", "64");
          if (downloads.has("classifiers")
              && downloads.getAsJsonObject("classifiers").has(classifier))
            add(
                result,
                root,
                downloads.getAsJsonObject("classifiers").getAsJsonObject(classifier),
                true);
        }
      } else {
        String path = VersionResolver.mavenPath(coordinate);
        String base = JsonFiles.string(library, "url", "https://libraries.minecraft.net/");
        Path destination = JsonFiles.safeResolve(root.resolve("libraries"), path);
        if (base.isBlank()) {
          if (!Files.isRegularFile(destination)) throw new IOException("安装器未生成依赖: " + coordinate);
          continue;
        }
        result.add(
            new Artifact(
                base + (base.endsWith("/") ? "" : "/") + path,
                destination,
                JsonFiles.string(library, "sha1", ""),
                false));
      }
    }
    return result;
  }

  private static void add(List<Artifact> result, Path root, JsonObject download, boolean natives)
      throws IOException {
    String url = JsonFiles.string(download, "url", "");
    Path destination =
        JsonFiles.safeResolve(root.resolve("libraries"), download.get("path").getAsString());
    if (url.isBlank()) {
      if (!Files.exists(destination))
        throw new IOException("缺少安装器生成文件: " + destination.getFileName());
    } else
      result.add(new Artifact(url, destination, JsonFiles.string(download, "sha1", ""), natives));
  }

  public void prepare(Path root, JsonObject version, TaskContext task) throws Exception {
    if (version.has("logging") && version.getAsJsonObject("logging").has("client")) {
      JsonObject config =
          version.getAsJsonObject("logging").getAsJsonObject("client").getAsJsonObject("file");
      http.download(
          config.get("url").getAsString(),
          JsonFiles.safeResolve(root.resolve("assets/log_configs"), config.get("id").getAsString()),
          JsonFiles.string(config, "sha1", ""),
          task);
    }
    if (version.has("_clientVersion")
        && version.has("downloads")
        && version.getAsJsonObject("downloads").has("client")) {
      String clientVersion = version.get("_clientVersion").getAsString();
      JsonObject client = version.getAsJsonObject("downloads").getAsJsonObject("client");
      http.download(
          client.get("url").getAsString(),
          JsonFiles.safeResolve(
              root.resolve("versions"), clientVersion + "/" + clientVersion + ".jar"),
          JsonFiles.string(client, "sha1", ""),
          task);
    }
    downloadAll(libraries(root, version), "依赖库", task);
    if (!version.has("assetIndex")) return;
    JsonObject index = version.getAsJsonObject("assetIndex");
    String id = index.get("id").getAsString();
    Path indexFile = JsonFiles.safeResolve(root.resolve("assets/indexes"), id + ".json");
    http.download(
        index.get("url").getAsString(), indexFile, JsonFiles.string(index, "sha1", ""), task);
    JsonObject assetIndex = JsonFiles.read(indexFile);
    List<Artifact> assets = new ArrayList<>();
    for (var entry : assetIndex.getAsJsonObject("objects").entrySet()) {
      String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
      if (!hash.matches("[0-9a-f]{40}")) throw new IOException("资源哈希格式无效");
      String path = hash.substring(0, 2) + "/" + hash;
      assets.add(
          new Artifact(
              "https://resources.download.minecraft.net/" + path,
              root.resolve("assets/objects").resolve(path),
              hash,
              false));
    }
    downloadAll(assets, "游戏资源", task);
    if (assetIndex.has("virtual") && assetIndex.get("virtual").getAsBoolean())
      materializeAssets(root, assetIndex, root.resolve("assets/virtual").resolve(id), task);
  }

  public void legacyResources(Path root, Path gameDir, JsonObject version, TaskContext task)
      throws IOException {
    if (!version.has("assetIndex")) return;
    String id = version.getAsJsonObject("assetIndex").get("id").getAsString();
    JsonObject index = JsonFiles.read(root.resolve("assets/indexes").resolve(id + ".json"));
    if (index.has("map_to_resources") && index.get("map_to_resources").getAsBoolean())
      materializeAssets(root, index, gameDir.resolve("resources"), task);
  }

  private static void materializeAssets(Path root, JsonObject index, Path target, TaskContext task)
      throws IOException {
    for (var entry : index.getAsJsonObject("objects").entrySet()) {
      task.checkCancelled();
      String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
      Path dest = JsonFiles.safeResolve(target, entry.getKey());
      Files.createDirectories(dest.getParent());
      Files.copy(
          root.resolve("assets/objects").resolve(hash.substring(0, 2)).resolve(hash),
          dest,
          StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void downloadAll(List<Artifact> artifacts, String label, TaskContext task)
      throws Exception {
    AtomicInteger completed = new AtomicInteger();
    try (ExecutorService workers = Executors.newFixedThreadPool(6)) {
      List<Future<?>> futures = new ArrayList<>();
      try {
        for (Artifact artifact : artifacts)
          futures.add(
              workers.submit(
                  () -> {
                    task.checkCancelled();
                    // Generated Forge libraries may not have a download URL or checksum.
                    if (!(artifact.sha1().isBlank() && Files.isRegularFile(artifact.path())))
                      http.download(artifact.url(), artifact.path(), artifact.sha1(), task);
                    int count = completed.incrementAndGet();
                    task.report(
                        label + " " + count + "/" + artifacts.size(),
                        (double) count / Math.max(1, artifacts.size()));
                    return null;
                  }));
        for (Future<?> future : futures) future.get();
      } catch (Exception ex) {
        futures.forEach(f -> f.cancel(true));
        workers.shutdownNow();
        if (ex instanceof ExecutionException execution
            && execution.getCause() instanceof Exception cause) throw cause;
        throw ex;
      }
    }
  }
}
