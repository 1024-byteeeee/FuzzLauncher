package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.config.LauncherConfig;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;

import com.google.gson.*;
import org.xml.sax.InputSource;

import java.io.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

public final class VersionCatalog {
    public static final String MANIFEST = LauncherConfig.get().service("manifest");
    private final HttpTransport http;
    private final CatalogCache cache;

    public VersionCatalog(HttpTransport http) {
        this(http, null);
    }

    public VersionCatalog(HttpTransport http, java.nio.file.Path directory) {
        this(http, directory, java.time.Clock.systemUTC());
    }

    VersionCatalog(HttpTransport http, java.nio.file.Path directory, java.time.Clock clock) {
        this.http = http;
        cache = new CatalogCache(directory, clock);
    }

    public List<GameVersion> games() throws IOException, InterruptedException {
        return games(false, values -> {
        });
    }

    public List<GameVersion> games(boolean force, java.util.function.Consumer<List<GameVersion>> cached)
        throws IOException, InterruptedException {
        String key = http.catalogSource() + "|" + MANIFEST;
        var previous = cache.read(key, GameVersion[].class);
        if (previous != null) {
            cached.accept(previous.values());
            if (!force && cache.fresh(previous)) {
                return previous.values();
            }
        }
        List<GameVersion> result = new ArrayList<>();
        for (JsonElement item : http.getJson(MANIFEST).getAsJsonArray("versions")) {
            JsonObject v = item.getAsJsonObject();
            result.add(new GameVersion(v.get("id").getAsString(), v.get("type").getAsString(), v.get("url").getAsString(),
                JsonFiles.string(v, "sha1", "")));
        }
        return cache.write(key, result);
    }

    public List<String> loaders(LoaderType loader, String game) throws Exception {
        return loaders(loader, game, false, values -> {
        });
    }

    public List<String> loaders(LoaderType loader, String game, boolean force, java.util.function.Consumer<List<String>> cached)
        throws Exception {
        if (loader == LoaderType.VANILLA) {
            return List.of();
        }
        String key = http.catalogSource() + "|" + loader + "|" + game + "|"
            + (loader == LoaderType.FABRIC ? LauncherConfig.get().service("fabricMeta") : LauncherConfig.get().loaderMaven(loader, game));
        var previous = cache.read(key, String[].class);
        if (previous != null) {
            cached.accept(previous.values());
            if (!force && cache.fresh(previous)) {
                return previous.values();
            }
        }
        if (loader == LoaderType.FABRIC) {
            String gameId = java.net.URLEncoder.encode(game, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            JsonArray values = JsonParser.parseString(http.getText(LauncherConfig.get().service("fabricMeta") + gameId)).getAsJsonArray();
            List<String> result = new ArrayList<>();
            for (JsonElement item : values) {
                result.add(item.getAsJsonObject().getAsJsonObject("loader").get("version").getAsString());
            }
            return cache.write(key, result);
        }
        String base = LauncherConfig.get().loaderMaven(loader, game);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(http.getText(base + "maven-metadata.xml"))));
        var nodes = document.getElementsByTagName("version");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            String version = nodes.item(i).getTextContent();
            if (loader == LoaderType.FORGE && version.startsWith(game + "-")) {
                result.add(version);
            }
            if (loader == LoaderType.NEOFORGE
                && (LauncherConfig.get().java().neoForgeLegacyGames().contains(game) ? version.startsWith(game + "-")
                    : neoMatches(game, version))) {
                result.add(version);
            }
        }
        Collections.reverse(result);
        return cache.write(key, result);
    }

    static boolean neoMatches(String game, String loader) {
        String[] parts = game.split("\\.");
        if (parts.length >= 2 && parts[0].equals("1")) {
            String prefix = parts[1] + "." + (parts.length > 2 ? parts[2] : "0") + ".";
            return loader.startsWith(prefix);
        }
        // New year-based releases retain the game major in their Maven version.
        if (parts.length >= 2 && game.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")) {
            String prefix = parts[0] + "." + parts[1] + "." + (parts.length > 2 ? parts[2] : "0") + ".";
            return loader.startsWith(prefix);
        }
        return false;
    }
}
