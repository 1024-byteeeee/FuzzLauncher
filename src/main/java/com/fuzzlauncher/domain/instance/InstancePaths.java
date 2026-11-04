package com.fuzzlauncher.domain.instance;

import java.nio.file.Path;

public final class InstancePaths {
    private InstancePaths() {
    }

    public static Path directory(Path root, GameInstance instance) {
        return root.resolve("versions").resolve(instance.name()).normalize();
    }
}
