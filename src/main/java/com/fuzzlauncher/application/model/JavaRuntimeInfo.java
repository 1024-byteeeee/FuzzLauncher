package com.fuzzlauncher.application.model;

import java.nio.file.Path;

/** A detected local Java runtime exposed to the presentation layer. */
public record JavaRuntimeInfo(Path executable, int major, boolean is64Bit) {
}
