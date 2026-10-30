package com.fuzzlauncher;

import com.fuzzlauncher.config.LauncherConfig;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.JsonFiles;
import com.fuzzlauncher.infrastructure.game.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LauncherConfigurationTest {
    @TempDir
    Path directory;

    private LauncherConfig load(String json) throws IOException {
        Path file = directory.resolve("launcher.json");
        Files.writeString(file, json);
        return LauncherConfig.load(file);
    }

    @Test
    void bundledDefaultsRemainCompatibleAndImmutable() throws Exception {
        LauncherConfig config = LauncherConfig.load(null);
        assertEquals(4096, config.defaults().minMemoryMb());
        assertEquals(4096, config.defaults().maxMemoryMb());
        assertEquals(List.of(), config.defaults().jvmArguments());
        assertEquals(3, config.network().attempts());
        assertThrows(UnsupportedOperationException.class, () -> config.defaults().jvmArguments().add("-ea"));
        assertThrows(UnsupportedOperationException.class, () -> config.services().clear());
        assertEquals(DownloadSource.MIRROR, JsonFiles.GSON.fromJson("\"NJU\"", DownloadSource.class));
        assertEquals(DownloadSource.MIRROR, JsonFiles.GSON.fromJson("\"BMCLAPI\"", DownloadSource.class));
    }

    @Test
    void partialOverrideInheritsMissingValuesWithoutChangingGlobalConfiguration() throws Exception {
        LauncherConfig original = LauncherConfig.get();
        LauncherConfig config = load("""
            {"schemaVersion": 1, "defaults": {"minMemoryMb": 2048, "maxMemoryMb": 8192},
             "network": {"attempts": 5, "maxConcurrency": 12}}
            """);
        assertEquals(2048, config.defaults().minMemoryMb());
        assertEquals(8192, config.defaults().maxMemoryMb());
        assertEquals("game", config.defaults().gameDirectory());
        assertEquals(5, config.network().attempts());
        assertEquals(10, config.network().connectTimeoutSeconds());
        assertTrue(config.network().concurrency() <= 12);
        assertSame(original, LauncherConfig.get());
    }

    @Test
    void invalidOverridesFailInsteadOfSilentlyUsingDefaults() {
        for (String json : List.of("{}", "{", "[]", "{\"schemaVersion\":2}", "{\"schemaVersion\":1,\"network\":{\"attempts\":0}}",
            "{\"schemaVersion\":1,\"network\":{\"attempts\":1.5}}", "{\"schemaVersion\":1,\"network\":{\"attempts\":4294967297}}",
            "{\"schemaVersion\":1,\"network\":{\"attempts\":\"3\"}}", "{\"schemaVersion\":1,\"network\":{\"attempts\":null}}",
            "{\"schemaVersion\":1,\"network\":{\"typo\":3}}", "{\"schemaVersion\":1,\"defaults\":{\"minMemoryMb\":8192}}",
            "{\"schemaVersion\":1,\"defaults\":{\"gameDirectory\":\"../game\"}}",
            "{\"schemaVersion\":1,\"defaults\":{\"isolation\":\"invalid\"}}",
            "{\"schemaVersion\":1,\"defaults\":{\"downloadSource\":\"invalid\"}}", "{\"schemaVersion\":1} {}",
            "{\"schemaVersion\":1,\"defaults\":{\"jvmArguments\":[\"-Xmx8G\"]}}")) {
            assertThrows(IOException.class, () -> load(json), json);
        }
    }

    @Test
    void releaseServicesAndAuthenticationCannotBeOverriddenLocally() {
        for (String section : List.of("services", "authentication", "downloads", "java")) {
            assertThrows(IOException.class, () -> load("{\"schemaVersion\":1,\"" + section + "\":{}}"));
        }
    }

    @Test
    void routingDataPreservesPathsAndQueryStrings() throws Exception {
        var config = LauncherConfig.load(null);
        URI maven = URI.create("https://maven.neoforged.net/releases/net/neoforged/neoforge/a.jar?key=a%2Fb");
        String mirror = config.downloads().rules().stream().map(rule -> rule.resolve(maven)).flatMap(Optional::stream).findFirst()
            .orElseThrow();
        assertEquals("https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/a.jar?key=a%2Fb", mirror);
        for (var rule : config.downloads().rules()) {
            assertTrue(rule.resolve(URI.create("https://api.minecraftservices.com/minecraft/profile")).isEmpty());
            assertTrue(rule.resolve(URI.create("https://login.microsoftonline.com/consumers/")).isEmpty());
        }
    }

    @Test
    void runtimeAndLoaderRulesHaveASingleSource() throws Exception {
        LauncherConfig config = LauncherConfig.load(null);
        assertEquals(16, config.java().requiredMajor("1.17.1"));
        assertEquals(17, config.java().requiredMajor("1.20.1"));
        assertEquals(8, config.java().requiredMajor("1.16.5"));
        assertEquals("forge", config.loaderArtifact(LoaderType.NEOFORGE, "1.20.1"));
        assertEquals("neoforge", config.loaderArtifact(LoaderType.NEOFORGE, "1.21.1"));
        assertTrue(config.loaderMaven(LoaderType.NEOFORGE, "1.20.1").endsWith("/forge/"));
        JavaRuntimeManager manager = new JavaRuntimeManager(directory);
        assertEquals(21, manager.requiredMajor(
            JsonFiles.GSON.fromJson("{\"id\":\"1.20.1\",\"javaVersion\":{\"majorVersion\":21}}", com.google.gson.JsonObject.class)));
    }

    @Test
    void argumentEditorAndLauncherUseTheSameDefaultArguments() throws Exception {
        GameInstance instance = new GameInstance("config-test", "Config test", "1.21.1", LoaderType.VANILLA, "", IsolationOverride.ISOLATED,
            8192, "", 2048, List.of(), List.of());
        List<String> defaults = LaunchArguments.defaultJvm(instance);
        assertEquals(List.of("-Xms2048M", "-Xmx8192M"), defaults);
        var parsed = LaunchArguments.parseMemory(LaunchArguments.display(defaults) + " -ea");
        assertEquals(2048, parsed.minimum());
        assertEquals(8192, parsed.maximum());
        assertEquals(List.of("-ea"), parsed.jvmArguments());
    }
}
