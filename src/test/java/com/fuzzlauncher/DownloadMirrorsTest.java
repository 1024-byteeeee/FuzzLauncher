package com.fuzzlauncher;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadMirrorsTest {
  @TempDir Path temp;

  @Test
  void bmclRoutesGameResourcesAndAllSupportedLoaderRepositories() {
    assertMirror(
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
        "/mc/game/version_manifest_v2.json");
    assertMirror(
        "https://piston-data.mojang.com/v1/objects/hash/client.jar", "/v1/objects/hash/client.jar");
    assertMirror("http://resources.download.minecraft.net/ab/hash", "/assets/ab/hash");
    assertMirror("https://libraries.minecraft.net/a/b.jar", "/libraries/a/b.jar");
    assertMirror(
        "https://meta.fabricmc.net/v2/versions/loader/1.20.4",
        "/fabric-meta/v2/versions/loader/1.20.4");
    assertMirror("https://maven.fabricmc.net/net/fabricmc/a.jar", "/maven/net/fabricmc/a.jar");
    assertMirror(
        "https://maven.minecraftforge.net/net/minecraftforge/a.jar.sha1",
        "/maven/net/minecraftforge/a.jar.sha1");
    assertMirror(
        "https://files.minecraftforge.net/maven/net/minecraftforge/a.jar",
        "/maven/net/minecraftforge/a.jar");
    assertMirror(
        "https://maven.neoforged.net/releases/net/neoforged/a.jar", "/maven/net/neoforged/a.jar");
  }

  private static void assertMirror(String original, String path) {
    assertEquals(
        "https://bmclapi2.bangbang93.com" + path,
        DownloadMirrors.resolve(DownloadSource.MIRROR, original));
  }

  @Test
  void prioritiesFollowSystemRegionAndMetadataUsesOfficialFirstInAutoMode() {
    java.util.Locale previous = java.util.Locale.getDefault();
    String url = "https://libraries.minecraft.net/a.jar";
    String mirror = "https://bmclapi2.bangbang93.com/libraries/a.jar";
    try {
      java.util.Locale.setDefault(java.util.Locale.CHINA);
      assertEquals(
          java.util.List.of(mirror, url),
          DownloadMirrors.candidates(DownloadSource.AUTO, url, false));
      assertEquals(
          java.util.List.of(url, mirror),
          DownloadMirrors.candidates(DownloadSource.AUTO, url, true));
      assertEquals(
          java.util.List.of(url, mirror),
          DownloadMirrors.candidates(DownloadSource.OFFICIAL, url, false));
      java.util.Locale.setDefault(java.util.Locale.US);
      assertEquals(
          java.util.List.of(url), DownloadMirrors.candidates(DownloadSource.AUTO, url, false));
      assertEquals(
          java.util.List.of(mirror, url),
          DownloadMirrors.candidates(DownloadSource.MIRROR, url, false));
    } finally {
      java.util.Locale.setDefault(previous);
    }
  }

  @Test
  void tencentAndNeoForgeCdnMatchCurrentHmclEndpoints() {
    assertEquals(
        "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/com/example/a.jar?key=value",
        DownloadMirrors.resolve(
            DownloadSource.MIRROR,
            "https://repo.maven.apache.org/maven2/com/example/a.jar?key=value"));
    assertEquals(
        "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/com/example/a.jar",
        DownloadMirrors.resolve(
            DownloadSource.MIRROR, "https://repo1.maven.org/maven2/com/example/a.jar"));
    String original = "https://maven.neoforged.net/releases/net/neoforged/a.jar";
    assertEquals(
        java.util.List.of(
            "https://bmclapi2.bangbang93.com/maven/net/neoforged/a.jar",
            original,
            "https://neoforged.forgecdn.net/releases/net/neoforged/a.jar"),
        DownloadMirrors.candidates(DownloadSource.MIRROR, original, false));
  }

  @Test
  void authenticationAndUnrecognizedHostsNeverReachMirrors() {
    for (DownloadSource source : DownloadSource.values())
      for (String url :
          new String[] {
            "https://api.minecraftservices.com/minecraft/profile",
            "https://user.auth.xboxlive.com/user/authenticate",
            "https://xsts.auth.xboxlive.com/xsts/authorize",
            "https://login.microsoftonline.com/consumers/",
            "https://libraries.minecraft.net.example.com/a.jar",
            "https://libraries.minecraft.net@evil.example/a.jar",
            "https://api.adoptium.net/v3/assets/latest/21/hotspot"
          }) assertEquals(url, DownloadMirrors.resolve(source, url));
  }

  @Test
  void oldSettingsAndRemovedMirrorNamesMigrateWithoutLosingOtherSettings() throws Exception {
    var json = new com.google.gson.JsonObject();
    json.addProperty("gameRoot", temp.resolve("game").toString());
    json.addProperty("isolation", "ALL");
    JsonFiles.write(temp.resolve("settings.json"), json);
    SettingsStore store = new SettingsStore(temp);
    LauncherSettings old = store.load();
    assertEquals(DownloadSource.AUTO, old.downloadSource());
    for (String oldName : new String[] {"BMCLAPI", "NJU"}) {
      json.addProperty("downloadSource", oldName);
      JsonFiles.write(temp.resolve("settings.json"), json);
      assertEquals(DownloadSource.MIRROR, store.load().downloadSource());
      assertEquals(old.gameRoot(), store.load().gameRoot());
    }
    for (DownloadSource source : DownloadSource.values()) {
      store.save(new LauncherSettings(old.gameRoot(), old.isolation(), source));
      assertEquals(source, new SettingsStore(temp).load().downloadSource());
    }
  }
}
