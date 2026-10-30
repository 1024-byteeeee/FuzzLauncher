package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.config.LauncherConfig;
import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.DownloadSource;

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLException;

public final class HttpTransport {
    private final Object downloadGate = new Object();
    private volatile int downloadConcurrency = LauncherConfig.get().network().concurrency();
    private int activeDownloads;

    public int downloadConcurrency() {
        return downloadConcurrency;
    }

    public void setDownloadConcurrency(int value) {
        if (value < 1 || value > 128) {
            throw new IllegalArgumentException("下载并行数应在 1–128 之间");
        }
        synchronized (downloadGate) {
            downloadConcurrency = value;
            downloadGate.notifyAll();
        }
    }

    private final LauncherConfig.Network network = LauncherConfig.get().network();
    private volatile DownloadSource downloadSource = DownloadSource.OFFICIAL;
    private final UnaryOperator<String> downloadUrl;
    private final java.util.concurrent.atomic.AtomicLong downloadedBytes = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ScheduledExecutorService DOWNLOAD_WATCHDOG = java.util.concurrent.Executors
        .newScheduledThreadPool(2, Thread.ofPlatform().daemon(true).name("download-watchdog-", 0).factory());

    public long downloadedBytes() {
        return downloadedBytes.get();
    }

    public HttpTransport() {
        downloadUrl = null;
    }

    HttpTransport(UnaryOperator<String> downloadUrl) {
        this.downloadUrl = Objects.requireNonNull(downloadUrl);
    }

    public void setDownloadSource(DownloadSource source) {
        downloadSource = Objects.requireNonNull(source);
    }

    private List<String> candidates(String url, boolean metadata) {
        if (downloadUrl == null) {
            return DownloadMirrors.candidates(downloadSource, url, metadata);
        }
        return java.util.stream.Stream.of(downloadUrl.apply(url), url).distinct().toList();
    }

    public static final class HttpStatusException extends IOException {
        private final int statusCode;

        public HttpStatusException(int statusCode, String host) {
            super("服务请求失败 HTTP " + statusCode + " (" + host + ")");
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(network.connectTimeoutSeconds())).build();

    public JsonObject getJson(String url) throws IOException, InterruptedException {
        IOException failure = null;
        for (String candidate : candidates(url, true)) {
            try {
                return json(HttpRequest.newBuilder(URI.create(candidate)).GET().build());
            } catch (IOException ex) {
                if (failure != null) {
                    ex.addSuppressed(failure);
                }
                failure = ex;
            }
        }
        throw Objects.requireNonNull(failure);
    }

    public JsonObject authorizedGet(String url, String token) throws IOException, InterruptedException {
        return json(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).GET().build());
    }

