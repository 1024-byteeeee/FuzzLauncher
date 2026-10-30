package com.fuzzlauncher.infrastructure.game;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A JDK-only log relay that survives launcher exit. Secrets travel through stdin, not argv/files.
 */
public final class DetachedGame {
    private DetachedGame() {
    }

    public static Process start(List<String> command, Path directory, Path log, String token) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.isRegularFile(java)) {
            java = Path.of(System.getProperty("java.home"), "bin", "java");
        }
        String classpath;
        try {
            classpath = Path.of(DetachedGame.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException ex) {
            throw new IOException("无法定位游戏日志辅助程序", ex);
        }
        Process relay = new ProcessBuilder(java.toString(), "-cp", classpath, DetachedGame.class.getName())
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try (DataOutputStream input = new DataOutputStream(relay.getOutputStream())) {
                input.writeInt(command.size());
                for (String argument : command) {
                    writeString(input, argument);
                }
                writeString(input, directory.toString());
                writeString(input, log.toString());
                writeString(input, token);
            }
            String ready = relay.inputReader(StandardCharsets.UTF_8).readLine();
            if (!"STARTED".equals(ready)) {
                throw new IOException("游戏日志辅助程序未能启动游戏");
            }
            return relay;
        } catch (IOException ex) {
            relay.destroyForcibly();
            throw ex;
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0) {
            throw new IOException("Invalid relay input length");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Incomplete relay input");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        List<String> command = new ArrayList<>();
        Path directory;
        Path log;
        String token;
        try (DataInputStream input = new DataInputStream(System.in)) {
            int count = input.readInt();
            if (count <= 0) {
                throw new IOException("Missing game command");
            }
            for (int i = 0; i < count; i++) {
                command.add(readString(input));
            }
            directory = Path.of(readString(input));
            log = Path.of(readString(input));
            token = readString(input);
        }
        // Open the log before starting the game so failure cannot leave an unmonitored game behind.
        int code;
        try (BufferedWriter output = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
            Process game = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            System.out.println("STARTED");
            System.out.flush();
            try (BufferedReader input = game.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = input.readLine()) != null) {
                    output.write(token.isEmpty() ? line : line.replace(token, "[REDACTED]"));
                    output.newLine();
                    output.flush();
                }
            }
            code = game.waitFor();
        }
        System.exit(code);
    }
}
