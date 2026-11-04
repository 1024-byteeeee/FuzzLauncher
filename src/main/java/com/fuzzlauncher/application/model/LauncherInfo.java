package com.fuzzlauncher.application.model;

import java.io.IOException;
import java.util.Objects;
import java.util.Properties;

/** Immutable product metadata shared without starting the application. */
public final class LauncherInfo {
    public static final String VERSION = version();
    public static final String TITLE = "Fuzz launcher v" + VERSION;

    private LauncherInfo() {
    }

    private static String version() {
        try (var input = LauncherInfo.class.getResourceAsStream("/com/fuzzlauncher/version.properties")) {
            var properties = new Properties();
            properties.load(Objects.requireNonNull(input, "Missing launcher version"));
            return Objects.requireNonNull(properties.getProperty("version"));
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

}