    public JsonObject postJson(String url, JsonObject body) throws IOException, InterruptedException {
        return json(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    private JsonObject json(HttpRequest request) throws IOException, InterruptedException {
        HttpRequest bounded = HttpRequest.newBuilder(request, (name, value) -> true)
            .timeout(Duration.ofSeconds(network.requestTimeoutSeconds()))
            .header("User-Agent", "FuzzLauncher/" + com.fuzzlauncher.Launcher.VERSION).build();
        HttpResponse<String> response;
        try {
            response = client.send(bounded, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof SSLException) {
                    throw new IOException("HTTPS/TLS 连接失败 (" + request.uri().getHost() + ")。请检查网络、系统代理及 HTTPS 拦截设置；尚未收到服务端的授权结果。", cause);
                }
            }
            throw new IOException("网络请求失败 (" + request.uri().getHost() + "): " + ex.getClass().getSimpleName(), ex);
        }
        if (response.statusCode() / 100 != 2) {
            throw new HttpStatusException(response.statusCode(), request.uri().getHost());
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IOException("服务返回无效 JSON: " + request.uri().getHost(), ex);
        }
    }

    public String getText(String url) throws IOException, InterruptedException {
        IOException failure = null;
        for (String candidate : candidates(url, true)) {
            try {
                return getTextDirect(candidate);
            } catch (IOException ex) {
                if (failure != null) {
                    ex.addSuppressed(failure);
                }
                failure = ex;
            }
        }
        throw Objects.requireNonNull(failure);
    }

    private String getTextDirect(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(network.requestTimeoutSeconds())).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("元数据请求失败 HTTP " + response.statusCode());
        }
        return response.body();
    }

    public void download(String url, Path destination, String sha1, TaskContext task) throws IOException, InterruptedException {
        synchronized (downloadGate) {
            while (activeDownloads >= downloadConcurrency) {
                task.checkCancelled();
                downloadGate.wait(100);
            }
            task.checkCancelled();
            activeDownloads++;
        }
        try {
            downloadFile(url, destination, sha1, task);
        } finally {
            synchronized (downloadGate) {
                activeDownloads--;
                downloadGate.notifyAll();
            }
        }
    }

    private void downloadFile(String url, Path destination, String sha1, TaskContext task) throws IOException, InterruptedException {
        task.checkCancelled();
        if (Files.isRegularFile(destination) && sha1 != null && !sha1.isBlank() && digest(destination, "SHA-1").equalsIgnoreCase(sha1)) {
            return;
        }
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Path partial = Files.createTempFile(destination.toAbsolutePath().getParent(), ".download-", ".part");
        List<String> urls = candidates(url, false);
        String previousUrl = null;
        String validator = null;
        boolean resumable = false;
        IOException failure = null;
        try {
            for (int attempt = 0; attempt < network.attempts(); attempt++) {
                task.checkCancelled();
                String candidate = urls.get(Math.min(attempt, urls.size() - 1));
                if (!candidate.equals(previousUrl)) {
                    if (previousUrl != null) {
                        task.report("切换备用下载地址：" + URI.create(candidate).getHost(), -1);
                    }
                    resumable = false;
                    validator = null;
                }
                previousUrl = candidate;
                long offset = resumable ? Files.size(partial) : 0;
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(candidate))
                    .timeout(Duration.ofSeconds(network.downloadTimeoutSeconds()))
                    .header("User-Agent", "FuzzLauncher/" + com.fuzzlauncher.Launcher.VERSION).header("Accept-Encoding", "identity").GET();
                if (offset > 0) {
                    request.header("Range", "bytes=" + offset + "-").header("If-Range", validator);
                }
                try {
                    HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream input = response.body()) {
                        int status = response.statusCode();
                        if (status != 200 && status != 206) {
                            resumable = false;
                            throw new IOException("下载失败 HTTP " + status + ": " + URI.create(candidate).getHost());
                        }
                        long total;
                        if (status == 206) {
                            var range = java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)")
                                .matcher(response.headers().firstValue("Content-Range").orElse(""));
                            if (offset == 0 || !range.matches() || Long.parseLong(range.group(1)) != offset
                                || Long.parseLong(range.group(2)) < offset
                                || Long.parseLong(range.group(3)) <= Long.parseLong(range.group(2))) {
                                resumable = false;
                                throw new IOException("服务器返回的续传范围无效");
                            }
                            total = Long.parseLong(range.group(3));
                        } else {
                            offset = 0;
                            total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                        }
                        String etag = response.headers().firstValue("ETag").filter(value -> !value.startsWith("W/")).orElse(null);
                        String freshValidator = etag != null ? etag : response.headers().firstValue("Last-Modified").orElse(null);
                        if (status == 206 && freshValidator != null && !freshValidator.equals(validator)) {
                            resumable = false;
                            throw new IOException("续传文件已变化，重新下载");
                        }
                        if (freshValidator != null || status == 200) {
                            validator = freshValidator;
                        }
                        resumable = validator != null && total >= 0
                            && response.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity")
                            && (status == 206 || response.headers().firstValue("Accept-Ranges").orElse("").equalsIgnoreCase("bytes"));
                        var lastRead = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
                        var stalled = new java.util.concurrent.atomic.AtomicBoolean();
                        Thread worker = Thread.currentThread();
                        var watchdog = DOWNLOAD_WATCHDOG.scheduleAtFixedRate(() -> {
                            if (worker.isInterrupted() || task.cancelled()
                                || System.nanoTime() - lastRead.get() > Duration.ofSeconds(network.idleTimeoutSeconds()).toNanos()) {
                                stalled.set(true);
                                try {
                                    input.close();
                                } catch (IOException ignored) {
                                }
                            }
                        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
                        long received = offset;
                        long start = System.nanoTime(), lastReport = start;
                        try (OutputStream output = Files.newOutputStream(partial, StandardOpenOption.WRITE,
                            offset > 0 ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                            byte[] buffer = new byte[128 * 1024];
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                task.checkCancelled();
                                output.write(buffer, 0, count);
                                received += count;
                                downloadedBytes.addAndGet(count);
                                long now = System.nanoTime();
                                lastRead.set(now);
                                if (now - lastReport >= Duration.ofMillis(network.progressIntervalMillis()).toNanos()) {
                                    double seconds = Math.max(0.001, (now - start) / 1e9);
                                    task.report(
                                        String.format(Locale.ROOT, "下载 %s · %.1f MiB · %.1f MiB/s", destination.getFileName(),
                                            received / 1048576.0, (received - offset) / 1048576.0 / seconds),
                                        total > 0 ? (double) received / total : -1);
                                    lastReport = now;
                                }
                            }
                            task.checkCancelled();
                            if (stalled.get()) {
                                throw new IOException("下载连接中断或连续 " + network.idleTimeoutSeconds() + " 秒没有接收到数据");
                            }
                            if (total >= 0 && received != total) {
                                throw new IOException("下载未完成，可从已接收位置续传");
                            }
                        } finally {
                            watchdog.cancel(false);
                        }
                    }
                    if (sha1 != null && !sha1.isBlank() && !digest(partial, "SHA-1").equalsIgnoreCase(sha1)) {
                        resumable = false;
                        throw new IOException("文件 SHA-1 校验失败: " + destination.getFileName());
                    }
                    task.checkCancelled();
                    try {
                        Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException ex) {
                        Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return;
                } catch (IOException ex) {
                    failure = ex;
                }
            }
            throw Objects.requireNonNull(failure);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    public static String digest(Path file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new java.io.InterruptedIOException("文件校验已取消");
                    }
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException(algorithm, ex);
        }
    }
}
