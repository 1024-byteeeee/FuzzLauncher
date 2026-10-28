package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.domain.DownloadSource;
import java.net.URI;

/** Routes only known public game downloads. Authentication endpoints are never mirrored. */
public final class DownloadMirrors {
  private DownloadMirrors() {}

  public static String resolve(DownloadSource source, String url) {
    if (source == null || source == DownloadSource.OFFICIAL) return url;
    URI uri = URI.create(url);
    if (uri.getUserInfo() != null
        || uri.getHost() == null
        || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
        || (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80)) return url;
    String path = uri.getRawPath();
    String mavenPath =
        switch (uri.getHost().toLowerCase(java.util.Locale.ROOT)) {
          case "libraries.minecraft.net", "maven.fabricmc.net", "maven.minecraftforge.net" -> path;
          case "files.minecraftforge.net" -> path.startsWith("/maven/") ? path.substring(6) : null;
          case "maven.neoforged.net" -> path.startsWith("/releases/") ? path.substring(9) : null;
          default -> null;
        };
    String target;
    if (source == DownloadSource.NJU) {
      if (mavenPath == null) return url;
      target = "https://mirror.nju.edu.cn/bmclapi" + mavenPath;
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
                case "meta.fabricmc.net" -> "/fabric-meta" + path;
                default -> null;
              };
      if (mirrorPath == null) return url;
      target = "https://bmclapi2.bangbang93.com" + mirrorPath;
    }
    return target + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
  }
}
