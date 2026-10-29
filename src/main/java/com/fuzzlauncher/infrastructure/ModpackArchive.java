package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.*;
import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Format metadata and bounded ZIP I/O. Imported launcher commands are never executed. */
public final class ModpackArchive {
  public static final String STATE = ".fuzz-modpack.json";
  private static final long MAX_EXPANDED = 16L * 1024 * 1024 * 1024;

  public record Info(
      ModpackFormat format,
      String name,
      String version,
      String author,
      String description,
      String game,
      LoaderType loader,
      String loaderVersion,
      JsonObject manifest,
      String prefix) {}

  private ModpackArchive() {}

  public record ContentEntry(String path, boolean directory, long size) {}

  public static List<ContentEntry> scanExport(Path source, GameInstance instance, TaskContext task)
      throws IOException {
    Path root = source.toAbsolutePath().normalize();
    List<ContentEntry> result = new ArrayList<>();
    if (!Files.isDirectory(root)) return result;
    Path realRoot = root.toRealPath();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          private String relative(Path path) {
            return root.relativize(path).toString().replace('\\', '/');
          }

          private boolean include(Path path) throws IOException {
            task.checkCancelled();
            String name = relative(path);
            return allowedContent(name)
                && !Files.isSymbolicLink(path)
                && path.toRealPath().startsWith(realRoot);
          }

          private void add(ContentEntry entry) throws IOException {
            if (result.size() >= 100_000) throw new IOException("可导出文件超过 100000 项，请先清理或移动过大的目录");
            result.add(entry);
          }

          @Override
          public FileVisitResult preVisitDirectory(
              Path path, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
            if (path.equals(root)) return FileVisitResult.CONTINUE;
            if (!include(path)) return FileVisitResult.SKIP_SUBTREE;
            add(new ContentEntry(relative(path), true, 0));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(
              Path path, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
            if (attrs.isRegularFile() && include(path))
              add(new ContentEntry(relative(path), false, attrs.size()));
            return FileVisitResult.CONTINUE;
          }
        });
    return List.copyOf(result);
  }

  public static List<ContentEntry> optionalFiles(Info info) throws IOException {
    if (info.format() != ModpackFormat.MODRINTH) return List.of();
    List<ContentEntry> result = new ArrayList<>();
    for (JsonElement element : array(info.manifest(), "files")) {
      JsonObject file = element.getAsJsonObject();
      if (file.has("env") && "optional".equals(string(object(file, "env"), "client", "required"))) {
        String path = string(file, "path", "");
        validateContent(path);
        result.add(
            new ContentEntry(
                path,
                false,
                file.has("fileSize") ? Math.max(0, file.get("fileSize").getAsLong()) : 0));
      }
    }
    return List.copyOf(result);
  }

  public static Info inspect(Path archive) throws IOException {
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      validateEntries(zip);
      List<String> matches =
          zip.stream()
              .filter(e -> !e.isDirectory())
              .map(ZipEntry::getName)
              .filter(
                  n ->
                      Set.of(
                              "mcbbs.packmeta",
                              "modrinth.index.json",
                              "server-manifest.json",
                              "mmc-pack.json")
                          .contains(n.substring(n.lastIndexOf('/') + 1)))
              .toList();
      int depth =
          matches.stream()
              .mapToInt(n -> (int) n.chars().filter(c -> c == '/').count())
              .min()
              .orElse(-1);
      matches =
          matches.stream().filter(n -> n.chars().filter(c -> c == '/').count() == depth).toList();
      if (matches.size() != 1) throw new IOException("未找到唯一的整合包清单，支持 MCBBS、MultiMC、服务器包和 Modrinth");
      String entry = matches.getFirst();
      String prefix = entry.substring(0, entry.lastIndexOf('/') + 1);
      ModpackFormat format =
          entry.endsWith("mcbbs.packmeta")
              ? ModpackFormat.MCBBS
              : entry.endsWith("mmc-pack.json")
                  ? ModpackFormat.MULTIMC
                  : entry.endsWith("server-manifest.json")
                      ? ModpackFormat.SERVER
                      : ModpackFormat.MODRINTH;
      JsonObject manifest = json(zip, entry);
      Info info = parse(format, manifest, prefix);
      if (format == ModpackFormat.MULTIMC) {
        ZipEntry config = zip.getEntry(prefix + "instance.cfg");
        String name = info.name();
        if (config != null) {
          Properties properties = new Properties();
          properties.load(new StringReader(text(zip, config)));
          name = properties.getProperty("name", name);
        }
        info =
            new Info(
                format,
                name,
                info.version(),
                info.author(),
                info.description(),
                info.game(),
                info.loader(),
                info.loaderVersion(),
                manifest,
                prefix);
      }
      return info;
    } catch (JsonParseException | IllegalArgumentException | IllegalStateException ex) {
      throw new IOException("整合包清单格式无效：" + ex.getMessage(), ex);
    }
  }

  public static Info parse(ModpackFormat format, JsonObject manifest, String prefix)
      throws IOException {
    Map<String, String> components = new LinkedHashMap<>();
    if (format == ModpackFormat.MODRINTH) {
      if (!"minecraft".equals(string(manifest, "game", ""))
          || integer(manifest, "formatVersion", 0) != 1)
        throw new IOException("不支持此 Modrinth 游戏或格式版本");
      object(manifest, "dependencies")
          .entrySet()
          .forEach(e -> components.put(e.getKey(), e.getValue().getAsString()));
    } else {
      if (format == ModpackFormat.MCBBS
          && (!"minecraftModpack".equals(string(manifest, "manifestType", ""))
              || integer(manifest, "manifestVersion", 0) > 2))
        throw new IOException("不支持此 MCBBS 格式版本");
      if (format == ModpackFormat.MULTIMC && integer(manifest, "formatVersion", 0) != 1)
        throw new IOException("不支持此 MultiMC 格式版本");
      for (JsonElement element :
          array(manifest, format == ModpackFormat.MULTIMC ? "components" : "addons")) {
        JsonObject component = element.getAsJsonObject();
        String id = string(component, format == ModpackFormat.MULTIMC ? "uid" : "id", "");
        String version = string(component, "version", string(component, "cachedVersion", ""));
        if (components.putIfAbsent(id, version) != null) throw new IOException("重复的游戏组件：" + id);
      }
    }
    String game = null, loaderVersion = null;
    LoaderType loader = LoaderType.VANILLA;
    for (var component : components.entrySet()) {
      String id = component.getKey();
      if (Set.of("game", "minecraft", "net.minecraft").contains(id)) game = component.getValue();
      else if (Set.of(
              "fabric",
              "fabric-loader",
              "net.fabricmc.fabric-loader",
              "forge",
              "net.minecraftforge",
              "neoforge",
              "net.neoforged")
          .contains(id)) {
        if (loader != LoaderType.VANILLA) throw new IOException("暂不支持一个整合包包含多个加载器");
        loader =
            id.contains("neoforge") || id.equals("net.neoforged")
                ? LoaderType.NEOFORGE
                : id.contains("forge") ? LoaderType.FORGE : LoaderType.FABRIC;
        loaderVersion = component.getValue();
      } else if (!Set.of("org.lwjgl", "org.lwjgl3", "net.fabricmc.intermediary").contains(id)) {
        throw new IOException("暂不支持整合包组件：" + id);
      }
    }
    if (game == null || game.isBlank()) throw new IOException("整合包没有声明 Minecraft 版本");
    if (loader != LoaderType.VANILLA && (loaderVersion == null || loaderVersion.isBlank()))
      throw new IOException("整合包缺少加载器版本");
    if (format == ModpackFormat.SERVER) baseUrl(string(manifest, "fileApi", ""));
    return new Info(
        format,
        string(manifest, "name", "导入的整合包"),
        string(manifest, "versionId", string(manifest, "version", "1.0.0")),
        string(manifest, "author", ""),
        string(manifest, "summary", string(manifest, "description", "")),
        game,
        loader,
        loaderVersion,
        manifest.deepCopy(),
        prefix);
  }

  public static void extract(
      Path archive, Info info, Path content, boolean optional, HttpTransport http, TaskContext task)
      throws Exception {
    extract(archive, info, content, optional, Set.of(), http, task);
  }

  public static void extract(
      Path archive,
      Info info,
      Path content,
      boolean optional,
      Set<String> excludedOptional,
      HttpTransport http,
      TaskContext task)
      throws Exception {
    Files.createDirectories(content);
    if (info.format() == ModpackFormat.MODRINTH) {
      Set<String> paths = new HashSet<>();
      for (JsonElement element : array(info.manifest(), "files")) {
        task.checkCancelled();
        JsonObject file = element.getAsJsonObject();
        String path = string(file, "path", "");
        validateContent(path);
        if (!paths.add(path.toLowerCase(Locale.ROOT))) throw new IOException("重复的整合包文件：" + path);
        String client =
            file.has("env") ? string(object(file, "env"), "client", "required") : "required";
        if (client.equals("unsupported")
            || client.equals("optional") && (!optional || excludedOptional.contains(path)))
          continue;
        if (!Set.of("required", "optional").contains(client))
          throw new IOException("无效的客户端环境：" + client);
        JsonObject hashes = object(file, "hashes");
        long size = file.get("fileSize").getAsLong();
        if (size < 0 || size > MAX_EXPANDED) throw new IOException("整合包下载文件大小无效：" + path);
        hashValue(string(hashes, "sha1", ""), 40);
        hashValue(string(hashes, "sha512", ""), 128);
        Path target = JsonFiles.safeResolve(content, path);
        Exception failure = null;
        boolean downloaded = false;
        for (JsonElement url : array(file, "downloads")) {
          try {
            download(http, url.getAsString(), target, string(hashes, "sha1", ""), task);
            verify(target, string(hashes, "sha512", ""), "SHA-512");
            if (Files.size(target) != file.get("fileSize").getAsLong())
              throw new IOException("下载文件大小不匹配：" + path);
            downloaded = true;
            break;
          } catch (IOException ex) {
            failure = ex;
            Files.deleteIfExists(target);
          }
        }
        if (!downloaded) throw new IOException("无法下载整合包文件：" + path, failure);
      }
    }
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      validateEntries(zip);
      if (info.format() == ModpackFormat.MULTIMC) {
        boolean dot =
            zip.stream().anyMatch(e -> e.getName().startsWith(info.prefix() + ".minecraft/"));
        copyLayer(zip, info.prefix() + (dot ? ".minecraft/" : "minecraft/"), content, task);
        if (zip.stream()
            .anyMatch(e -> e.getName().startsWith(info.prefix() + "patches/") && !e.isDirectory()))
          throw new IOException("此 MultiMC 包包含自定义补丁，当前仅支持标准游戏与加载器组件");
      } else {
        copyLayer(zip, info.prefix() + "overrides/", content, task);
        if (info.format() == ModpackFormat.MODRINTH)
          copyLayer(zip, info.prefix() + "client-overrides/", content, task);
      }
    }
    if (info.format() == ModpackFormat.MODRINTH) {
      // A declined optional file must not be reintroduced by an embedded override layer.
      for (ContentEntry entry : optionalFiles(info)) {
        task.checkCancelled();
        if (!optional || excludedOptional.contains(entry.path()))
          Files.deleteIfExists(JsonFiles.safeResolve(content, entry.path()));
      }
    }
    if (info.format() == ModpackFormat.MCBBS || info.format() == ModpackFormat.SERVER) {
      if (info.manifest().has("libraries") && !array(info.manifest(), "libraries").isEmpty())
        throw new IOException("此 MCBBS 包包含自定义启动库，当前不支持");
      Set<String> paths = new HashSet<>();
      for (JsonElement element : array(info.manifest(), "files")) {
        task.checkCancelled();
        JsonObject file = element.getAsJsonObject();
        boolean curse = string(file, "type", "addon").equals("curse");
        String path =
            curse
                ? "mods/"
                    + string(
                        file,
                        "fileName",
                        string(file, "projectID", "mod")
                            + "-"
                            + string(file, "fileID", "file")
                            + ".jar")
                : string(file, "path", "");
        validateContent(path);
        if (!paths.add(path.toLowerCase(Locale.ROOT))) throw new IOException("重复的整合包文件：" + path);
        Path target = JsonFiles.safeResolve(content, path);
        String hash = curse ? null : string(file, "hash", "");
        if (hash != null) hashValue(hash, 40);
        if (!Files.isRegularFile(target)) {
          String url;
          if (curse)
            url =
                string(
                    file,
                    "url",
                    "https://www.curseforge.com/minecraft/mc-mods/"
                        + string(file, "projectID", "")
                        + "/download/"
                        + string(file, "fileID", "")
                        + "/file");
          else if (info.format() == ModpackFormat.SERVER)
            url = fileUrl(baseUrl(string(info.manifest(), "fileApi", "")), path);
          else throw new IOException("整合包缺少清单中的文件：" + path);
          download(http, url, target, hash, task, true);
        }
        if (hash != null) verify(target, hash, "SHA-1");
      }
    }
    JsonFiles.write(
        content.resolve(STATE),
        Map.of("format", info.format().name(), "manifest", info.manifest()));
  }

  public static List<String> exportEntries(Path source) throws IOException {
    if (!Files.isDirectory(source)) return List.of();
    try (var children = Files.list(source)) {
      return children
          .filter(p -> !Files.isSymbolicLink(p) && allowedContent(p.getFileName().toString()))
          .map(p -> p.getFileName().toString())
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .toList();
    }
  }

  public static void export(
      Path source,
      GameInstance instance,
      ModpackFormat format,
      String name,
      String version,
      String author,
      String description,
      String fileApi,
      Set<String> selected,
      Path destination,
      boolean replace,
      TaskContext task)
      throws Exception {
    source = source.toAbsolutePath().normalize();
    destination = destination.toAbsolutePath().normalize();
    if (destination.startsWith(source)) throw new IOException("导出文件不能保存在被导出的游戏目录中");
    if (name.isBlank() || version.isBlank()) throw new IOException("请填写整合包名称和版本");
    if (Files.exists(destination) && !replace) throw new IOException("目标文件已存在");
    if (format == ModpackFormat.SERVER) fileApi = baseUrl(fileApi);
    Set<Path> files = new LinkedHashSet<>();
    Set<Path> directories = new LinkedHashSet<>();
    for (String entry : selected) {
      validateContent(entry);
      Path child = JsonFiles.safeResolve(source, entry);
      for (Path parent = child;
          parent != null && parent.startsWith(source);
          parent = parent.getParent())
        if (Files.isSymbolicLink(parent)) throw new IOException("不能导出目录链接：" + entry);
      try (var walk = Files.walk(child)) {
        for (Path path : walk.toList()) {
          task.checkCancelled();
          if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(source.toRealPath()))
            throw new IOException("不能导出指向其他目录的链接：" + path.getFileName());
          if (Files.isRegularFile(path)) {
            String relative = source.relativize(path).toString().replace('\\', '/');
            if (allowedContent(relative)) files.add(path);
          } else if (Files.isDirectory(path)) {
            String relative = source.relativize(path).toString().replace('\\', '/');
            if (allowedContent(relative)) directories.add(path);
          }
        }
      }
    }
    Files.createDirectories(destination.getParent());
    Path temporary = Files.createTempFile(destination.getParent(), ".fuzz-pack-", ".tmp");
    try {
      JsonObject manifest = new JsonObject();
      manifest.addProperty("name", name);
      manifest.addProperty("version", version);
      manifest.addProperty("author", author);
      manifest.addProperty("description", description);
      JsonArray index = new JsonArray();
      try (ZipOutputStream zip =
          new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
        int count = 0;
        for (Path folder : directories) {
          task.checkCancelled();
          String relative = source.relativize(folder).toString().replace('\\', '/');
          zip.putNextEntry(
              new ZipEntry(
                  (format == ModpackFormat.MULTIMC ? ".minecraft/" : "overrides/")
                      + relative
                      + "/"));
          zip.closeEntry();
        }
        for (Path file : files) {
          task.checkCancelled();
          String relative = source.relativize(file).toString().replace('\\', '/');
          task.report("导出 " + relative, files.isEmpty() ? 1 : (double) count++ / files.size());
          zip.putNextEntry(
              new ZipEntry(
                  (format == ModpackFormat.MULTIMC ? ".minecraft/" : "overrides/") + relative));
          try (InputStream input = Files.newInputStream(file)) {
            copy(input, zip, task, MAX_EXPANDED);
          }
          zip.closeEntry();
          JsonObject item = new JsonObject();
          item.addProperty("path", relative);
          item.addProperty("hash", digest(file, "SHA-1"));
          if (format == ModpackFormat.MCBBS) {
            item.addProperty("type", "addon");
            item.addProperty("force", false);
          }
          index.add(item);
        }
        switch (format) {
          case MCBBS -> {
            manifest.addProperty("manifestType", "minecraftModpack");
            manifest.addProperty("manifestVersion", 2);
            manifest.add("addons", addons(instance, false));
            manifest.add("files", index);
            manifest.add("libraries", new JsonArray());
            manifest.add("origins", new JsonArray());
            jsonEntry(zip, "mcbbs.packmeta", manifest);
          }
          case SERVER -> {
            manifest.addProperty("fileApi", fileApi);
            manifest.add("addons", addons(instance, false));
            manifest.add("files", index);
            jsonEntry(zip, "server-manifest.json", manifest);
          }
          case MULTIMC -> {
            JsonObject mmc = new JsonObject();
            mmc.addProperty("formatVersion", 1);
            mmc.add("components", addons(instance, true));
            jsonEntry(zip, "mmc-pack.json", mmc);
            entry(
                zip,
                "instance.cfg",
                "[General]\nInstanceType=OneSix\nname="
                    + name.replace("\n", " ").replace("\r", " ")
                    + "\n");
          }
          case MODRINTH -> {
            manifest = new JsonObject();
            manifest.addProperty("formatVersion", 1);
            manifest.addProperty("game", "minecraft");
            manifest.addProperty("versionId", version);
            manifest.addProperty("name", name);
            manifest.addProperty("summary", description);
            manifest.add("files", new JsonArray());
            JsonObject deps = new JsonObject();
            deps.addProperty("minecraft", instance.gameVersion());
            if (instance.loader() != LoaderType.VANILLA)
              deps.addProperty(
                  instance.loader() == LoaderType.FABRIC
                      ? "fabric-loader"
                      : instance.loader().name().toLowerCase(Locale.ROOT),
                  portableLoader(instance));
            manifest.add("dependencies", deps);
            jsonEntry(zip, "modrinth.index.json", manifest);
          }
        }
      }
      Path output = destination;
      task.commit(
          () -> {
            if (replace) Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, output);
          });
      task.report("整合包已导出：" + destination, 1);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static String portableLoader(GameInstance instance) {
    String value = instance.loaderVersion();
    return value != null && value.startsWith(instance.gameVersion() + "-")
        ? value.substring(instance.gameVersion().length() + 1)
        : value;
  }

  private static JsonArray addons(GameInstance instance, boolean mmc) {
    JsonArray result = new JsonArray();
    JsonObject game = new JsonObject();
    game.addProperty(mmc ? "uid" : "id", mmc ? "net.minecraft" : "game");
    game.addProperty("version", instance.gameVersion());
    if (mmc) game.addProperty("important", true);
    result.add(game);
    if (instance.loader() != LoaderType.VANILLA) {
      JsonObject loader = new JsonObject();
      String id =
          switch (instance.loader()) {
            case FABRIC -> mmc ? "net.fabricmc.fabric-loader" : "fabric";
            case FORGE -> mmc ? "net.minecraftforge" : "forge";
            case NEOFORGE -> mmc ? "net.neoforged" : "neoforge";
            default -> throw new IllegalStateException();
          };
      loader.addProperty(mmc ? "uid" : "id", id);
      loader.addProperty("version", portableLoader(instance));
      result.add(loader);
    }
    return result;
  }

  private static void copyLayer(ZipFile zip, String prefix, Path target, TaskContext task)
      throws IOException {
    long total = 0;
    for (ZipEntry entry :
        zip.stream()
            .filter(e -> e.getName().startsWith(prefix) && !e.getName().equals(prefix))
            .toList()) {
      String name = entry.getName().substring(prefix.length());
      if (entry.isDirectory() && name.endsWith("/")) name = name.substring(0, name.length() - 1);
      validateContent(name);
      Path destination = JsonFiles.safeResolve(target, name);
      if (entry.isDirectory()) {
        task.checkCancelled();
        Files.createDirectories(destination);
        continue;
      }
      Files.createDirectories(destination.getParent());
      task.report("解压 " + name, -1);
      try (InputStream input = zip.getInputStream(entry);
          OutputStream output = Files.newOutputStream(destination)) {
        total += copy(input, output, task, MAX_EXPANDED - total);
      }
    }
  }

  private static long copy(InputStream input, OutputStream output, TaskContext task, long limit)
      throws IOException {
    long total = 0;
    byte[] buffer = new byte[128 * 1024];
    int count;
    while ((count = input.read(buffer)) != -1) {
      task.checkCancelled();
      total += count;
      if (total > limit) throw new IOException("整合包展开大小超过 16 GiB 限制");
      output.write(buffer, 0, count);
    }
    return total;
  }

  private static void validateEntries(ZipFile zip) throws IOException {
    Set<String> names = new HashSet<>();
    long size = 0;
    if (zip.size() > 100_000) throw new IOException("整合包文件数量过多");
    for (ZipEntry entry : zip.stream().toList()) {
      String name = entry.getName();
      validatePath(
          entry.isDirectory() && name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
      if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("整合包包含重复路径：" + name);
      if (entry.getSize() > MAX_EXPANDED - size) throw new IOException("整合包展开大小超过 16 GiB 限制");
      if (entry.getSize() > 0) size += entry.getSize();
      if (size > MAX_EXPANDED) throw new IOException("整合包展开大小超过 16 GiB 限制");
    }
  }

  public static void validatePath(String path) throws IOException {
    if (path == null
        || path.length() > 32760
        || path.chars().filter(c -> c == '/').count() >= 128
        || path.isBlank()
        || path.startsWith("/")
        || path.contains("\\")
        || path.contains(":")) throw new IOException("整合包路径无效：" + path);
    for (String part : path.split("/", -1))
      if (part.isBlank()
          || part.equals(".")
          || part.equals("..")
          || part.endsWith(".")
          || part.endsWith(" ")
          || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")
          || part.matches(".*[<>\"|?*\\p{Cntrl}].*")) throw new IOException("整合包路径无效：" + path);
  }

  public static boolean allowedContent(String path) {
    try {
      validatePath(path);
    } catch (IOException ex) {
      return false;
    }
    return true;
  }

  public static void validateContent(String path) throws IOException {
    validatePath(path);
  }

  public static String baseUrl(String url) throws IOException {
    url = url.strip().replaceAll("/+$", "");
    try {
      URI uri = URI.create(url);
      if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) throw new IllegalArgumentException();
      return url;
    } catch (IllegalArgumentException ex) {
      throw new IOException("服务器文件地址应为 HTTP 或 HTTPS 基础网址，不含查询参数", ex);
    }
  }

  public static String fileUrl(String base, String path) throws IOException {
    validateContent(path);
    try {
      return base + "/overrides/" + new URI(null, null, "/" + path, null).getRawPath().substring(1);
    } catch (java.net.URISyntaxException ex) {
      throw new IOException("无效的文件下载路径", ex);
    }
  }

  private static void download(
      HttpTransport http, String url, Path target, String sha1, TaskContext task) throws Exception {
    download(http, url, target, sha1, task, false);
  }

  private static void download(
      HttpTransport http,
      String url,
      Path target,
      String sha1,
      TaskContext task,
      boolean legacyHttp)
      throws Exception {
    URI uri = URI.create(url);
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || !(uri.getScheme().equalsIgnoreCase("https")
            || uri.getScheme().equalsIgnoreCase("http")
                && (legacyHttp
                    || Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()))))
      throw new IOException("整合包下载地址必须使用 HTTPS");
    http.download(url, target, sha1, task);
  }

  public static String digest(Path path, String algorithm) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance(algorithm);
      try (InputStream input = Files.newInputStream(path)) {
        byte[] buffer = new byte[128 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static void verify(Path path, String expected, String algorithm) throws IOException {
    if (!Files.isRegularFile(path) || !digest(path, algorithm).equalsIgnoreCase(expected))
      throw new IOException("整合包文件校验失败：" + path.getFileName());
  }

  public static void hashValue(String value, int length) throws IOException {
    if (value == null || !value.matches("[a-fA-F0-9]{" + length + "}"))
      throw new IOException("整合包文件哈希无效");
  }

  private static JsonObject json(ZipFile zip, String entry) throws IOException {
    return JsonParser.parseString(text(zip, zip.getEntry(entry))).getAsJsonObject();
  }

  private static String text(ZipFile zip, ZipEntry entry) throws IOException {
    try (InputStream input = zip.getInputStream(entry)) {
      byte[] value = input.readNBytes(4 * 1024 * 1024 + 1);
      if (value.length > 4 * 1024 * 1024) throw new IOException("整合包清单过大");
      return new String(value, StandardCharsets.UTF_8);
    }
  }

  public static JsonArray array(JsonObject object, String name) throws IOException {
    if (!object.has(name) || !object.get(name).isJsonArray())
      throw new IOException("整合包缺少数组：" + name);
    return object.getAsJsonArray(name);
  }

  private static JsonObject object(JsonObject object, String name) throws IOException {
    if (!object.has(name) || !object.get(name).isJsonObject())
      throw new IOException("整合包缺少对象：" + name);
    return object.getAsJsonObject(name);
  }

  private static String string(JsonObject value, String key, String fallback) {
    return JsonFiles.string(value, key, fallback);
  }

  private static int integer(JsonObject value, String key, int fallback) {
    return value.has(key) ? value.get(key).getAsInt() : fallback;
  }

  private static void jsonEntry(ZipOutputStream zip, String name, JsonObject value)
      throws IOException {
    entry(zip, name, JsonFiles.GSON.toJson(value));
  }

  private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(value.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  public static void deleteWorkspace(Path directory, Path parent) throws IOException {
    Path normalized = directory.toAbsolutePath().normalize();
    Path boundary = parent.toAbsolutePath().normalize();
    if (!normalized.startsWith(boundary)
        || normalized.equals(boundary)
        || Files.isSymbolicLink(normalized)) throw new IOException("拒绝清理工作目录之外的路径");
    if (!Files.exists(normalized)) return;
    try (var walk = Files.walk(normalized)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }
}
