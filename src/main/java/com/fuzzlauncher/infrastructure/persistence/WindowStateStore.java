package com.fuzzlauncher.infrastructure.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Local presentation state, independent of the selected game and data directories. */
public final class WindowStateStore {
    private final Path file;

    public WindowStateStore(Path bootstrapDirectory) {
        file = bootstrapDirectory.resolve("window-state.json");
    }

    public Optional<State> load() throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        State state = JsonFiles.GSON.fromJson(JsonFiles.read(file), State.class);
        if (state == null || !valid(state.width()) || !valid(state.height())) {
            throw new IOException("无效的主窗口尺寸");
        }
        return Optional.of(state);
    }

    public void save(State state) throws IOException {
        JsonFiles.write(file, state);
    }

    private static boolean valid(double size) {
        return Double.isFinite(size) && size > 0;
    }

    public record State(double width, double height, boolean maximized) {
    }
}
