package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.infrastructure.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Game runtimes are independent of the launcher's trimmed, bundled Java runtime. */
public final class JavaRuntimeManager {
  private final HttpTransport http;
  private final Path directory;

  public JavaRuntimeManager(HttpTransport http, Path directory) {
    this.http = http;
    this.directory = directory;
  }

  public int requiredMajor(JsonObject version) {
    if (version.has("javaVersion"))
      return version.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
    String id = JsonFiles.string(version, "_clientVersion", JsonFiles.string(version, "id", ""));
    if (id.startsWith("1.17")) return 16;
    if (id.matches("1\\.(18|19|20)(\\..*)?")) return 17;
    return 8;
  }

  public Path resolve(int major, TaskContext task) throws Exception {
    Path marker = directory.resolve("java-" + major + ".json");
    if (Files.exists(marker)) {
      Path home = Path.of(JsonFiles.read(marker).get("home").getAsString());
      if (major(home) == major && Files.isRegularFile(home.resolve("bin/java.exe")))
        return home.resolve("bin/java.exe");
    }
    LinkedHashSet<Path> homes = new LinkedHashSet<>();
    String env = System.getenv("JAVA_HOME");
    if (env != null) homes.add(Path.of(env));
    Path current = Path.of(System.getProperty("java.home"));
    if (Files.isDirectory(current.resolve("jmods"))) homes.add(current);
    String programs = Objects.toString(System.getenv("ProgramFiles"), "C:/Program Files");
    for (String vendor : List.of("Java", "Zulu", "Eclipse Adoptium", "Microsoft", "BellSoft")) {
      Path parent = Path.of(programs, vendor);
      if (Files.isDirectory(parent))
        try (var children = Files.list(parent)) {
          children.filter(Files::isDirectory).forEach(homes::add);
        }
    }
    for (Path home : homes)
      if (major(home) == major && Files.isRegularFile(home.resolve("bin/java.exe")))
        return home.resolve("bin/java.exe");
    task.report("自动下载游戏 Java " + major, -1);
    String url =
        "https://api.adoptium.net/v3/assets/latest/"
            + major
            + "/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse";
    JsonArray releases = JsonParser.parseString(http.getText(url)).getAsJsonArray();
    if (releases.isEmpty()) throw new IOException("没有可用的 Java " + major + "，请安装对应的 x64 JDK/JRE");
    JsonObject archive =
        releases.get(0).getAsJsonObject().getAsJsonObject("binary").getAsJsonObject("package");
    Files.createDirectories(directory);
    Path zip = directory.resolve("java-" + major + ".zip");
    String expected = archive.get("checksum").getAsString();
    if (!Files.isRegularFile(zip)
        || !HttpTransport.digest(zip, "SHA-256").equalsIgnoreCase(expected))
      http.download(archive.get("link").getAsString(), zip, "", task);
    if (!HttpTransport.digest(zip, "SHA-256").equalsIgnoreCase(expected))
      throw new IOException("Java 安装包 SHA-256 校验失败");
    Path extracted = Files.createTempDirectory(directory, "java-" + major + "-");
    ZipFiles.extract(zip, extracted, false, task);
    try (var children = Files.list(extracted)) {
      for (Path home : children.toList())
        if (major(home) == major && Files.exists(home.resolve("bin/java.exe"))) {
          JsonFiles.write(marker, Map.of("home", home.toAbsolutePath().toString()));
          return home.resolve("bin/java.exe");
        }
    }
    throw new IOException("Java 安装包缺少有效运行环境");
  }

  private static int major(Path home) throws IOException {
    Path release = home.resolve("release");
    if (!Files.isRegularFile(release)) return -1;
    Properties props = new Properties();
    try (Reader reader = Files.newBufferedReader(release)) {
      props.load(reader);
    }
    String version = props.getProperty("JAVA_VERSION", "").replace("\"", "");
    if (version.startsWith("1.")) version = version.substring(2);
    String number = version.split("[.\\-+_]")[0];
    return number.matches("[0-9]+") ? Integer.parseInt(number) : -1;
  }
}
