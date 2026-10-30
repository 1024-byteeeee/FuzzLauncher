package com.fuzzlauncher;

import com.fuzzlauncher.infrastructure.HttpTransport;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.net.ssl.SSLException;

import static org.junit.jupiter.api.Assertions.*;

class HttpTransportTest {
    @Test
    void httpRejectionPreservesStatusWithoutExposingResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] body = "private-response-content".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(403, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var failure = assertThrows(HttpTransport.HttpStatusException.class,
                () -> new HttpTransport().postJson("http://127.0.0.1:" + server.getAddress().getPort() + "/login", new JsonObject()));
            assertEquals(403, failure.statusCode());
            assertTrue(failure.getMessage().contains("127.0.0.1"));
            assertFalse(failure.getMessage().contains("private-response-content"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tlsFailureIsReportedAsConnectionFailureRatherThanAuthorizationRejection() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = Thread.startVirtualThread(() -> {
                while (!server.isClosed()) {
                    try (var socket = server.accept()) {
                        socket.getOutputStream()
                            .write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    } catch (IOException ignored) {
                        return;
                    }
                }
            });
            try {
                IOException failure = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(IOException.class,
                    () -> new HttpTransport().postJson("https://127.0.0.1:" + server.getLocalPort() + "/login", new JsonObject())));
                assertFalse(failure instanceof HttpTransport.HttpStatusException);
                assertInstanceOf(SSLException.class, failure.getCause());
                assertTrue(failure.getMessage().contains("HTTPS/TLS"));
                assertTrue(failure.getMessage().contains("127.0.0.1"));
                assertFalse(failure.getMessage().contains("登录被拒绝"));
            } finally {
                server.close();
                responder.join(1000);
            }
        }
    }
}
