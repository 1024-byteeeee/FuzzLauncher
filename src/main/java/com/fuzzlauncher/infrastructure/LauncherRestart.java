package com.fuzzlauncher.infrastructure;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Relaunches the same packaged executable, or the original Java command in development. */
public final class LauncherRestart {
  private LauncherRestart() {}

  public static List<String> command() throws IOException {
    String packaged = System.getProperty("jpackage.app-path");
    if (packaged != null && !packaged.isBlank()) {
      if (!Files.isRegularFile(Path.of(packaged))) throw new IOException("找不到启动器程序：" + packaged);
      return List.of(packaged);
    }
    Path executable = Path.of(System.getProperty("java.home"), "bin", "javaw.exe");
    if (!Files.isRegularFile(executable))
      executable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
    if (!Files.isRegularFile(executable)) throw new IOException("找不到启动器 Java 程序");
    List<String> command = new ArrayList<>();
    command.add(executable.toString());
    // Windows does not expose ProcessHandle arguments. Preserve module paths and VM properties
    // directly, but do not reattach a debugger to its already occupied port.
    java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(
            argument -> !argument.startsWith("-agentlib:") && !argument.startsWith("-Xrunjdwp:"))
        .forEach(command::add);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add("com.fuzzlauncher.Launcher");
    return List.copyOf(command);
  }

  public static void launch(List<String> command) throws IOException {
    new ProcessBuilder(command)
        .directory(Path.of("").toAbsolutePath().toFile())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
  }
}
