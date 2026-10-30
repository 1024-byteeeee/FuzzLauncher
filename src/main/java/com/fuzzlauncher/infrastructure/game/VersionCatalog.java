package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;

import com.google.gson.*;
import org.xml.sax.InputSource;

import java.io.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

public final class VersionCatalog {
    public static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private final HttpTransport http;

    public VersionCatalog(HttpTransport http) {
        this.http = http;
    }

    public List<GameVersion> games() throws IOException, InterruptedException {
        List<GameVersion> result = new ArrayList<>();
        for (JsonElement item : http.getJson(MANIFEST).getAsJsonArray("versions")) {
            JsonObject v = item.getAsJsonObject();
            result.add(new GameVersion(v.get("id").getAsString(), v.get("type").getAsString(), v.get("url").getAsString(),
                JsonFiles.string(v, "sha1", "")));
        }
        return result;
    }

    public List<String> loaders(LoaderType loader, String game) throws Exception {
        if (loader == LoaderType.VANILLA) {
            return List.of();
        }
        if (loader == LoaderType.FABRIC) {
            String gameId = java.net.URLEncoder.encode(game, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            JsonArray values = JsonParser.parseString(http.getText("https://meta.fabricmc.net/v2/versions/loader/" + gameId))
                .getAsJsonArray();
            List<String> result = new ArrayList<>();
            for (JsonElement item : values) {
                result.add(item.getAsJsonObject().getAsJsonObject("loader").get("version").getAsString());
            }
            return result;
        }
        String base = loader == LoaderType.FORGE ? "https://maven.minecraftforge.net/net/minecraftforge/forge/"
            : game.equals("1.20.1") ? "https://maven.neoforged.net/releases/net/neoforged/forge/"
                : "https://maven.neoforged.net/releases/net/neoforged/neoforge/";
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
            if (loader == LoaderType.NEOFORGE && (game.equals("1.20.1") ? version.startsWith(game + "-") : neoMatches(game, version))) {
                result.add(version);
            }
        }
        Collections.reverse(result);
        return result;
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
