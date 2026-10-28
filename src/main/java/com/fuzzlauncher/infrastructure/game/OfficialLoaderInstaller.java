package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Runs Forge/NeoForge's own client processors, without importing their implementation. */
public final class OfficialLoaderInstaller implements LoaderInstaller {
  private final LoaderType type;
  private final HttpTransport http;
  private final JavaRuntimeManager runtimes;
  private final VersionResolver resolver;

  public OfficialLoaderInstaller(
      LoaderType type, HttpTransport http, JavaRuntimeManager runtimes, VersionResolver resolver) {
    this.type = type;
    this.http = http;
    this.runtimes = runtimes;
    this.resolver = resolver;
  }

  @Override
  public JsonObject install(
      Path root, Path staging, GameVersion game, String loaderVersion, TaskContext task)
      throws Exception {
    String artifact = type == LoaderType.FORGE || game.id().equals("1.20.1") ? "forge" : "neoforge";
    String base =
        type == LoaderType.FORGE
            ? "https://maven.minecraftforge.net/net/minecraftforge/forge/"
            : "https://maven.neoforged.net/releases/net/neoforged/" + artifact + "/";
    Path installer = staging.resolve("installer.jar");
    String url = base + loaderVersion + "/" + artifact + "-" + loaderVersion + "-installer.jar";
    task.report("下载 " + type + " 安装器", -1);
    String sha1 = http.getText(url + ".sha1").strip().split("\\s+")[0];
    if (!sha1.matches("[0-9a-fA-F]{40}")) throw new IOException("安装器校验信息无效");
    http.download(url, installer, sha1, task);
    JsonObject profile;
    try (ZipFile zip = new ZipFile(installer.toFile())) {
      var entry = zip.getEntry("install_profile.json");
      if (entry == null) throw new IOException("安装器缺少 install_profile.json");

      try (Reader reader =
          new InputStreamReader(
              zip.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8)) {
        profile = JsonParser.parseReader(reader).getAsJsonObject();
      }
      if (profile.has("versionInfo")) return legacy(zip, profile, root, task);
      if (profile.has("minecraft") && !profile.get("minecraft").getAsString().equals(game.id()))
        throw new IOException("加载器与所选 Minecraft 版本不兼容");
    }
    JsonObject vanilla = resolver.resolve(root, game.id());
    Path java = runtimes.resolve(Math.max(17, runtimes.requiredMajor(vanilla)), task);
    JsonObject version =
        new ClientProcessors(http).execute(root, staging, installer, profile, vanilla, java, task);
    Path libraryDirectory = staging.resolve("libraries");
    if (Files.isDirectory(libraryDirectory))
      try (var files = Files.walk(libraryDirectory)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          task.checkCancelled();
          Path destination =
              JsonFiles.safeResolve(
                  root.resolve("libraries"), libraryDirectory.relativize(file).toString());
          Files.createDirectories(destination.getParent());
          // The installation queue is serial; cache files are immutable after publication.
          Path temp = Files.createTempFile(destination.getParent(), ".install-", ".tmp");
          Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING);
          Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    return version;
  }

  private JsonObject legacy(ZipFile zip, JsonObject profile, Path root, TaskContext task)
      throws IOException {
    task.report("安装旧版 Forge 客户端依赖", -1);
    task.checkCancelled();
    JsonObject install = profile.getAsJsonObject("install");
    String coordinate = install.get("path").getAsString();
    String embedded = install.get("filePath").getAsString();
    var entry = zip.getEntry(embedded.startsWith("/") ? embedded.substring(1) : embedded);
    if (entry == null) throw new IOException("旧版安装器缺少内嵌库: " + embedded);
    Path target =
        JsonFiles.safeResolve(root.resolve("libraries"), VersionResolver.mavenPath(coordinate));
    Files.createDirectories(target.getParent());
    try (InputStream input = zip.getInputStream(entry)) {
      Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return profile.getAsJsonObject("versionInfo").deepCopy();
  }
}
