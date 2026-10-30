package com.fuzzlauncher;

import com.fuzzlauncher.config.LauncherConfig;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.JsonFiles;
import com.fuzzlauncher.infrastructure.game.*;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JvmDefaultsTest {
    private JvmEnvironment environment(int major, boolean hotspot, long memory, Set<String> flags) {
        return new JvmEnvironment(major, true, hotspot, memory, "GBK", flags);
    }

    @Test
    void automaticDownloadCountAndLegacySettingsMatchHmcl() {
        LauncherSettings old = JsonFiles.GSON.fromJson("{\"gameRoot\":\"game\",\"isolation\":\"ALL\"}", LauncherSettings.class);
        assertTrue(old.autoDownloadThreads());
        assertEquals(64, old.downloadThreads());
        assertEquals(Math.min(Runtime.getRuntime().availableProcessors() * 4, 64), old.effectiveDownloadThreads());
        LauncherSettings manual = new LauncherSettings("game", IsolationMode.ALL, DownloadSource.AUTO, "", LauncherVisibility.KEEP, false,
            5);
        assertEquals(5, manual.effectiveDownloadThreads());
        assertEquals(manual, JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(manual), LauncherSettings.class));
        assertThrows(IllegalArgumentException.class,
            () -> new LauncherSettings("game", IsolationMode.ALL, DownloadSource.AUTO, "", LauncherVisibility.KEEP, false, 0));
    }

    @Test
    void collectorChoiceAndExplicitSettingsOverrideDefaults() {
        var env = environment(21, true, 16L << 30,
            Set.of("UnlockExperimentalVMOptions", "UnlockDiagnosticVMOptions", "UseG1GC", "MaxGCPauseMillis", "ReservedCodeCacheSize"));
        List<String> generated = JvmDefaults.generate(env,
            List.of("-XX:+UseZGC", "-XX:ReservedCodeCacheSize=256M", "-Dfile.encoding=UTF-8"));
        assertFalse(generated.contains("-XX:+UseG1GC"));
        assertFalse(generated.stream().anyMatch(value -> value.startsWith("-XX:MaxGCPauseMillis")));
        assertFalse(generated.stream().anyMatch(value -> value.startsWith("-XX:ReservedCodeCacheSize")));
        assertFalse(generated.stream().anyMatch(value -> value.startsWith("-Dfile.encoding=")));
        assertTrue(generated.contains("-Dstdout.encoding=UTF-8"));
        assertFalse(JvmDefaults.generate(env, List.of("-XX:-UseG1GC")).contains("-XX:+UseG1GC"));
    }

    @Test
    void unsupportedFlagsAndNonHotspotRuntimesDoNotReceiveHotspotTuning() {
        List<String> sparse = JvmDefaults.generate(environment(21, true, 16L << 30, Set.of("UseG1GC")), List.of());
        assertTrue(sparse.contains("-XX:+UseG1GC"));
        assertFalse(sparse.stream().anyMatch(value -> value.startsWith("-XX:MaxNodeLimit")));
        List<String> openj9 = JvmDefaults.generate(environment(21, false, 16L << 30, Set.of("UseG1GC")), List.of());
        assertFalse(openj9.stream().anyMatch(value -> value.startsWith("-XX:")));
        assertTrue(openj9.contains("-Dfile.encoding=GBK"));
        assertFalse(JvmDefaults.generate(environment(21, true, 16L << 30, Set.of("UseG1GC")), List.of("-XX:-UnlockExperimentalVMOptions"))
            .contains("-XX:+UseG1GC"));
    }

    @Test
    void versionAndArchitectureRulesAreConditional() {
        assertTrue(JvmDefaults.generate(environment(8, true, 4L << 30, Set.of()), List.of()).contains("-Dsun.stdout.encoding=GBK"));
        assertTrue(JvmDefaults.generate(environment(21, true, 4L << 30, Set.of()), List.of()).contains("-Dstdout.encoding=GBK"));
        Set<String> flags = Set.of("ReservedCodeCacheSize", "UseCompactObjectHeaders");
        assertFalse(JvmDefaults.generate(environment(21, true, 4L << 30, flags), List.of()).contains("-XX:ReservedCodeCacheSize=400M"));
        assertTrue(JvmDefaults.generate(environment(25, true, 8L << 30, flags), List.of()).contains("-XX:+UseCompactObjectHeaders"));
        assertFalse(JvmDefaults.generate(environment(21, true, 8L << 30, flags), List.of()).contains("-XX:+UseCompactObjectHeaders"));
        var small = new JvmEnvironment(8, false, true, 4L << 30, "GBK", Set.of());
        assertTrue(JvmDefaults.generate(small, List.of()).contains("-Xss1m"));
        assertFalse(JvmDefaults.generate(small, List.of("-Xss2m")).contains("-Xss1m"));
        assertEquals(List.of(), LauncherConfig.get().defaults().jvmArguments());
    }

    @Test
    void logDecoderUsesEffectiveRuntimeOutputEncoding() throws Exception {
        var java21 = environment(21, true, 4L << 30, Set.of());
        assertEquals("GBK", JvmDefaults.outputEncoding(java21, List.of()).name());
        assertEquals("UTF-8", JvmDefaults.outputEncoding(java21, List.of("-Dfile.encoding=GBK", "-Dstdout.encoding=UTF-8")).name());
        assertEquals("UTF-8",
            JvmDefaults.outputEncoding(environment(8, true, 4L << 30, Set.of()), List.of("-Dsun.stdout.encoding=UTF-8")).name());
    }
}
