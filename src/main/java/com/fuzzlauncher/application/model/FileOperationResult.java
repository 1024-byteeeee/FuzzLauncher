package com.fuzzlauncher.application.model;

import java.util.List;

public record FileOperationResult(int completed, List<String> failures) {
    public FileOperationResult {
        failures = List.copyOf(failures);
    }
}
