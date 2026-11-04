package com.fuzzlauncher.ui.viewmodel;

import com.fuzzlauncher.domain.account.Account;
import com.fuzzlauncher.domain.game.GameVersion;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.settings.LauncherSettings;

import javafx.beans.property.*;
import javafx.collections.*;

final class PresentationState {
    final BooleanProperty installing = new SimpleBooleanProperty(), gameRunning = new SimpleBooleanProperty(),
        loggingIn = new SimpleBooleanProperty(), savingSettings = new SimpleBooleanProperty();
    final ObjectProperty<LauncherSettings> settings = new SimpleObjectProperty<>();
    final ObservableList<GameInstance> instances = FXCollections.observableArrayList();
    final ObjectProperty<GameInstance> selected = new SimpleObjectProperty<>();
    final ObservableList<GameVersion> versions = FXCollections.observableArrayList();
    final ObservableList<String> loaderVersions = FXCollections.observableArrayList();
    final ObjectProperty<Account> account = new SimpleObjectProperty<>();

    void finished() {
        installing.set(false);
        savingSettings.set(false);
        loggingIn.set(false);
    }

    void replaceInstance(GameInstance updated) {
        for (int i = 0; i < instances.size(); i++) {
            if (instances.get(i).id().equals(updated.id())) {
                instances.set(i, updated);
                break;
            }
        }
        if (selected.get() != null && selected.get().id().equals(updated.id())) {
            selected.set(updated);
        }
    }
}
