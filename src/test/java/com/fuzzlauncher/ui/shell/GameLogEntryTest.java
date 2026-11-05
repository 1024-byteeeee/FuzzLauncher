package com.fuzzlauncher.ui.shell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameLogEntryTest {
    @Test
    void classifiesPlainLinesAndStackFrames() {
        String text = """
            [15:45:19] [Render thread/ERROR]: plain failure
                at example.Game.main(Game.java:10)
            [15:45:20] [Render thread/WARN]: warning
            ordinary output
            """;
        var entries = GameLogEntry.parse(text, 1000);
        assertEquals(List.of(GameLogEntry.Level.ERROR, GameLogEntry.Level.ERROR, GameLogEntry.Level.WARN, GameLogEntry.Level.OTHER),
            entries.stream().map(GameLogEntry::level).toList());
    }

    @Test
    void keepsTheLatestEntries() {
        var entries = GameLogEntry.parse("[INFO] older\n[DEBUG] visible\n[main/ERROR]: kept", 2);
        assertEquals(List.of(new GameLogEntry(GameLogEntry.Level.DEBUG, "[DEBUG] visible"),
            new GameLogEntry(GameLogEntry.Level.ERROR, "[main/ERROR]: kept")), entries);
    }
}
