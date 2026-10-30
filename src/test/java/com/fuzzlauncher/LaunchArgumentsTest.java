package com.fuzzlauncher;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.LaunchArguments;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LaunchArgumentsTest {
    @TempDir
    Path temp;

    @Test
    void customArgumentsAppendAfterOfficialOnes() throws Exception {
        GameInstance instance = new GameInstance("a", "A", "1.20.4", LoaderType.VANILLA, null, IsolationOverride.INHERIT, 4096)
            .withLaunchArguments(1024, 4096, List.of("-XX:+UseG1GC", "-Dfoo=bar"), List.of("--server", "mc.example.com"));
        var version = JsonParser.parseString("""
            {"_clientVersion":"1.20.4","mainClass":"Main",
             "arguments":{"jvm":["-Djava.library.path=${natives_directory}","-cp","${classpath}"],
                          "game":["--username","${auth_player_name}"]}}
            """).getAsJsonObject();
        var args = new LaunchArguments().build(temp, temp, temp.resolve("natives"), instance, GameSession.offline(Account.offline("Steve")),
            version, List.of());
        int jvmCustom = args.indexOf("-XX:+UseG1GC");
        int main = args.indexOf("Main");
        assertTrue(jvmCustom > 0 && jvmCustom < main);
        assertTrue(args.indexOf("-Xms1024M") < jvmCustom);
        assertTrue(args.indexOf("-Xmx4096M") < jvmCustom);
        int officialGame = args.indexOf("--username");
        int gameCustom = args.indexOf("--server");
        assertTrue(officialGame > main && gameCustom > officialGame);
    }

    @Test
    void parseMemoryHandlesFormsAndStripsGeneratedTokens() throws Exception {
        var parsed = LaunchArguments.parseMemory("-Xms1024M -Xmx 4096M -Dfile.encoding=UTF-8 -XX:+UseG1GC");
        assertEquals(1024, parsed.minimum());
        assertEquals(4096, parsed.maximum());
        assertEquals(List.of("-Dfile.encoding=UTF-8", "-XX:+UseG1GC"), parsed.jvmArguments());

        parsed = LaunchArguments.parseMemory("-Xmx4G -Dfile.encoding=UTF-8");
        assertEquals(4096, parsed.maximum());
        assertNull(parsed.minimum());
        assertEquals(List.of("-Dfile.encoding=UTF-8"), parsed.jvmArguments());

        parsed = LaunchArguments.parseMemory("-Dfile.encoding=GBK -XX:+UseG1GC");
        assertNull(parsed.minimum());
        assertNull(parsed.maximum());
        assertEquals(List.of("-Dfile.encoding=GBK", "-XX:+UseG1GC"), parsed.jvmArguments());

        parsed = LaunchArguments.parseMemory("-Xmx3000M -Xmx6000M");
        assertEquals(6000, parsed.maximum());
        assertEquals(List.of(), parsed.jvmArguments());

        parsed = LaunchArguments.parseMemory("-Xmx 12M 34M");
        assertEquals(12, parsed.maximum());
        assertEquals(List.of("34M"), parsed.jvmArguments());

        parsed = LaunchArguments.parseMemory("-Dfoo=\"a b\"");
        assertNull(parsed.minimum());
        assertEquals(List.of("-Dfoo=a b"), parsed.jvmArguments());
    }

    @Test
    void parseMemoryRejectsInvalidValues() {
        assertThrows(IOException.class, () -> LaunchArguments.parseMemory("-Xmxabc"));
        assertThrows(IOException.class, () -> LaunchArguments.parseMemory("-Xmx"));
        assertThrows(IOException.class, () -> LaunchArguments.parseMemory("-Xms 12M -Xms"));
        assertThrows(IOException.class, () -> LaunchArguments.parseMemory("-Xmx99999G"));
    }

    @Test
    void displayQuotesTokensWithSpaces() {
        assertEquals("", LaunchArguments.display(List.of()));
        assertEquals("-Xmx4096M", LaunchArguments.display(List.of("-Xmx4096M")));
        assertEquals("\"-Dfoo=a b\" -Dbar=x", LaunchArguments.display(List.of("-Dfoo=a b", "-Dbar=x")));
    }

    @Test
    void launchArgumentsPersistPerInstanceAndOldFilesRemainCompatible() throws Exception {
        GameInstance old = JsonFiles.GSON.fromJson("""
            {"id":"old","name":"Old","gameVersion":"1.20.4","loader":"VANILLA","isolation":"INHERIT","memoryMb":4096}
            """, GameInstance.class);
        assertEquals(List.of(), old.jvmArguments());
        assertEquals(List.of(), old.gameArguments());
        GameInstance customized = old.withLaunchArguments(2048, 4096, List.of("-XX:+UseG1GC"), List.of("--server", "a b"));
        var repo = new JsonInstanceRepository(temp);
        repo.save(customized);
        assertEquals(customized, repo.findAll().getFirst());
        GameInstance renamed = customized.withSettings("Renamed", old.isolation(), 6000);
        assertEquals(List.of("-XX:+UseG1GC"), renamed.jvmArguments());
        assertEquals(List.of("--server", "a b"), renamed.gameArguments());
        assertEquals(2048, renamed.minMemoryMb());
        GameInstance typed = customized.withSettings("Typed", old.isolation(), 1024, 5000, "");
        assertEquals(List.of("-XX:+UseG1GC"), typed.jvmArguments());
        assertEquals(List.of("--server", "a b"), typed.gameArguments());
    }
}
