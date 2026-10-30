package com.fuzzlauncher.domain;

public record GameVersion(String id, String type, String url, String sha1) {
    @Override
    public String toString() {
        return id + "  [" + type + "]";
    }
}
