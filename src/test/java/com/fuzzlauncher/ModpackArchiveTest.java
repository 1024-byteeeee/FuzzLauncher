package com.fuzzlauncher;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class ModpackArchiveTest {
    @TempDir
    Path temp;
    private final TaskContext task = new TaskContext() {
        public void report(String message, double value) {
        }

        public boolean cancelled() {
            return false;
        }
    };

    private Path archive(String name, Map<String, String> entries) throws IOException {
        Path path = temp.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }

    @Test
    void allFourFormatsRoundTripContentAndSupportedLoaders() throws Exception {
        Path source = Files.createDirectories(temp.resolve("source"));
        Files.createDirectories(source.resolve("mods"));
        Files.createDirectories(source.resolve("config"));
        Files.writeString(source.resolve("mods/example.jar"), "mod fixture");
        Files.writeString(source.resolve("config/中文设置.json"), "configuration fixture");
        Files.createDirectories(source.resolve("config/hirosakura/data"));
        Files.writeString(source.resolve("config/hirosakura/data/settings.json"), "mod data fixture");
        Files.createDirectories(source.resolve("config/hirosakura/libraries"));
        Files.writeString(source.resolve("config/hirosakura/libraries/settings.json"), "mod library fixture");
        Files.writeString(source.resolve("config/hirosakura/data/accounts.json"), "nested private fixture");
        Files.writeString(source.resolve("account.json"), "private fixture");
        Files.writeString(source.resolve(".fuzz-instance.json"), "internal fixture");
        List<String> unrestricted = List.of("config/jade/usernamecache.json", "logs/latest.log", "libraries/helper.jar", "data/config.json",
            "account.json", "credentials.dpapi", ".env", ".git/config", "tools/tool.exe", "1.20.4.jar", "1.20.4.json");
        for (String path : unrestricted) {
            Files.createDirectories(source.resolve(path).getParent());
            Files.writeString(source.resolve(path), "fixture: " + path);
        }
        Set<String> selected = new HashSet<>(unrestricted);
        selected.addAll(Set.of("mods", "config", ".fuzz-instance.json"));
        for (ModpackFormat format : ModpackFormat.values()) {
            for (LoaderType loader : LoaderType.values()) {
                String version = loader == LoaderType.VANILLA ? null
                    : loader == LoaderType.FORGE ? "1.20.4-49.0.30" : loader == LoaderType.FABRIC ? "0.16.0" : "20.4.100";
                GameInstance instance = new GameInstance("test", "测试整合包", "1.20.4", loader, version, IsolationOverride.ISOLATED, 4096);
                Path output = temp.resolve(format.name() + loader.name() + format.extension());
                ModpackArchive.export(source, instance, format, "测试整合包", "2.3.4", "1024_byteeeee", "整合包说明", "https://example.com/pack",
                    selected, output, false, task);
                var info = ModpackArchive.inspect(output);
                assertEquals(format, info.format());
                assertEquals(loader, info.loader());
                assertEquals("1.20.4", info.game());
                assertEquals("测试整合包", info.name());
                if (loader == LoaderType.FORGE) {
                    assertEquals("49.0.30", info.loaderVersion());
                }
                Path content = temp.resolve("content-" + format.name() + loader.name());
                ModpackArchive.extract(output, info, content, true, new HttpTransport(), task);
                assertEquals("mod fixture", Files.readString(content.resolve("mods/example.jar")));
                assertEquals("configuration fixture", Files.readString(content.resolve("config/中文设置.json")));
                assertEquals("mod data fixture", Files.readString(content.resolve("config/hirosakura/data/settings.json")));
                assertEquals("mod library fixture", Files.readString(content.resolve("config/hirosakura/libraries/settings.json")));
                assertEquals("nested private fixture", Files.readString(content.resolve("config/hirosakura/data/accounts.json")));
                assertEquals("internal fixture", Files.readString(content.resolve(".fuzz-instance.json")));
                for (String path : unrestricted) {
                    assertEquals("fixture: " + path, Files.readString(content.resolve(path)), format + ": " + path);
                }
            }
        }
        assertTrue(ModpackArchive.exportEntries(source).contains("account.json"));
        var scanned = ModpackArchive.scanExport(source,
            new GameInstance("test", "测试", "1.20.4", LoaderType.VANILLA, null, IsolationOverride.ISOLATED, 4096), task);
        assertTrue(scanned.stream().anyMatch(entry -> entry.path().equals("config/hirosakura/data")));
        assertTrue(scanned.stream().anyMatch(entry -> entry.path().endsWith("accounts.json")));
        for (String path : unrestricted) {
            assertTrue(scanned.stream().anyMatch(entry -> entry.path().equals(path)), path);
        }
    }

    @Test
    void fileNamesAreUnrestrictedAndOnlyInvalidPathsAreRejected() throws Exception {
        for (String directory : List.of("assets", "libraries", "versions", "runtime", "runtimes", "logs", "crash-reports", "backups",
            "natives", "data", "accounts")) {
            assertTrue(ModpackArchive.allowedContent(directory + "/settings.json"), directory);
            assertTrue(ModpackArchive.allowedContent("config/hirosakura/" + directory + "/settings.json"), directory);
        }
        for (String path : List.of("config/accounts.json", "config/data/credentials.dpapi", "config/data/.env",
            "config/.fuzz-modpack.json")) {
            assertTrue(ModpackArchive.allowedContent(path), path);
            ModpackArchive.validateContent(path);
        }
        assertFalse(ModpackArchive.allowedContent("config/../escape"));
        assertThrows(IOException.class, () -> ModpackArchive.validateContent("config/../escape"));
        ModpackArchive.validateContent("config/hirosakura/data");
    }

    @Test
    void maliciousDuplicateAndReservedArchivePathsAreRejected() throws Exception {
        String manifest = "{\"formatVersion\":1,\"components\":[{\"uid\":\"net.minecraft\",\"version\":\"1.20.4\"}]}";
        for (String unsafe : List.of("../outside", "/absolute", "C:/outside", "dir/../escape", "dir\\escape", "dir/NUL.txt",
            "data:stream")) {
            Path zip = archive("unsafe-" + UUID.randomUUID() + ".zip", Map.of("mmc-pack.json", manifest, unsafe, "bad"));
            assertThrows(IOException.class, () -> ModpackArchive.inspect(zip), unsafe);
        }
        Path duplicate = archive("duplicate.zip",
            Map.of("mmc-pack.json", manifest, ".minecraft/mods/A.jar", "a", ".minecraft/mods/a.jar", "b"));
        assertThrows(IOException.class, () -> ModpackArchive.inspect(duplicate));
        Path credentials = archive("credentials.zip", Map.of("mmc-pack.json", manifest, ".minecraft/account.json", "private"));
        ModpackArchive.extract(credentials, ModpackArchive.inspect(credentials), temp.resolve("unrestricted"), true, new HttpTransport(),
            task);
        assertEquals("private", Files.readString(temp.resolve("unrestricted/account.json")));
        assertFalse(Files.exists(temp.resolve("outside")));
    }

    @Test
    void wrappedMultiMcAndClientOverridesAreImported() throws Exception {
        Path mmc = archive("wrapped.zip",
            Map.of("包/mmc-pack.json", "{\"formatVersion\":1,\"components\":[{\"uid\":\"net.minecraft\",\"version\":\"1.20.4\"}]}",
                "包/instance.cfg", "[General]\nname=外部实例\nPreLaunchCommand=never-execute\n", "包/.minecraft/config/settings.txt",
                "settings"));
        var info = ModpackArchive.inspect(mmc);
        assertEquals("外部实例", info.name());
        ModpackArchive.extract(mmc, info, temp.resolve("wrapped"), false, new HttpTransport(), task);
        assertEquals("settings", Files.readString(temp.resolve("wrapped/config/settings.txt")));
        assertFalse(Files.exists(temp.resolve("wrapped/instance.cfg")));
        Path mrpack = archive("layers.mrpack", Map.of("modrinth.index.json",
            "{\"formatVersion\":1,\"game\":\"minecraft\",\"versionId\":\"1\",\"name\":\"layers\",\"files\":[],\"dependencies\":{\"minecraft\":\"1.20.4\"}}",
            "overrides/config/example.txt", "common", "client-overrides/config/example.txt", "client", "server-overrides/config/server.txt",
            "server"));
        ModpackArchive.extract(mrpack, ModpackArchive.inspect(mrpack), temp.resolve("layers"), true, new HttpTransport(), task);
        assertEquals("client", Files.readString(temp.resolve("layers/config/example.txt")));
        assertFalse(Files.exists(temp.resolve("layers/config/server.txt")));
    }

    @Test
    void modrinthDownloadsVerifyBothHashesAndRespectEnvironment() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = "remote mod".getBytes(StandardCharsets.UTF_8);
        server.createContext("/mod", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Path expected = temp.resolve("expected");
            Files.write(expected, bytes);
            JsonObject index = JsonParser.parseString(
                "{\"formatVersion\":1,\"game\":\"minecraft\",\"versionId\":\"1\",\"name\":\"remote\",\"files\":[],\"dependencies\":{\"minecraft\":\"1.20.4\"}}")
                .getAsJsonObject();
            JsonObject file = new JsonObject();
            file.addProperty("path", "mods/remote.jar");
            file.addProperty("fileSize", bytes.length);
            JsonObject hashes = new JsonObject();
            hashes.addProperty("sha1", ModpackArchive.digest(expected, "SHA-1"));
            hashes.addProperty("sha512", ModpackArchive.digest(expected, "SHA-512"));
            file.add("hashes", hashes);
            JsonArray downloads = new JsonArray();
            downloads.add("http://127.0.0.1:" + server.getAddress().getPort() + "/mod");
            file.add("downloads", downloads);
            index.getAsJsonArray("files").add(file);
            JsonObject optional = file.deepCopy();
            optional.addProperty("path", "mods/optional.jar");
            optional.add("env", JsonParser.parseString("{\"client\":\"optional\"}"));
            index.getAsJsonArray("files").add(optional);
            JsonObject excluded = file.deepCopy();
            excluded.addProperty("path", "mods/server.jar");
            excluded.add("env", JsonParser.parseString("{\"client\":\"unsupported\"}"));
            index.getAsJsonArray("files").add(excluded);
            Path zip = archive("remote.mrpack",
                Map.of("modrinth.index.json", index.toString(), "overrides/mods/optional.jar", "embedded optional override"));
            ModpackArchive.extract(zip, ModpackArchive.inspect(zip), temp.resolve("remote"), false, new HttpTransport(), task);
            assertArrayEquals(bytes, Files.readAllBytes(temp.resolve("remote/mods/remote.jar")));
            assertFalse(Files.exists(temp.resolve("remote/mods/optional.jar")));
            assertFalse(Files.exists(temp.resolve("remote/mods/server.jar")));
            ModpackArchive.extract(zip, ModpackArchive.inspect(zip), temp.resolve("optional-selected"), true,
                Set.of("mods/optional.jar", "mods/remote.jar"), new HttpTransport(), task);
            assertTrue(Files.isRegularFile(temp.resolve("optional-selected/mods/remote.jar")),
                "Required files cannot be excluded through optional selection");
            assertFalse(Files.exists(temp.resolve("optional-selected/mods/optional.jar")));
            hashes.addProperty("sha512", "0".repeat(128));
            Path bad = archive("bad.mrpack", Map.of("modrinth.index.json", index.toString()));
            assertThrows(IOException.class,
                () -> ModpackArchive.extract(bad, ModpackArchive.inspect(bad), temp.resolve("bad"), false, new HttpTransport(), task));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serverPackAddressesAcceptHmclHttpPrefixAndRejectCredentialsOrQueries() throws Exception {
        assertEquals("http://example.com/pack", ModpackArchive.baseUrl("http://example.com/pack/"));
        assertEquals("https://example.com/pack", ModpackArchive.baseUrl("https://example.com/pack/"));
        for (String invalid : List.of("file:///tmp/pack", "https://user:password@example.com/pack", "https://example.com/pack?token=secret",
            "https://example.com/pack#fragment")) {
            assertThrows(IOException.class, () -> ModpackArchive.baseUrl(invalid));
        }
    }

    @Test
    void nestedSelectionDeduplicatesPathsAndPreservesEmptyDirectoriesAcrossAllFormats() throws Exception {
        Path source = Files.createDirectories(temp.resolve("selected-source"));
        Files.createDirectories(source.resolve("mods"));
        Files.createDirectories(source.resolve("config/nested"));
        Files.createDirectories(source.resolve("empty"));
        Files.createDirectories(source.resolve("logs"));
        Files.writeString(source.resolve("mods/include.jar"), "include");
        Files.writeString(source.resolve("mods/exclude.jar"), "exclude");
        Files.writeString(source.resolve("config/nested/settings.json"), "config");
        Files.writeString(source.resolve("logs/latest.log"), "private log");
        Files.writeString(source.resolve("account.json"), "private account");
        GameInstance instance = new GameInstance("selected", "selected", "1.20.4", LoaderType.VANILLA, null, IsolationOverride.ISOLATED,
            4096);
        var entries = ModpackArchive.scanExport(source, instance, task);
        assertTrue(entries.stream().anyMatch(e -> e.path().equals("config/nested/settings.json")));
        assertTrue(entries.stream().anyMatch(e -> e.path().equals("logs/latest.log")));
        assertTrue(entries.stream().anyMatch(e -> e.path().equals("account.json")));
        for (var format : ModpackFormat.values()) {
            Path output = temp.resolve("selected-" + format + format.extension());
            ModpackArchive.export(source, instance, format, "selected", "1", "", "", "https://example.com/pack",
                Set.of("mods/include.jar", "config", "config/nested/settings.json", "empty"), output, false, task);
            try (ZipFile zip = new ZipFile(output.toFile())) {
                String prefix = format == ModpackFormat.MULTIMC ? ".minecraft/" : "overrides/";
                assertNotNull(zip.getEntry(prefix + "mods/include.jar"));
                assertNull(zip.getEntry(prefix + "mods/exclude.jar"));
                assertNull(zip.getEntry(prefix + "logs/latest.log"));
                assertNull(zip.getEntry(prefix + "account.json"));
                assertEquals(1, zip.stream().filter(e -> e.getName().equals(prefix + "config/nested/settings.json")).count());
                assertNotNull(zip.getEntry(prefix + "empty/"));
            }
            Path extracted = temp.resolve("selection-result-" + format);
            ModpackArchive.extract(output, ModpackArchive.inspect(output), extracted, false, new HttpTransport(), task);
            assertTrue(Files.isDirectory(extracted.resolve("empty")));
            assertFalse(Files.exists(extracted.resolve("mods/exclude.jar")));
        }
    }

    @Test
    void cancellationAndExistingOutputDoNotDestroyPreviousArchive() throws Exception {
        Path source = Files.createDirectories(temp.resolve("source"));
        Files.writeString(source.resolve("options.txt"), "settings");
        Path output = temp.resolve("existing.zip");
        Files.writeString(output, "original");
        GameInstance instance = new GameInstance("fixture", "fixture", "1.20.4", LoaderType.VANILLA, null, IsolationOverride.ISOLATED,
            4096);
        TaskContext cancelled = new TaskContext() {
            public void report(String m, double p) {
            }

            public boolean cancelled() {
                return true;
            }
        };
        assertThrows(CancellationException.class, () -> ModpackArchive.export(source, instance, ModpackFormat.MCBBS, "fixture", "1", "", "",
            "", Set.of("options.txt"), output, true, cancelled));
        assertEquals("original", Files.readString(output));
        assertThrows(IOException.class, () -> ModpackArchive.export(source, instance, ModpackFormat.MCBBS, "fixture", "1", "", "", "",
            Set.of("options.txt"), output, false, task));
        assertEquals("original", Files.readString(output));
    }
}
