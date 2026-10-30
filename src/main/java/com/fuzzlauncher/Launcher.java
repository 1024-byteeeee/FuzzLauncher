package com.fuzzlauncher;

/** Classpath entry point used by jpackage and java -jar. */
public final class Launcher {
    public static final String VERSION = version();
    public static final String TITLE = "Fuzz launcher v" + VERSION;

    private Launcher() {
    }

    private static String version() {
        try (var input = Launcher.class.getResourceAsStream("version.properties")) {
            var properties = new java.util.Properties();
            properties.load(java.util.Objects.requireNonNull(input, "Missing launcher version"));
            return java.util.Objects.requireNonNull(properties.getProperty("version"));
        } catch (java.io.IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    public static void main(String[] args) {
        // Read Windows proxy settings before Java initializes its default ProxySelector.
        if (System.getProperty("java.net.useSystemProxies") == null) {
            System.setProperty("java.net.useSystemProxies", "true");
        }
        javafx.application.Application.launch(FuzzApplication.class, args);
    }
}
