package com.fuzzlauncher.domain;

import java.nio.file.Path;

/** Sole source of truth for both --gameDir and process working directory. */
public final class IsolationPolicy {
    public Path gameDirectory(Path root, GameInstance instance, IsolationMode global) {
        boolean isolate = switch (instance.isolation()) {
            case ISOLATED -> true;
            case SHARED -> false;
            case INHERIT -> global == IsolationMode.ALL || global == IsolationMode.MODDED && instance.loader() != LoaderType.VANILLA;
        };
        return isolate ? root.resolve("versions").resolve(instance.name()).normalize() : root.normalize();
    }
}
