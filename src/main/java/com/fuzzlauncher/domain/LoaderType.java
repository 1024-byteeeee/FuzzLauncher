package com.fuzzlauncher.domain;

public enum LoaderType {
    VANILLA("原版"), FABRIC("Fabric"), FORGE("Forge"), NEOFORGE("NeoForge");

    private final String label;

    LoaderType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
