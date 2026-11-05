package com.fuzzlauncher.infrastructure.browser;

import com.fuzzlauncher.application.port.FileBrowser;
import com.fuzzlauncher.infrastructure.filesystem.BrowserFiles;
import com.fuzzlauncher.infrastructure.platform.windows.WindowsExplorer;
import com.fuzzlauncher.infrastructure.platform.windows.WindowsFileClipboard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class LocalFileBrowser implements FileBrowser {
    public boolean hasFiles() {
        return WindowsFileClipboard.hasFiles();
    }

    public ClipboardContents readClipboard() throws IOException {
        var contents = WindowsFileClipboard.read();
        return new ClipboardContents(contents.paths(), contents.move(), contents.sequence());
    }

    public void writeClipboard(List<Path> paths, boolean cut) throws IOException {
        WindowsFileClipboard.write(paths, cut);
    }

    public void completeCut(ClipboardContents contents, List<Path> completed) throws IOException {
        WindowsFileClipboard.completeCut(new WindowsFileClipboard.Contents(contents.paths(), contents.move(), contents.sequence()), completed);
    }

    public Path paste(Path source, Path destination, boolean move, boolean rename) throws IOException {
        return BrowserFiles.paste(source, destination, move, rename);
    }

    public Path createFolder(Path parent, String name) throws IOException {
        return BrowserFiles.createFolder(parent, name);
    }

    public Path rename(Path source, String name) throws IOException {
        return BrowserFiles.rename(source, name);
    }

    public void recycle(Path source) throws IOException {
        BrowserFiles.recycle(source);
    }

    public void reveal(Path source) throws IOException {
        WindowsExplorer.reveal(source);
    }
}
