package com.fuzzlauncher.application;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class LaunchGame {
  public record RunningGame(Process process, Path log) {}

  private final AccountAuthenticator authentication;
  private final IsolationPolicy isolation;
  private final VersionResolver resolver;
  private final GameFiles files;
  private final JavaRuntimeManager runtimes;
  private final LaunchArguments arguments;
  private final Path logs;

  public LaunchGame(
      AccountAuthenticator authentication,
      IsolationPolicy isolation,
      VersionResolver resolver,
      GameFiles files,
      JavaRuntimeManager runtimes,
      LaunchArguments arguments,
      Path logs) {
    this.authentication = authentication;
    this.isolation = isolation;
    this.resolver = resolver;
    this.files = files;
    this.runtimes = runtimes;
    this.arguments = arguments;
    this.logs = logs;
  }

  public RunningGame execute(
      LauncherSettings settings, GameInstance instance, Account account, TaskContext task)
      throws Exception {
    if (account == null) throw new IOException("请先在账号页登录微软账号或创建离线账号");
    GameSession session =
        account.isOffline() ? GameSession.offline(account) : authentication.refresh(account, task);
    Path root = Path.of(settings.gameRoot()).toAbsolutePath().normalize();
    JsonObject version = resolver.resolveInstance(root, instance);
    Path java =
        instance.javaPath().isBlank()
            ? null
            : runtimes.resolve(runtimes.requiredMajor(version), instance.javaPath(), task);
    files.prepare(root, version, task);
    Path gameDir = isolation.gameDirectory(root, instance, settings.isolation());
    Files.createDirectories(gameDir);
    Path natives =
        Files.createTempDirectory(
            Files.createDirectories(root.resolve(".fuzz-natives")), "launch-");
    List<Path> classpath = new ArrayList<>();
    for (GameFiles.Artifact artifact : files.libraries(root, version)) {
      if (artifact.nativeJar()) ZipFiles.extract(artifact.path(), natives, true, task);
    }
    for (JsonElement item : version.getAsJsonArray("libraries")) {
      JsonObject library = item.getAsJsonObject();
      if (!VersionResolver.allowed(library)) continue;
      if (library.has("downloads")) {
        JsonObject downloads = library.getAsJsonObject("downloads");
        if (downloads.has("artifact"))
          classpath.add(
              JsonFiles.safeResolve(
                  root.resolve("libraries"),
                  downloads.getAsJsonObject("artifact").get("path").getAsString()));
      } else
        classpath.add(
            JsonFiles.safeResolve(
                root.resolve("libraries"),
                VersionResolver.mavenPath(library.get("name").getAsString())));
    }
    files.legacyResources(root, gameDir, version, task);
    if (java == null) java = runtimes.resolve(runtimes.requiredMajor(version), task);
    if (!account.isOffline() && !session.expiresAt().isAfter(Instant.now().plusSeconds(60)))
      session = authentication.refresh(account, task);
    List<String> command = new ArrayList<>();
    command.add(java.toString());
    command.addAll(arguments.build(root, gameDir, natives, instance, session, version, classpath));
    task.checkCancelled();
    Files.createDirectories(logs);
    Path log = logs.resolve(instance.id() + "-" + System.currentTimeMillis() + ".log");
    task.report("正在启动 " + instance.name(), -1);
    Process process =
        new ProcessBuilder(command).directory(gameDir.toFile()).redirectErrorStream(true).start();
    String token = session.accessToken();
    boolean redactToken = !account.isOffline();
    Thread.ofVirtual()
        .name("game-log-" + process.pid())
        .start(
            () -> {
              try (BufferedReader input = process.inputReader(StandardCharsets.UTF_8);
                  BufferedWriter output = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
                String line;
                while ((line = input.readLine()) != null) {
                  output.write(redactToken ? line.replace(token, "[REDACTED]") : line);
                  output.newLine();
                  output.flush();
                }
              } catch (IOException ex) {
                System.err.println("游戏日志写入失败: " + ex.getClass().getSimpleName());
              }
            });
    task.report("游戏已启动", 1);
    return new RunningGame(process, log);
  }
}
