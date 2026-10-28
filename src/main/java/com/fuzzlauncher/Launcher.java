package com.fuzzlauncher;

/** Classpath entry point used by jpackage and java -jar. */
public final class Launcher {
  private Launcher() {}

  public static void main(String[] args) {
    // Read Windows proxy settings before Java initializes its default ProxySelector.
    if (System.getProperty("java.net.useSystemProxies") == null)
      System.setProperty("java.net.useSystemProxies", "true");
    javafx.application.Application.launch(FuzzApplication.class, args);
  }
}
