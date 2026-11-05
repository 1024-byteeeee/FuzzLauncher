package com.fuzzlauncher.bootstrap;

import com.fuzzlauncher.application.model.LauncherOptions;
import com.fuzzlauncher.application.port.AccountAuthenticator;
import com.fuzzlauncher.application.port.AccountAvatars;
import com.fuzzlauncher.application.port.FileBrowser;
import com.fuzzlauncher.application.port.GameLauncher;
import com.fuzzlauncher.application.port.InstanceRepository;
import com.fuzzlauncher.application.usecase.mod.ManageMods;
import com.fuzzlauncher.domain.settings.DownloadSource;
import com.fuzzlauncher.infrastructure.browser.LocalFileBrowser;
import com.fuzzlauncher.infrastructure.config.LauncherConfig;
import com.fuzzlauncher.infrastructure.filesystem.BaseVersions;
import com.fuzzlauncher.infrastructure.game.catalog.VersionCatalog;
import com.fuzzlauncher.infrastructure.game.install.LocalGameInstallation;
import com.fuzzlauncher.infrastructure.instance.LocalInstanceOperations;
import com.fuzzlauncher.infrastructure.mod.ModFiles;
import com.fuzzlauncher.infrastructure.modpack.LocalModpackOperations;
import com.fuzzlauncher.infrastructure.modpack.LocalModpacks;
import com.fuzzlauncher.infrastructure.persistence.SettingsStore;
import com.fuzzlauncher.infrastructure.recovery.OperationJournal;
import com.fuzzlauncher.infrastructure.settings.LocalSettingsOperations;
import com.fuzzlauncher.ui.viewmodel.LauncherServices;
import com.fuzzlauncher.ui.viewmodel.LauncherViewModel;

import java.io.IOException;
import java.util.*;

public final class ViewModels {
    private ViewModels() {
    }

    public static FileBrowser fileBrowser() {
        return new LocalFileBrowser();
    }

    public static LauncherViewModel create(SettingsStore settings, InstanceRepository repository, VersionCatalog catalog,
        AccountAuthenticator auth, LocalGameInstallation install, GameLauncher launch) throws IOException {
        return create(settings, repository, catalog, auth, install, launch, null);
    }

    public static LauncherViewModel create(SettingsStore settings, InstanceRepository repository, VersionCatalog catalog,
        AccountAuthenticator auth, LocalGameInstallation install, GameLauncher launch, AccountAvatars avatars) throws IOException {
        return create(settings, repository, catalog, auth, install, launch, avatars, null, null);
    }

    public static LauncherViewModel create(SettingsStore settings, InstanceRepository repository, VersionCatalog catalog,
        AccountAuthenticator auth, LocalGameInstallation install, GameLauncher launch, AccountAvatars avatars, LocalModpacks modpacks,
        OperationJournal.Store recovery) throws IOException {
        BaseVersions versions = install == null ? new BaseVersions() : install.baseVersions();
        Map<DownloadSource, LauncherOptions.Source> sources = new EnumMap<>(DownloadSource.class);
        LauncherConfig config = LauncherConfig.get();
        for (var source : DownloadSource.values()) {
            var entry = config.downloads().sources().get(source.name());
            sources.put(source, new LauncherOptions.Source(entry.label(), entry.website()));
        }
        LauncherOptions options = new LauncherOptions(config.network().concurrency(), config.defaults().maxMemoryMb(), config.defaults().jvmArguments(), sources);
        return
            new LauncherViewModel(
                new LauncherServices(settings, repository, catalog, auth, install, launch, avatars,
                new LocalInstanceOperations(repository, versions), new LocalSettingsOperations(settings, repository, versions, recovery),
                new LocalModpackOperations(modpacks), new ManageMods(new ModFiles()), options, fileBrowser())
            );
    }
}
