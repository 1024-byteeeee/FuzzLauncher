package com.fuzzlauncher.infrastructure.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CatalogCacheTest {
    @TempDir
    Path temp;

    @Test
    void diskCacheSurvivesRestartExpiresAndRejectsFutureTimestamps() throws Exception {
        Instant now = Instant.parse("2026-10-30T00:00:00Z");
        CatalogCache cache = cache(now);
        cache.write("fabric|1.21", List.of("0.16.0"));
        var restored = cache(now.plusSeconds(10)).read("fabric|1.21", String[].class);
        assertEquals(List.of("0.16.0"), restored.values());
        assertTrue(cache(now).fresh(restored));
        assertFalse(cache(now.plus(CatalogCache.LIFETIME)).fresh(restored));
        assertFalse(cache(now.minusSeconds(1)).fresh(restored));
        assertNull(cache.read("fabric|1.20", String[].class));
        assertThrows(UnsupportedOperationException.class, () -> restored.values().add("wrong"));
    }

    @Test
    void corruptOrOversizedCacheIsIgnoredWithoutTouchingOtherFiles() throws Exception {
        var cache = cache(Instant.now());
        cache.write("game", List.of("version"));
        Path file;
        try (var files = Files.list(temp)) {
            file = files.findFirst().orElseThrow();
        }
        Files.writeString(file, "{broken");
        assertNull(cache(Instant.now()).read("game", String[].class));
        Files.writeString(file, " ".repeat(16 * 1024 * 1024 + 1));
        assertNull(cache(Instant.now()).read("game", String[].class));
        assertTrue(Files.exists(file));
    }

    private CatalogCache cache(Instant now) {
        return new CatalogCache(temp, Clock.fixed(now, ZoneOffset.UTC));
    }
}
