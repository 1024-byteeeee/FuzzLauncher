package com.fuzzlauncher.infrastructure.game.launch;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameLogTextTest {
    @Test
    void preservesPlainOutputAndDecodesEventsBeforeRedacting() throws Exception {
        String input = """
            plain output
            <log4j:Event level="WARN" thread="Render &amp; sound">
                <log4j:Message><![CDATA[Literal </log4j:Event>]]></log4j:Message>
                <log4j:Throwable>secret &amp; token</log4j:Throwable>
            </log4j:Event><log4j:Event level="INFO" thread="main"><log4j:Message>ready</log4j:Message></log4j:Event>
            <log4j:Event level="ERROR" thread="main"><log4j:Message><![CDATA[incomplete
            """;
        var text = new StringWriter();
        try (var reader = new BufferedReader(new StringReader(input)); var writer = new BufferedWriter(text)) {
            GameLogText.transfer(reader, writer, "secret & token");
        }
        assertEquals("""
            plain output
            [Render & sound/WARN]: Literal </log4j:Event>
            [REDACTED]
            [main/INFO]: ready
            incomplete
            """, text.toString().replace("\r\n", "\n"));
    }
}
