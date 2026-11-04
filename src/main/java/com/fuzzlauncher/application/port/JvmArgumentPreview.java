package com.fuzzlauncher.application.port;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
public interface JvmArgumentPreview {
    default boolean available() {
        return true;
    }

    static JvmArgumentPreview unavailable() {
        return new JvmArgumentPreview() {
            public boolean available() {
                return false;
            }

            public List<String> generated(String text) {
                return List.of();
            }
        };
    }

    List<String> generated(String editableArguments) throws IOException;
}
