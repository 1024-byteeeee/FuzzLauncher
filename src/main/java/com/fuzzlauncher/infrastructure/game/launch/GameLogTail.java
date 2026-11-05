package com.fuzzlauncher.infrastructure.game.launch;

import com.fuzzlauncher.application.model.RunningGame;
import com.fuzzlauncher.application.port.GameLauncher.LogSnapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class GameLogTail {
    private static final int MAX_BYTES = 128 * 1024;
    private static final int REFRESH_MILLIS = 300;

    private GameLogTail() {
    }

    public static void follow(RunningGame game, BooleanSupplier cancelled, Consumer<LogSnapshot> output)
        throws IOException, InterruptedException {
        long previousSize = -1;
        ByteBuffer buffer = ByteBuffer.allocate(MAX_BYTES);
        try (FileChannel channel = FileChannel.open(game.log())) {
            while (!cancelled.getAsBoolean()) {
                boolean finished = !game.process().isAlive();
                long size = channel.size();
                if (size != previousSize || finished) {
                    output.accept(new LogSnapshot(readTail(channel, buffer, size), size > MAX_BYTES));
                    previousSize = size;
                }
                if (finished) {
                    return;
                }
                Thread.sleep(REFRESH_MILLIS);
            }
        }
    }

    private static String readTail(FileChannel channel, ByteBuffer buffer, long size) throws IOException {
        long offset = Math.max(0, size - buffer.capacity());
        channel.position(offset);
        buffer.clear();
        buffer.limit((int) (size - offset));
        while (buffer.hasRemaining() && channel.read(buffer) > 0) {
            // Read only the bounded tail captured by this poll.
        }
        buffer.flip();
        if (offset > 0) {
            while (buffer.hasRemaining() && (buffer.get(buffer.position()) & 0xc0) == 0x80) {
                buffer.get();
            }
        }
        return StandardCharsets.UTF_8.decode(buffer).toString();
    }
}
