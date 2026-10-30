package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.game.VersionCatalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VersionCatalogCacheTest {
    @TempDir
    Path temp;

    @Test
    void memoryDiskRefreshAndSourceIsolationUseLocalFixtures() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                String json = path.contains("manifest")
                    ? "{\"versions\":[{\"id\":\"1.21\",\"type\":\"release\",\"url\":\"https://example.org/game.json\"}]}"
                    : "[{\"loader\":{\"version\":\"0.16.0\"}}]";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            HttpTransport http = new HttpTransport(url -> "http://127.0.0.1:" + server.getAddress().getPort()
                + (url.equals(VersionCatalog.MANIFEST) ? "/manifest" : "/fabric"));
            VersionCatalog catalog = new VersionCatalog(http, temp);
            assertEquals("1.21", catalog.games().getFirst().id());
            assertEquals(catalog.games(), new VersionCatalog(http, temp).games());
            assertEquals(1, requests.get());
            List<GameVersion> displayed = new ArrayList<>();
            catalog.games(true, displayed::addAll);
            assertEquals("1.21", displayed.getFirst().id());
            assertEquals(2, requests.get());
            assertEquals(List.of("0.16.0"), catalog.loaders(LoaderType.FABRIC, "1.21"));
            catalog.loaders(LoaderType.FABRIC, "1.21");
            assertEquals(3, requests.get());
            catalog.loaders(LoaderType.FABRIC, "1.20");
            assertEquals(4, requests.get());
            http.setDownloadSource(DownloadSource.AUTO);
            catalog.games();
            assertEquals(5, requests.get());
            catalog.loaders(LoaderType.FABRIC, "1.21", true, values -> {
            });
            assertEquals(6, requests.get());
        } finally {
            server.stop(0);
        }
    }
}
