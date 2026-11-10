package com.fuzzlauncher.ui.viewmodel;

import com.fuzzlauncher.application.model.LauncherOptions;
import com.fuzzlauncher.application.port.FileBrowser;
import com.fuzzlauncher.application.port.ModpackOperations;
import com.fuzzlauncher.application.port.SettingsRepository;
import com.fuzzlauncher.domain.account.Account;
import com.fuzzlauncher.domain.game.GameVersion;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.ui.task.FxTaskCoordinator;

import javafx.beans.property.*;
import javafx.collections.*;

import java.io.IOException;
import java.nio.file.Path;

/** Presentation facade: shared observable state, feature view models and lifecycle delegation. */
public final class LauncherViewModel implements AutoCloseable {
    private final PresentationState state = new PresentationState();
    private final FxTaskCoordinator tasks = new FxTaskCoordinator(state.savingSettings::get, state::finished);
    private final SettingsRepository settingsStore;
    private final ModpackOperations modpacks;
    private final FileBrowser browser;
    private final LauncherOptions options;
    private final ObjectProperty<LauncherSettings> settings = state.settings;
    private final ObservableList<GameInstance> instances = state.instances;
    private final ObjectProperty<GameInstance> selected = state.selected;
    private final ObservableList<GameVersion> versions = state.versions;
    private final ObservableList<String> loaderVersions = state.loaderVersions;
    private final ObservableList<String> readOnlyLoaderVersions = FXCollections.unmodifiableObservableList(loaderVersions);
    private final ObjectProperty<Account> account = state.account;
    private final BooleanProperty busy = tasks.busyProperty(), gameRunning = state.gameRunning, installing = state.installing,
        loggingIn = state.loggingIn, savingSettings = state.savingSettings;
    private final DoubleProperty progress = tasks.progressProperty();
    private final StringProperty status = tasks.statusProperty(), error = tasks.errorProperty();
    private final ContentViewModel modsViewModel;
    private final ContentViewModel resourcePacksViewModel;
    private final ContentViewModel shaderPacksViewModel;
    private final ContentViewModel schematicsViewModel;
    private final AccountsViewModel accountsViewModel;
    private final CatalogViewModel catalogViewModel;
    private final InstancesViewModel instancesViewModel;
    private final SettingsViewModel settingsViewModel;
    private final GamesViewModel gamesViewModel;
    private final ModpacksViewModel modpacksViewModel;

    public LauncherViewModel(LauncherServices services) throws IOException {
        settingsStore = services.settings();
        modpacks = services.modpacks();
        browser = services.browser();
        options = services.options();
        settings.set(settingsStore.load());
        instances.setAll(services.repository().findAll());
        String warning = services.instances().initialize(settings.get(), instances);
        if (!warning.isBlank()) {
            status.set(warning);
        }
        if (!instances.isEmpty()) {
            selected.set(instances.getFirst());
        }
        modsViewModel = new ContentViewModel(state, tasks, services.mods());
        resourcePacksViewModel = new ContentViewModel(state, tasks, services.resourcePacks());
        shaderPacksViewModel = new ContentViewModel(state, tasks, services.shaderPacks());
        schematicsViewModel = new ContentViewModel(state, tasks, services.schematics());
        accountsViewModel = new AccountsViewModel(state, tasks, services.authentication(), services.avatars());
        catalogViewModel = new CatalogViewModel(state, tasks, services.catalog());
        instancesViewModel = new InstancesViewModel(state, tasks, services.instances());
        settingsViewModel = new SettingsViewModel(state, tasks, services.settings(), services.settingsOperations());
        gamesViewModel = new GamesViewModel(state, tasks, services.catalog(), services.installation(), services.launcher(), services.modpacks());
        modpacksViewModel = new ModpacksViewModel(state, tasks, services.modpacks());
    }

    public boolean isCommitting() {
        return tasks.isCommitting();
    }

    public ReadOnlyBooleanProperty loggingInProperty() {
        return loggingIn;
    }

    public ReadOnlyBooleanProperty savingSettingsProperty() {
        return savingSettings;
    }

    public FileBrowser browser() {
        return browser;
    }

    public LauncherOptions options() {
        return options;
    }

    public ModpackOperations modpacks() {
        return modpacks;
    }

    public ReadOnlyObjectProperty<LauncherSettings> settingsProperty() {
        return settings;
    }

    public ObservableList<GameInstance> instances() {
        return FXCollections.unmodifiableObservableList(instances);
    }

    public ReadOnlyObjectProperty<GameInstance> selectedProperty() {
        return selected;
    }

    public ObservableList<GameVersion> versions() {
        return FXCollections.unmodifiableObservableList(versions);
    }

    public ObservableList<String> loaderVersions() {
        return readOnlyLoaderVersions;
    }

    public ReadOnlyObjectProperty<Account> accountProperty() {
        return account;
    }

    public ReadOnlyBooleanProperty busyProperty() {
        return busy;
    }

    public ReadOnlyDoubleProperty progressProperty() {
        return progress;
    }

    public ReadOnlyStringProperty statusProperty() {
        return status;
    }

    public ReadOnlyStringProperty errorProperty() {
        return error;
    }

    public ReadOnlyBooleanProperty gameRunningProperty() {
        return gameRunning;
    }

    public ReadOnlyBooleanProperty installingProperty() {
        return installing;
    }

    public Path dataDirectory() {
        return settingsStore.directory();
    }

    public void cancel() {
        tasks.cancel();
    }

    public void cancelCatalogRequests(Object owner) {
        tasks.cancelCatalogRequests(owner);
    }

    public ContentViewModel modsModel() {
        return modsViewModel;
    }

    public ContentViewModel resourcePacksModel() {
        return resourcePacksViewModel;
    }

    public ContentViewModel shaderPacksModel() {
        return shaderPacksViewModel;
    }

    public ContentViewModel schematicsModel() {
        return schematicsViewModel;
    }

    public AccountsViewModel accountsModel() {
        return accountsViewModel;
    }

    public CatalogViewModel catalogModel() {
        return catalogViewModel;
    }

    public InstancesViewModel instancesModel() {
        return instancesViewModel;
    }

    public SettingsViewModel settingsModel() {
        return settingsViewModel;
    }

    public GamesViewModel gamesModel() {
        return gamesViewModel;
    }

    public ModpacksViewModel modpacksModel() {
        return modpacksViewModel;
    }

    public void close() {
        accountsViewModel.close();
        tasks.close();
    }

    public boolean awaitShutdown() {
        boolean tasksStopped = tasks.awaitShutdown();
        boolean avatarsStopped = accountsViewModel.awaitShutdown();
        return tasksStopped && avatarsStopped;
    }
}
