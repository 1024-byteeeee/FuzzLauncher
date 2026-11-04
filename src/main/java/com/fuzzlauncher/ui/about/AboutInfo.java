package com.fuzzlauncher.ui.about;

import com.google.gson.Gson;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bundled product information and user-facing links. */
public record AboutInfo(String name, String description, String author, String repository, String issues) {
    public AboutInfo {
        required(name);
        required(description);
        required(author);
        website(repository);
        website(issues);
    }

    public static AboutInfo load() {
        try (var input = AboutInfo.class.getResourceAsStream("/com/fuzzlauncher/about.json")) {
            if (input == null) {
                throw new IllegalStateException("缺少关于页面资源：about.json");
            }
            return Objects.requireNonNull(new Gson().fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), AboutInfo.class));
        } catch (IOException ex) {
            throw new UncheckedIOException("无法读取关于页面资源", ex);
        }
    }

    private static void required(String value) {
        if (value == null || value.isBlank() || value.contains("${")) {
            throw new IllegalArgumentException("关于页面资源存在缺失或未展开的字段");
        }
    }

    private static void website(String value) {
        required(value);
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("关于页面网址无效：" + value);
        }
    }
}
