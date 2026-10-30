package com.fuzzlauncher.domain;

public enum ModpackFormat {
    MCBBS("MCBBS 整合包", ".zip"), MULTIMC("MultiMC", ".zip"), SERVER("服务器自动更新整合包", ".zip"), MODRINTH("Modrinth", ".mrpack");

    private final String label;
    private final String extension;

    ModpackFormat(String label, String extension) {
        this.label = label;
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    @Override
    public String toString() {
        return label;
    }
}
