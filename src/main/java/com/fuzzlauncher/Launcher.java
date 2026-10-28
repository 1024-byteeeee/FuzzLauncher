package com.fuzzlauncher;

/** Classpath entry point used by jpackage and java -jar. */
public final class Launcher {
  private Launcher() {}

  public static void main(String[] args) {
    javafx.application.Application.launch(FuzzApplication.class, args);
  }
}
