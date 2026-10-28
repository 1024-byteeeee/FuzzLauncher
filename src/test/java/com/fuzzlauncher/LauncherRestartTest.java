package com.fuzzlauncher;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.infrastructure.LauncherRestart;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherRestartTest {
  @TempDir Path temp;

  @Test
  void packagedRestartUsesExecutableAsOneArgumentEvenWithSpaces() throws Exception {
    String previous = System.getProperty("jpackage.app-path");
    try {
      Path executable = Files.createFile(temp.resolve("Fuzz Launcher.exe"));
      System.setProperty("jpackage.app-path", executable.toString());
      assertEquals(java.util.List.of(executable.toString()), LauncherRestart.command());
      Files.delete(executable);
      assertThrows(java.io.IOException.class, LauncherRestart::command);
    } finally {
      if (previous == null) System.clearProperty("jpackage.app-path");
      else System.setProperty("jpackage.app-path", previous);
    }
  }

  @Test
  void developmentRestartPreservesClasspathAndUsesLauncherEntryPoint() throws Exception {
    var command = LauncherRestart.command();
    assertTrue(Files.isRegularFile(Path.of(command.getFirst())));
    assertEquals("com.fuzzlauncher.Launcher", command.getLast());
    assertEquals(System.getProperty("java.class.path"), command.get(command.indexOf("-cp") + 1));
  }
}
