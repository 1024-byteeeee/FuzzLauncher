package com.fuzzlauncher.ui.task;

import com.fuzzlauncher.application.task.TaskContext;

import javafx.beans.property.*;
import javafx.concurrent.Task;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class FxTaskCoordinator implements AutoCloseable {
    @FunctionalInterface
    public interface Work<T> {
        T execute(TaskContext context) throws Exception;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("fuzz-operations").daemon(true).factory());
    private Task<?> active;
    private final Object commitGate = new Object();
    private boolean committing;
    private volatile boolean closed;
    private Object catalogOwner;
    private boolean catalogTask;
    private final BooleanSupplier savingSettings;
    private final Runnable finished;
    private final BooleanProperty busy = new SimpleBooleanProperty();
    private final DoubleProperty progress = new SimpleDoubleProperty();
    private final StringProperty status = new SimpleStringProperty("就绪"), error = new SimpleStringProperty("");

    public FxTaskCoordinator(BooleanSupplier savingSettings, Runnable finished) {
        this.savingSettings = savingSettings;
        this.finished = finished;
    }

    public BooleanProperty busyProperty() {
        return busy;
    }

    public DoubleProperty progressProperty() {
        return progress;
    }

    public StringProperty statusProperty() {
        return status;
    }

    public StringProperty errorProperty() {
        return error;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isCommitting() {
        synchronized (commitGate) {
            return committing;
        }
    }

    public void cancel() {
        if (savingSettings.getAsBoolean()) {
            return;
        }
        synchronized (commitGate) {
            if (!committing && active != null) {
                active.cancel(true);
            }
        }
    }

    public void cancelCatalogRequests(Object owner) {
        if (active != null && catalogTask && catalogOwner == owner) {
            Task<?> task = active;
            task.cancel(true);
            if (active == task) {
                finish(task);
            }
            if (!busy.get()) {
                status.set("已停止获取版本列表");
            }
        }
    }

    public <T> void submit(String title, Work<T> work, Consumer<T> success) {
        submit(title, work, success, null, false);
    }

    public <T> void submit(String title, Work<T> work, Consumer<T> success, Object owner, boolean catalog) {
        if (busy.get()) {
            return;
        }
        error.set("");
        busy.set(true);
        status.set(title);
        progress.set(-1);
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.execute(new TaskContext() {
                    @Override
                    public void report(String message, double value) {
                        updateMessage(message);
                        updateProgress(value < 0 ? -1 : value, 1);
                    }

                    @Override
                    public boolean cancelled() {
                        return isCancelled();
                    }

                    @Override
                    public void commit(TaskContext.Commit action) throws Exception {
                        synchronized (commitGate) {
                            checkCancelled();
                            committing = true;
                        }
                        action.run();
                    }
                });
            }
        };
        active = task;
        catalogOwner = owner;
        catalogTask = catalog;
        Logger.getLogger("com.fuzzlauncher.launcher").info(title);
        status.bind(task.messageProperty());
        progress.bind(task.progressProperty());
        task.setOnSucceeded(event -> {
            if (!finish(task)) {
                return;
            }
            success.accept(task.getValue());
            Logger.getLogger("com.fuzzlauncher.launcher").info(title + "完成");
        });
        task.setOnFailed(event -> {
            if (!finish(task)) {
                return;
            }
            Throwable cause = task.getException();
            while (cause instanceof ExecutionException || cause instanceof CompletionException) {
                cause = cause.getCause();
            }
            String message = Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
            status.set(title + "失败");
            Logger.getLogger("com.fuzzlauncher.launcher").warning(title + "失败：" + message);
            error.set(message);
        });
        task.setOnCancelled(event -> {
            if (!finish(task)) {
                return;
            }
            status.set(catalog ? "已停止获取版本列表" : "任务已取消");
            Logger.getLogger("com.fuzzlauncher.launcher").info(title + "已取消");
        });
        executor.execute(task);
    }

    private boolean finish(Task<?> task) {
        if (active != task) {
            return false;
        }
        active = null;
        synchronized (commitGate) {
            committing = false;
        }
        catalogTask = false;
        catalogOwner = null;
        status.unbind();
        progress.unbind();
        status.set(task.getMessage().isBlank() ? "就绪" : task.getMessage());
        progress.set(0);
        finished.run();
        busy.set(false);
        return true;
    }

    public void close() {
        closed = true;
        synchronized (commitGate) {
            if (committing) {
                executor.shutdown();
            } else {
                cancel();
                executor.shutdownNow();
            }
        }
    }

    public boolean awaitShutdown() {
        try {
            return executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
