package com.fuzzlauncher.ui.viewmodel;

import com.fuzzlauncher.application.model.LauncherOptions;
import com.fuzzlauncher.application.port.AccountAuthenticator;
import com.fuzzlauncher.application.port.AccountAvatars;
import com.fuzzlauncher.application.port.FileBrowser;
import com.fuzzlauncher.application.port.GameInstallation;
import com.fuzzlauncher.application.port.GameLauncher;
import com.fuzzlauncher.application.port.InstanceOperations;
import com.fuzzlauncher.application.port.InstanceRepository;
import com.fuzzlauncher.application.port.ModpackOperations;
import com.fuzzlauncher.application.port.SettingsOperations;
import com.fuzzlauncher.application.port.SettingsRepository;
import com.fuzzlauncher.application.port.VersionCatalogPort;
import com.fuzzlauncher.application.usecase.mod.ManageContent;

/** Explicitly wired service dependencies. No runtime lookup or global registration. */
public record LauncherServices(SettingsRepository settings, InstanceRepository repository, VersionCatalogPort catalog,
    AccountAuthenticator authentication, GameInstallation installation, GameLauncher launcher, AccountAvatars avatars,
    InstanceOperations instances, SettingsOperations settingsOperations, ModpackOperations modpacks, ManageContent mods, ManageContent resourcePacks,
    ManageContent schematics, LauncherOptions options, FileBrowser browser) {
}
