package com.fuzzlauncher.infrastructure.game.launch;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class GameLogText {
    private static final Pattern BOUNDARY = Pattern.compile("<!\\[CDATA\\[|\\]\\]>|<log4j:Event(?=[\\s>])|</log4j:Event\\s*>");
    private static final Pattern MARKUP = Pattern.compile("</?log4j:[^>\\r\\n]*>|<!\\[CDATA\\[|\\]\\]>");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private GameLogText() {
    }

    static void transfer(BufferedReader input, BufferedWriter output, String token) throws IOException {
        transfer(input::readLine, output, token);
    }

    static void transfer(InputStream input, Charset consoleEncoding, BufferedWriter output, String token) throws IOException {
        if (consoleEncoding.equals(StandardCharsets.UTF_8)
            || !Arrays.equals("\r\n".getBytes(consoleEncoding), new byte[] {13, 10})) {
            transfer(new BufferedReader(new InputStreamReader(input, consoleEncoding)), output, token);
            return;
        }
        var stream = new BufferedInputStream(input);
        var line = new ByteArrayOutputStream();
        var utf8 = StandardCharsets.UTF_8.newDecoder();
        transfer(() -> {
            line.reset();
            int next = stream.read();
            if (next == -1) {
                return null;
            }
            while (next != -1 && next != '\n' && next != '\r') {
                line.write(next);
                next = stream.read();
            }
            if (next == '\r') {
                stream.mark(1);
                if (stream.read() != '\n') {
                    stream.reset();
                }
            }
            byte[] bytes = line.toByteArray();
            // Log4j can write UTF-8 bytes directly even when the JVM console uses GBK.
            try {
                return utf8.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException ex) {
                return new String(bytes, consoleEncoding);
            }
        }, output, token);
    }

    @FunctionalInterface
    private interface LineReader {
        String readLine() throws IOException;
    }

    private static void transfer(LineReader input, BufferedWriter output, String token) throws IOException {
        XMLInputFactory factory = xmlFactory();
        StringBuilder event = new StringBuilder();
        boolean cdata = false;
        String line;
        while ((line = input.readLine()) != null) {
            var matcher = BOUNDARY.matcher(line);
            int offset = 0;
            while (matcher.find()) {
                String boundary = matcher.group();
                if (cdata) {
                    cdata = !boundary.equals("]]>");
                } else if (boundary.equals("<![CDATA[") && !event.isEmpty()) {
                    cdata = true;
                } else if (boundary.startsWith("<log4j:Event")) {
                    String prefix = line.substring(offset, matcher.start());
                    if (!event.isEmpty()) {
                        write(output, withoutMarkup(event.append(prefix).toString()), token);
                        event.setLength(0);
                    } else if (!prefix.isBlank()) {
                        write(output, prefix, token);
                    }
                    offset = matcher.start();
                    // Keep the event open even when the start tag is the only content on the line.
                    event.append(boundary);
                    offset += boundary.length();
                } else if (boundary.startsWith("</log4j:Event") && !event.isEmpty()) {
                    event.append(line, offset, matcher.end());
                    write(output, formatEvent(event.toString(), factory), token);
                    event.setLength(0);
                    offset = matcher.end();
                }
            }
            if (!event.isEmpty()) {
                event.append(line, offset, line.length()).append('\n');
            } else if (offset == 0 || !line.substring(offset).isBlank()) {
                write(output, line.substring(offset), token);
            }
        }
        if (!event.isEmpty()) {
            write(output, withoutMarkup(event.toString()), token);
        }
    }

    private static String withoutMarkup(String event) {
        return MARKUP.matcher(event).replaceAll("").strip();
    }

    private static void write(BufferedWriter output, String line, String token) throws IOException {
        output.write(token.isEmpty() ? line : line.replace(token, "[REDACTED]"));
        output.newLine();
        output.flush();
    }

    private static XMLInputFactory xmlFactory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    private static String formatEvent(String event, XMLInputFactory factory) {
        try {
            var reader = factory.createXMLStreamReader(new StringReader(event));
            try {
                reader.nextTag();
                String timestamp = reader.getAttributeValue(null, "timestamp");
                String thread = Objects.requireNonNullElse(reader.getAttributeValue(null, "thread"), "?");
                String rawLevel = Objects.requireNonNullElse(reader.getAttributeValue(null, "level"), "OTHER");
                StringBuilder message = new StringBuilder();
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                        && Set.of("log4j:Message", "log4j:Throwable").contains(reader.getLocalName())) {
                        if (!message.isEmpty()) {
                            message.append('\n');
                        }
                        message.append(reader.getElementText());
                    }
                }
                String time = "";
                if (timestamp != null) {
                    try {
                        time = "[" + TIME.format(Instant.ofEpochMilli(Long.parseLong(timestamp))) + "] ";
                    } catch (NumberFormatException ignored) {
                        // The message can still be displayed without a valid timestamp.
                    }
                }
                return time + "[" + thread + "/" + rawLevel + "]: " + message;
            } finally {
                reader.close();
            }
        } catch (XMLStreamException ex) {
            return withoutMarkup(event);
        }
    }
}
