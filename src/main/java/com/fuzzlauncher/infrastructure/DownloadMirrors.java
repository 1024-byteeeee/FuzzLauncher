package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.config.LauncherConfig;
import com.fuzzlauncher.domain.DownloadSource;

import java.net.URI;
import java.util.*;

/** Routes only public game downloads; authenticated requests bypass this class. */
public final class DownloadMirrors {
    private DownloadMirrors() {
    }

    public static String resolve(DownloadSource source, String url) {
        return candidates(source, url, false).getFirst();
    }

    public static List<String> candidates(DownloadSource source, String url, boolean metadata) {
        LauncherConfig.Downloads config = LauncherConfig.get().downloads();
        if (source == null) {
            source = DownloadSource.AUTO;
        }
        boolean mirrorRegion = config.autoMirrorRegions().stream()
            .anyMatch(region -> region.equalsIgnoreCase(Locale.getDefault().getCountry()));
        if (!mirrorRegion && source != DownloadSource.MIRROR) {
            return List.of(url);
        }
        URI original = URI.create(url);
        boolean eligible = original.getUserInfo() == null && original.getHost() != null
            && ("https".equalsIgnoreCase(original.getScheme()) || "http".equalsIgnoreCase(original.getScheme()))
            && (original.getPort() == -1 || original.getPort() == 443 || original.getPort() == 80);
        if (!eligible) {
            return List.of(url);
        }
        String mirror = config.rules().stream().map(rule -> rule.resolve(original)).flatMap(Optional::stream).findFirst().orElse(url);
        List<String> result = new ArrayList<>();
        boolean mirrorFirst = source == DownloadSource.MIRROR || (source == DownloadSource.AUTO && !metadata);
        if (mirrorFirst) {
            result.add(mirror);
        }
        result.add(url);
        if (!mirrorFirst) {
            result.add(mirror);
        }
        if ("https".equalsIgnoreCase(original.getScheme()) && original.getPort() == -1) {
            config.fallbacks().stream().map(rule -> rule.resolve(original)).flatMap(Optional::stream).forEach(result::add);
        }
        return result.stream().distinct().toList();
    }
}
