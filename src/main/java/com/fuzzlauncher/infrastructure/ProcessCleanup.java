package com.fuzzlauncher.infrastructure;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Terminates the probe/processor tree and waits before releasing its file handles. */
public final class ProcessCleanup {
    private ProcessCleanup() {
    }

    public static void terminate(Process process) throws IOException {
        boolean interrupted = Thread.interrupted();
        var children = process.descendants().toList();
        children.forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)) {
                if (System.nanoTime() >= deadline) {
                    throw new IOException("后台 Java 进程未能退出，PID：" + process.pid());
                }
                try {
                    if (process.isAlive()) {
                        process.waitFor(100, TimeUnit.MILLISECONDS);
                    } else {
                        Thread.sleep(50);
                    }
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
        } finally {
            try {
                process.getInputStream().close();
                process.getErrorStream().close();
                process.getOutputStream().close();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
