package com.fuzzlauncher.infrastructure;

import java.io.IOException;
import java.nio.file.*;

/** Resolves the executable location independently of shortcuts' working directories. */
public final class LauncherPaths {
  private LauncherPaths() {}

  public static Path directory() throws IOException {
    String packaged = System.getProperty("jpackage.app-path");
    if (packaged != null && !packaged.isBlank())
      return Path.of(packaged).toAbsolutePath().normalize().getParent();
    try {
      Path source =
          Path.of(LauncherPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (Files.isRegularFile(source)) return source.toAbsolutePath().normalize().getParent();
      // Gradle/IDE execution has class directories rather than a launcher executable.
      return Path.of("").toAbsolutePath().normalize();
    } catch (java.net.URISyntaxException ex) {
      throw new IOException("无法定位启动器所在目录", ex);
    }
  }
}
