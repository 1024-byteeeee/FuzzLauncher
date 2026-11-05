package com.fuzzlauncher.application.port;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface FileBrowser {
    record ClipboardContents(List<Path> paths, boolean move, int sequence) {
        public ClipboardContents {
            paths = List.copyOf(paths);
        }
    }

    boolean hasFiles();

    ClipboardContents readClipboard() throws IOException;

    void writeClipboard(List<Path> paths, boolean cut) throws IOException;

    void completeCut(ClipboardContents contents, List<Path> completed) throws IOException;

    Path paste(Path source, Path destination, boolean move, boolean rename) throws IOException;

    Path createFolder(Path parent, String name) throws IOException;

    Path rename(Path source, String name) throws IOException;

    void recycle(Path source) throws IOException;

    void reveal(Path source) throws IOException;
}
