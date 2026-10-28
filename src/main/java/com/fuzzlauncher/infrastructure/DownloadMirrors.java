package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.domain.DownloadSource;
import java.net.URI;
import java.util.*;

/** Routes only known public game downloads. Authentication endpoints are never mirrored. */
public final class DownloadMirrors {
  private DownloadMirrors() {}

  public static String resolve(DownloadSource source, String url) {
    return candidates(source, url, false).getFirst();
  }

  public static List<String> candidates(DownloadSource source, String url, boolean metadata) {
    if (source == null) source = DownloadSource.AUTO;
    boolean china = "CN".equalsIgnoreCase(Locale.getDefault().getCountry());
    if (!china && source != DownloadSource.MIRROR) return List.of(url);
    String mirror = mirrorUrl(url);
    List<String> result = new ArrayList<>();
    boolean mirrorFirst =
        source == DownloadSource.MIRROR || (source == DownloadSource.AUTO && !metadata);
    if (mirrorFirst) result.add(mirror);
    result.add(url);
    if (!mirrorFirst) result.add(mirror);
    URI original = URI.create(url);
    if ("https".equalsIgnoreCase(original.getScheme())
        && "maven.neoforged.net".equalsIgnoreCase(original.getHost())
        && original.getUserInfo() == null
        && original.getPort() == -1) {
      result.add(
          "https://neoforged.forgecdn.net"
              + original.getRawPath()
              + (original.getRawQuery() == null ? "" : "?" + original.getRawQuery()));
    }
    return result.stream().distinct().toList();
  }

  private static String mirrorUrl(String url) {
    URI uri = URI.create(url);
    if (uri.getUserInfo() != null
        || uri.getHost() == null
        || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
        || (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80)) return url;
    String path = uri.getRawPath();
    String mavenPath =
        switch (uri.getHost().toLowerCase(java.util.Locale.ROOT)) {
          case "maven.fabricmc.net", "maven.minecraftforge.net" -> path;
          case "files.minecraftforge.net" -> path.startsWith("/maven/") ? path.substring(6) : null;
          case "maven.neoforged.net" -> path.startsWith("/releases/") ? path.substring(9) : null;
          default -> null;
        };
    String target;
    if (("repo.maven.apache.org".equalsIgnoreCase(uri.getHost())
            || "repo1.maven.org".equalsIgnoreCase(uri.getHost()))
        && path.startsWith("/maven2/")) {
      target =
          "https://mirrors.cloud.tencent.com/nexus/repository/maven-public" + path.substring(7);
    } else {
      String mirrorPath =
          mavenPath != null
              ? "/maven" + mavenPath
              : switch (uri.getHost().toLowerCase(java.util.Locale.ROOT)) {
                case "launchermeta.mojang.com",
                        "launcher.mojang.com",
                        "piston-meta.mojang.com",
                        "piston-data.mojang.com" ->
                    path;
                case "resources.download.minecraft.net" -> "/assets" + path;
                case "libraries.minecraft.net" -> "/libraries" + path;
                case "meta.fabricmc.net" -> "/fabric-meta" + path;
                default -> null;
              };
      if (mirrorPath == null) return url;
      target = "https://bmclapi2.bangbang93.com" + mirrorPath;
    }
    return target + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
  }
}
