package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.LauncherVisibility;

import javafx.application.Platform;
import javafx.stage.Stage;

/** Applies the policy captured for one successful game launch, on the JavaFX thread. */
public final class LauncherVisibilityController {
    private final Stage stage;
    private final Runnable quit;
    private LauncherVisibility active = LauncherVisibility.KEEP;

    public LauncherVisibilityController(Stage stage, Runnable quit) {
        this.stage = stage;
        this.quit = quit;
    }

    public void started(LauncherVisibility policy) {
        active = policy;
        switch (policy) {
            case EXIT -> quit.run();
            case HIDE, HIDE_AND_REOPEN -> {
                Platform.setImplicitExit(false);
                stage.setIconified(false);
                stage.hide();
            }
            case KEEP -> {
            }
        }
    }

    public void exited(int exitCode) {
        LauncherVisibility policy = active;
        active = LauncherVisibility.KEEP;
        if (policy == LauncherVisibility.HIDE && exitCode == 0) {
            quit.run();
        } else if (policy == LauncherVisibility.HIDE || policy == LauncherVisibility.HIDE_AND_REOPEN) {
            stage.setIconified(false);
            stage.show();
            stage.toFront();
            Platform.setImplicitExit(true);
        }
    }
}
