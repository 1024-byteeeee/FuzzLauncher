package com.fuzzlauncher.application.model;

import java.nio.file.Path;

public record RunningGame(Process process, Path log) {
}
