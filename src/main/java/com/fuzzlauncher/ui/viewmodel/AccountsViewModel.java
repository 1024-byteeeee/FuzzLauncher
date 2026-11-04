package com.fuzzlauncher.ui.viewmodel;

import com.fuzzlauncher.application.port.AccountAuthenticator;
import com.fuzzlauncher.application.port.AccountAvatars;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.account.Account;
import com.fuzzlauncher.ui.task.FxTaskCoordinator;

import javafx.application.Platform;
import javafx.beans.property.*;

import java.io.IOException;
import java.util.concurrent.*;

public final class AccountsViewModel {
    private final FxTaskCoordinator tasks;
    private final BooleanProperty busy;
    private final StringProperty status, error;
    private final ObjectProperty<Account> account;
    private final BooleanProperty gameRunning;
    private final BooleanProperty loggingIn;
    private final AccountAuthenticator authentication;
    private final AccountAvatars avatarLoader;
    private final ObjectProperty<javafx.scene.image.Image> accountAvatar = new SimpleObjectProperty<>();
    private final ExecutorService avatarExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("account-avatar").factory());
    private Future<?> avatarWork;
    private volatile int avatarGeneration;
    private volatile boolean closed;

    AccountsViewModel(PresentationState state, FxTaskCoordinator tasks, AccountAuthenticator authentication, AccountAvatars avatarLoader)
        throws IOException {
        this.tasks = tasks;
        busy = tasks.busyProperty();
        status = tasks.statusProperty();
        error = tasks.errorProperty();
        account = state.account;
        gameRunning = state.gameRunning;
        loggingIn = state.loggingIn;
        this.authentication = authentication;
        this.avatarLoader = avatarLoader;
        account.addListener((observable, old, identity) -> loadAvatar(identity));
        account.set(authentication.savedAccount().orElse(null));
    }

    public ReadOnlyObjectProperty<javafx.scene.image.Image> accountAvatarProperty() {
        return accountAvatar;
    }

    private void loadAvatar(Account identity) {
        int generation = ++avatarGeneration;
        if (avatarWork != null) {
            avatarWork.cancel(true);
        }
        accountAvatar.set(null);
        if (identity == null || identity.isOffline() || avatarLoader == null || closed) {
            return;
        }
        avatarWork = avatarExecutor.submit(() -> {
            try {
                byte[] bytes = avatarLoader.load(identity, new TaskContext() {
                    public void report(String message, double progress) {
                    }

                    public boolean cancelled() {
                        return closed || generation != avatarGeneration || Thread.currentThread().isInterrupted();
                    }
                });
                if (bytes != null && !closed) {
                    Platform.runLater(() -> {
                        if (!closed && generation == avatarGeneration && identity.equals(account.get())) {
                            var image = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(bytes));
                            if (!image.isError()) {
                                accountAvatar.set(image);
                            } else {
                                java.util.logging.Logger.getLogger("com.fuzzlauncher.launcher").warning("头像 PNG 解码失败");
                            }
                        }
                    });
                }
            } catch (InterruptedException | CancellationException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                java.util.logging.Logger.getLogger("com.fuzzlauncher.launcher").warning("头像加载失败：" + ex.getMessage());
            }
        });
    }

    public void login() {
        if (!canLogin()) {
            return;
        }
        loggingIn.set(true);
        tasks.submit("微软登录", authentication::login, session -> {
            account.set(session.account());
            status.set("已登录微软账号: " + session.account().name());
        });
    }

    public void logout() {
        tasks.submit("退出登录", context -> {
            authentication.logout(context);
            return true;
        }, ignored -> {
            account.set(null);
            status.set("已退出登录");
        });
    }

    public void loginOffline(String name) {
        if (!canLogin()) {
            return;
        }
        tasks.submit("离线登录", context -> authentication.loginOffline(name, context), session -> {
            account.set(session.account());
            status.set("已使用离线账号: " + session.account().name());
        });
    }

    private boolean canLogin() {
        if (busy.get() || gameRunning.get()) {
            return false;
        }
        Account current = account.get();
        if (current != null) {
            error.set(current.isOffline() ? "请先退出当前离线账号，再登录" : "请先退出当前微软账号，再登录");
            return false;
        }
        return true;
    }

    public void close() {
        closed = true;
        avatarGeneration++;
        if (avatarWork != null) {
            avatarWork.cancel(true);
        }
        avatarExecutor.shutdownNow();
    }

    public boolean awaitShutdown() {
        try {
            return avatarExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
