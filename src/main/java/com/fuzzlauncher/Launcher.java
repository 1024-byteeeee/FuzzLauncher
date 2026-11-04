package com.fuzzlauncher;

import com.fuzzlauncher.application.model.LauncherInfo;
import com.fuzzlauncher.bootstrap.FuzzApplication;

import javafx.application.Application;

/** Classpath entry point used by jpackage and java -jar. */
public final class Launcher {
    public static final String VERSION = LauncherInfo.VERSION;
    public static final String TITLE = LauncherInfo.TITLE;

    private Launcher() {
    }

    public static void main(String[] args) {
        // Read Windows proxy settings before Java initializes its default ProxySelector.
        if (System.getProperty("java.net.useSystemProxies") == null) {
            System.setProperty("java.net.useSystemProxies", "true");
        }
        Application.launch(FuzzApplication.class, args);
    }
}
