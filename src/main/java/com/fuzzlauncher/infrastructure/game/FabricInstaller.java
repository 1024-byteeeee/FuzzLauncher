package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.GameVersion;
import com.fuzzlauncher.infrastructure.HttpTransport;

import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class FabricInstaller implements LoaderInstaller {
    private final HttpTransport http;

    public FabricInstaller(HttpTransport http) {
        this.http = http;
    }

    @Override
    public JsonObject install(Path root, Path staging, GameVersion game, String loaderVersion, TaskContext task) throws Exception {
        task.checkCancelled();
        task.report("生成 Fabric 安装描述", -1);
        String gameId = URLEncoder.encode(game.id(), StandardCharsets.UTF_8).replace("+", "%20");
        String loaderId = URLEncoder.encode(loaderVersion, StandardCharsets.UTF_8);
        return http.getJson("https://meta.fabricmc.net/v2/versions/loader/" + gameId + "/" + loaderId + "/profile/json");
    }
}
