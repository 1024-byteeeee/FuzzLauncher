package com.fuzzlauncher.ui.shell;

import com.fuzzlauncher.ModFixtures;
import com.fuzzlauncher.application.model.LauncherInfo;
import com.fuzzlauncher.application.model.ModpackContent;
import com.fuzzlauncher.application.model.RunningGame;
import com.fuzzlauncher.application.port.AccountAuthenticator;
import com.fuzzlauncher.application.port.GameLauncher;
import com.fuzzlauncher.application.port.InstanceRepository;
import com.fuzzlauncher.application.port.JvmArgumentPreview;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.bootstrap.FuzzApplication;
import com.fuzzlauncher.bootstrap.StartupRecovery;
import com.fuzzlauncher.bootstrap.ViewModels;
import com.fuzzlauncher.domain.account.Account;
import com.fuzzlauncher.domain.account.GameSession;
import com.fuzzlauncher.domain.game.GameVersion;
import com.fuzzlauncher.domain.game.LoaderType;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.instance.IsolationMode;
import com.fuzzlauncher.domain.instance.IsolationOverride;
import com.fuzzlauncher.domain.content.ContentFile;
import com.fuzzlauncher.domain.modpack.ModpackFormat;
import com.fuzzlauncher.domain.settings.DownloadSource;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.domain.settings.LauncherVisibility;
import com.fuzzlauncher.infrastructure.auth.MicrosoftAuthentication;
import com.fuzzlauncher.infrastructure.config.SettingsDefaults;
import com.fuzzlauncher.infrastructure.filesystem.BaseVersions;
import com.fuzzlauncher.infrastructure.filesystem.InstanceDirectories;
import com.fuzzlauncher.infrastructure.game.catalog.VersionCatalog;
import com.fuzzlauncher.infrastructure.game.catalog.VersionResolver;
import com.fuzzlauncher.infrastructure.game.install.GameFiles;
import com.fuzzlauncher.infrastructure.game.install.LoaderInstaller;
import com.fuzzlauncher.infrastructure.game.install.LocalGameInstallation;
import com.fuzzlauncher.infrastructure.game.launch.DetachedGame;
import com.fuzzlauncher.infrastructure.game.launch.GameLogTail;
import com.fuzzlauncher.infrastructure.modpack.LocalModpackOperations;
import com.fuzzlauncher.infrastructure.modpack.LocalModpacks;
import com.fuzzlauncher.infrastructure.modpack.ModpackArchive;
import com.fuzzlauncher.infrastructure.network.HttpTransport;
import com.fuzzlauncher.infrastructure.persistence.JsonFiles;
import com.fuzzlauncher.infrastructure.persistence.JsonInstanceRepository;
import com.fuzzlauncher.infrastructure.persistence.SettingsStore;
import com.fuzzlauncher.infrastructure.platform.ProcessCleanup;
import com.fuzzlauncher.infrastructure.recovery.OperationJournal;
import com.fuzzlauncher.ui.accounts.AccountsPage;
import com.fuzzlauncher.ui.common.RetroPage;
import com.fuzzlauncher.ui.common.RetroPathDialog;
import com.fuzzlauncher.ui.modpack.ModpackDialogs;
import com.fuzzlauncher.ui.modpack.ModpackFileTree;
import com.fuzzlauncher.ui.settings.SettingsPage;
import com.fuzzlauncher.ui.viewmodel.LauncherViewModel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

import static java.awt.Color.BLUE;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "fuzz.uiTests", matches = "true")
class UiSmokeTest {
    @TempDir
    Path temp;

    @Test
    void navigationSelectionAndAllPageLayouts() throws Exception {
        JsonInstanceRepository repository = new JsonInstanceRepository(temp);
        repository.save(new GameInstance("first", "原版实例", "1.20.4", LoaderType.VANILLA, null, IsolationOverride.INHERIT, 4096));
        repository.save(new GameInstance("second", "Fabric 实例", "1.21.1", LoaderType.FABRIC, "0.16.0", IsolationOverride.ISOLATED, 4096));
        System.setProperty("fuzz.dataDir", temp.toString());
        new SettingsStore(temp).save(SettingsDefaults.create(temp.resolve("game").toString(), IsolationMode.ALL));
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            FuzzApplication app = new FuzzApplication();
            Stage stage = new Stage();
            Throwable failure = null;
            try {
                app.start(stage);
                LauncherView view = (LauncherView) stage.getScene().getRoot();
                view.applyCss();
                view.layout();
                verifyWindowResize(view);
                verifyNavigationLayout(view);
                ListView<?> list = (ListView<?>) view.lookup(".list-view");
                Node originalForm = view.lookup("#instance-settings-form");
                Node originalImage = view.lookup("#instance-preview-image");
                list.getSelectionModel().select(1);
                assertSame(originalForm, view.lookup("#instance-settings-form"));
                assertSame(originalImage, view.lookup("#instance-preview-image"));
                view.applyCss();
                view.layout();
                assertTrue(view.lookupAll(".text-field").stream().filter(TextField.class::isInstance).map(TextField.class::cast)
                    .anyMatch(field -> field.getText().equals("1.21.1")));
                verifyInstanceSelectionSync(view);
                Files.createDirectories(Path.of("build/ui-check"));
                for (int page = 0; page < 8; page++) {
                    view.showPage(page);
                    view.applyCss();
                    view.layout();
                    for (Node field : view.lookupAll(".text-field")) {
                        var bounds = field.localToScene(field.getBoundsInLocal());
                        assertTrue(bounds.getMaxX() <= stage.getScene().getWidth() + 2, "Field overflow on page " + page);
                    }
                    ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png",
                        Path.of("build/ui-check/page-" + page + ".png").toFile());
                }
                verifyAboutPage(stage, view);
                verifyClearLogs(view);
                verifyLocalMods(stage, view);
                verifyDropdownHighlight(view);
                verifyDisabledMenuHighlight(stage);
                verifyResourcePacks(stage, view);
                verifySchematics(stage, view);
                verifyLauncherVisibility(stage, view, app);
                verifyTestGame(stage);
                verifyInstanceEditingLock(view, app, repository);
                verifyAccountExclusion(stage);
                verifyAvatarLifecycle(stage);
                verifyBrowserLoginDialog(stage);
                verifyTaskProgressLayout(stage);
                verifyDirectoryLoading(stage);
                verifyModpackDialogs(stage);
                verifyLoaderReinstallation(stage);
                verifyImportedInstanceSelection(stage);
                verifyStartupRecovery(stage);
            } catch (Throwable ex) {
                failure = ex;
            } finally {
                app.stop();
                stage.close();
                Platform.exit();
            }
            if (failure == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(failure);
            }
        });
        try {
            result.get(30, TimeUnit.SECONDS);
        } finally {
            System.clearProperty("fuzz.dataDir");
        }
    }

    private void verifyTestGame(Stage stage) throws Exception {
        var scene = stage.getScene();
        var original = scene.getRoot();
        Path data = Files.createDirectories(temp.resolve("test-game-ui"));
        var settings = new SettingsStore(data);
        settings.save(SettingsDefaults.create(data.resolve("game").toString(), IsolationMode.ALL, DownloadSource.AUTO, "", LauncherVisibility.EXIT));
        var instances = new JsonInstanceRepository(data);
        instances.save(new GameInstance("test", "测试实例", "1.21.1", LoaderType.VANILLA, null, IsolationOverride.INHERIT, 4096));
        AtomicReference<RunningGame> running = new AtomicReference<>();
        AtomicReference<LauncherVisibility> policy = new AtomicReference<>();
        AtomicInteger launches = new AtomicInteger();
        GameLauncher launcher = new GameLauncher() {
            public RunningGame execute(LauncherSettings config, GameInstance instance, Account account, TaskContext task)
                throws Exception {
                int number = launches.incrementAndGet();
                Path log = data.resolve(number + ".log");
                String executable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
                String classes = Path.of(TestGameOutput.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                Process relay = DetachedGame.start(List.of(executable, "-cp", classes, TestGameOutput.class.getName(),
                    Boolean.toString(number == 1)), data, log, "fixture-secret", java.nio.charset.Charset.forName("GBK"));
                var game = new RunningGame(relay, log);
                running.set(game);
                return game;
            }

            public void stop(RunningGame game) throws IOException {
                ProcessCleanup.terminate(game.process());
            }

            public void followLog(RunningGame game, BooleanSupplier cancelled, Consumer<LogSnapshot> output)
                throws IOException, InterruptedException {
                GameLogTail.follow(game, cancelled, output);
            }

            public JvmArgumentPreview previewJvm(Path root, GameInstance instance) {
                return JvmArgumentPreview.unavailable();
            }
        };
        try (var vm = ViewModels.create(settings, instances, null, new MicrosoftAuthentication(new HttpTransport(), data), null, launcher)) {
            vm.gamesModel().onGameLifecycle(policy::set, ignored -> {
            });
            awaitAccountOperation(vm, () -> vm.accountsModel().loginOffline("TestPlayer"));
            var view = new LauncherView(stage, vm);
            scene.setRoot(view);
            CheckBox mode = (CheckBox) view.lookup("#test-game-mode");
            Button start = (Button) view.lookup("#launch-game");
            assertFalse(mode.isSelected());
            mode.fire();
            start.fire();
            waitForFx(() -> vm.gameRunningProperty().get());
            assertEquals(LauncherVisibility.KEEP, policy.get());
            assertFalse(mode.isSelected());
            DialogPane pane = Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene)
                .map(windowScene -> windowScene.lookup("#test-game-log-dialog")).filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast).findFirst().orElseThrow();
            pane.applyCss();
            pane.layout();
            Button stop = (Button) pane.lookup("#stop-test-game");
            Node close = pane.lookupButton(ButtonType.CLOSE);
            double gap = close.localToScene(close.getBoundsInLocal()).getMinX()
                - stop.localToScene(stop.getBoundsInLocal()).getMaxX();
            assertTrue(gap >= 0 && gap <= 8, "Expected adjacent action buttons, gap was " + gap);
            Window logWindow = pane.getScene().getWindow();
            double width = logWindow.getWidth(), height = logWindow.getHeight();
            assertTrue(width >= 890 && height >= 630);
            assertTrue(pane.getWidth() - close.localToScene(close.getBoundsInLocal()).getMaxX() < 35);
            verifyWindowResize(pane);
            @SuppressWarnings("unchecked")
            ListView<GameLogEntry> output = (ListView<GameLogEntry>) pane.lookup("#test-game-log-output");
            waitForFx(() -> output.getItems().stream().anyMatch(entry -> entry.text().contains("last [REDACTED]")));
            assertTrue(output.getItems().stream().noneMatch(entry -> entry.text().contains("fixture-secret")));
            assertTrue(output.getItems().stream().anyMatch(entry -> entry.text().contains("旧版控制台中文")));
            assertTrue(output.getItems().stream().anyMatch(entry -> entry.text().contains("游戏中文日志")));
            assertTrue(output.getItems().stream().noneMatch(entry -> entry.text().contains("<log4j:")));
            String savedLog = Files.readString(running.get().log());
            assertTrue(savedLog.contains("[Render thread/WARN]: last [REDACTED]"));
            assertTrue(savedLog.contains("旧版控制台中文"));
            assertTrue(savedLog.contains("游戏中文日志"));
            assertFalse(savedLog.contains("log4j:"));
            assertFalse(savedLog.contains("fixture-secret"));
            assertTrue(Files.size(running.get().log()) > 128 * 1024);
            assertTrue(output.getItems().size() <= 1000);
            ToggleButton warn = (ToggleButton) pane.lookup("#game-log-warn-filter");
            assertEquals("WARN 1", warn.getText());
            assertEquals(GameLogEntry.Level.WARN, output.getItems().getLast().level());
            output.scrollTo(output.getItems().size() - 1);
            waitForFx(() -> output.lookupAll(".list-cell.game-log-warn").stream()
                .filter(ListCell.class::isInstance).map(ListCell.class::cast)
                .anyMatch(cell -> cell.getBackground() != null && cell.getBackground().getFills().stream()
                    .anyMatch(fill -> Color.web("#fff2c7").equals(fill.getFill()))));
            warn.fire();
            assertTrue(output.getItems().stream().noneMatch(entry -> entry.level() == GameLogEntry.Level.WARN));
            assertEquals("WARN 1", warn.getText());
            warn.fire();
            assertEquals(GameLogEntry.Level.WARN, output.getItems().getLast().level());
            ((Button) pane.lookupButton(ButtonType.CLOSE)).fire();
            assertTrue(running.get().process().isAlive(), "Closing the log window must leave the game running");
            GameLogDialog reopened = new GameLogDialog(stage, vm.gamesModel(), running.get());
            reopened.show();
            DialogPane reopenedPane = reopened.getDialogPane();
            ((Button) reopenedPane.lookup("#stop-test-game")).fire();
            waitForFx(() -> ((Label) reopenedPane.lookup("#test-game-log-status")).getText().contains("退出码"));
            assertFalse(running.get().process().isAlive());
            reopened.close();
            start.fire();
            waitForFx(() -> launches.get() == 2 && policy.get() == LauncherVisibility.EXIT && !vm.gameRunningProperty().get());
            assertTrue(Window.getWindows().stream().filter(Window::isShowing)
                .noneMatch(window -> window.getScene().lookup("#test-game-log-dialog") != null));
        } finally {
            scene.setRoot(original);
            if (running.get() != null && running.get().process().isAlive()) {
                ProcessCleanup.terminate(running.get().process());
            }
        }
    }

    private static void verifyWindowResize(Region pane) {
        Stage window = (Stage) pane.getScene().getWindow();
        Cursor[][] cursors = {
            { Cursor.NW_RESIZE, Cursor.N_RESIZE, Cursor.NE_RESIZE },
            { Cursor.W_RESIZE, Cursor.DEFAULT, Cursor.E_RESIZE },
            { Cursor.SW_RESIZE, Cursor.S_RESIZE, Cursor.SE_RESIZE }
        };
        for (int row = -1; row <= 1; row++) {
            for (int column = -1; column <= 1; column++) {
                double x = column < 0 ? 2 : column > 0 ? pane.getWidth() - 2 : pane.getWidth() / 2;
                double y = row < 0 ? 2 : row > 0 ? pane.getHeight() - 2 : pane.getHeight() / 2;
                var screen = pane.localToScreen(x, y);
                resizeMouse(pane, MouseEvent.MOUSE_MOVED, x, y, screen.getX(), screen.getY(), false);
                assertEquals(cursors[row + 1][column + 1], pane.getCursor());
                if (row == 0 && column == 0) {
                    continue;
                }
                double left = window.getX(), top = window.getY(), width = window.getWidth(), height = window.getHeight();
                int dx = column * 20, dy = row * 20;
                resizeMouse(pane, MouseEvent.MOUSE_PRESSED, x, y, screen.getX(), screen.getY(), true);
                resizeMouse(pane, MouseEvent.MOUSE_DRAGGED, x + dx, y + dy, screen.getX() + dx, screen.getY() + dy, true);
                resizeMouse(pane, MouseEvent.MOUSE_RELEASED, x + dx, y + dy, screen.getX() + dx, screen.getY() + dy, false);
                assertEquals(width + Math.abs(dx), window.getWidth(), 1);
                assertEquals(height + Math.abs(dy), window.getHeight(), 1);
                assertEquals(left + Math.min(0, dx), window.getX(), 1);
                assertEquals(top + Math.min(0, dy), window.getY(), 1);
                window.setX(left);
                window.setY(top);
                window.setWidth(width);
                window.setHeight(height);
            }
        }
    }

    private static void resizeMouse(Region pane, EventType<MouseEvent> type, double x, double y, double screenX, double screenY, boolean pressed) {
        Event.fireEvent(pane, new MouseEvent(type, x, y, screenX, screenY,
            type == MouseEvent.MOUSE_MOVED ? MouseButton.NONE : MouseButton.PRIMARY,
            1, false, false, false, false, pressed, false, false, false, false, true, new PickResult(pane, x, y)));
    }

    public static class TestGameOutput {
        public static void main(String[] args) throws InterruptedException {
            for (int i = 0; i < 1500; i++) {
                System.out.println("fixture line " + i + " " + "x".repeat(100));
            }
            System.out.println("[15:45:19] [Render thread/ERROR]: ordinary error");
            System.out.writeBytes("旧版控制台中文\n".getBytes(java.nio.charset.Charset.forName("GBK")));
            System.err.writeBytes("""
                <log4j:Event logger="fixture" timestamp="1793864770320" level="WARN" thread="Render thread">
                  <log4j:Message><![CDATA[last fixture-secret 游戏中文日志]]></log4j:Message>
                </log4j:Event>
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (Boolean.parseBoolean(args[0])) {
                Thread.sleep(20_000);
            }
        }
    }

    private static void verifyClearLogs(LauncherView view) throws IOException {
        view.showPage(3);
        LauncherViewModel vm = view.viewModel();
        Path log = Files.writeString(vm.dataDirectory().resolve("logs/clear-fixture.log"), "old output");
        Button clear = (Button) view.lookup("#clear-launcher-logs");
        for (ButtonType choice : List.of(ButtonType.CANCEL, ButtonType.OK)) {
            Platform.runLater(() -> {
                Stage dialog = Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                    .filter(window -> window.isShowing() && window.getTitle().equals("清理日志")).findFirst().orElseThrow();
                DialogPane pane = (DialogPane) dialog.getScene().getRoot();
                ((Button) pane.lookupButton(choice)).fire();
            });
            clear.fire();
            if (choice == ButtonType.CANCEL) {
                assertTrue(Files.exists(log));
                assertFalse(vm.busyProperty().get());
            }
        }
        waitForFx(() -> !vm.busyProperty().get());
        assertFalse(Files.exists(log));
        assertTrue(vm.statusProperty().get().startsWith("已清理"));
    }

    private static void verifyInstanceSelectionSync(LauncherView view) {
        LauncherViewModel vm = view.viewModel();
        @SuppressWarnings("unchecked")
        ListView<GameInstance> list = (ListView<GameInstance>) view.lookup(".list-view");
        for (GameInstance instance : vm.instances()) {
            vm.instancesModel().selectInstance(instance);
            assertEquals(instance, list.getSelectionModel().getSelectedItem(), "Programmatic selection must update the list highlight as well as the details");
            assertTrue(view.lookupAll("#instance-settings-form .text-field").stream().filter(TextField.class::isInstance)
                .map(TextField.class::cast).anyMatch(field -> field.getText().equals(instance.name())));
        }
        list.getSelectionModel().selectFirst();
        assertEquals(list.getSelectionModel().getSelectedItem(), vm.selectedProperty().get());
        view.showPage(5);
        GameInstance next = vm.instances().getLast();
        vm.instancesModel().selectInstance(next);
        view.showPage(0);
        @SuppressWarnings("unchecked")
        ListView<GameInstance> current = (ListView<GameInstance>) view.lookup(".list-view");
        assertEquals(next, current.getSelectionModel().getSelectedItem());
        var scene = view.getScene();
        scene.setRoot(new VBox());
        GameInstance first = vm.instances().getFirst();
        vm.instancesModel().selectInstance(first);
        current.getSelectionModel().selectFirst();
        current.getSelectionModel().selectLast();
        assertEquals(first, vm.selectedProperty().get(), "A detached page must not change the current instance");
        scene.setRoot(view);
        assertEquals(first, current.getSelectionModel().getSelectedItem());
        vm.instancesModel().selectInstance(next);
        assertEquals(next, current.getSelectionModel().getSelectedItem(), "A reattached page must resume selection synchronization");
    }

    private void verifyImportedInstanceSelection(Stage stage) throws Exception {
        var original = stage.getScene().getRoot();
        Path data = Files.createDirectories(temp.resolve("import-selection")), root = data.resolve("game");
        var settings = new SettingsStore(data);
        settings.save(SettingsDefaults.create(root.toString(), IsolationMode.ALL));
        var repository = new JsonInstanceRepository(data);
        GameInstance first = new GameInstance("first", "已有原版", "selection-base", LoaderType.VANILLA, null, IsolationOverride.INHERIT, 4096);
        GameInstance second = new GameInstance("second", "已有 Fabric", "selection-base", LoaderType.FABRIC, "0.16.0", IsolationOverride.INHERIT, 4096);
        repository.save(first);
        repository.save(second);
        Path content = Files.createDirectories(data.resolve("source/config"));
        Files.writeString(content.resolve("settings.txt"), "fixture");
        Path archive = data.resolve("pack.zip");
        ModpackArchive.export(content.getParent(), second, ModpackFormat.MCBBS, "新导入实例", "1", "", "", "", Set.of("config"), archive, false, ModFixtures.TASK);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String body = exchange.getRequestURI().getPath().equals("/version.json")
                ? "{\"id\":\"selection-base\",\"mainClass\":\"Fixture\",\"libraries\":[],\"downloads\":{\"client\":{\"url\":\""
                    + base + "/client.jar\"}}}"
                : "fixture client";
            try (exchange) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            HttpTransport http = new HttpTransport();
            BaseVersions bases = new BaseVersions(data.resolve("bases"));
            GameVersion game = new GameVersion("selection-base", "release", base + "/version.json", "");
            var recovery = new OperationJournal.Store(data.resolve("operations"), data);
            LoaderInstaller loader = (gameRoot, workspace, version, selected, task) -> new JsonObject();
            var installer = new LocalGameInstallation(repository, new GameFiles(http, bases), new VersionResolver(bases),
                Map.of(LoaderType.FABRIC, loader), recovery);
            try (var vm = ViewModels.create(settings, repository, null, new MicrosoftAuthentication(http, data), installer, null, null,
                new LocalModpacks(http, () -> List.of(game), installer, repository), recovery)) {
                LauncherView view = new LauncherView(stage, vm);
                stage.getScene().setRoot(view);
                view.applyCss();
                view.layout();
                vm.instancesModel().selectInstance(second);
                awaitAccountOperation(vm, () -> vm.modpacksModel().importModpack(archive, "新导入实例", true));
                GameInstance imported = vm.selectedProperty().get();
                assertEquals("新导入实例", imported.name());
                assertInstanceSelection(view, imported);
                awaitAccountOperation(vm, vm.instancesModel()::refreshInstances);
                assertEquals(imported.id(), vm.selectedProperty().get().id());
                assertInstanceSelection(view, vm.selectedProperty().get());
                for (GameInstance item : List.copyOf(vm.instances())) {
                    awaitAccountOperation(vm, () -> vm.instancesModel().unregister(item));
                    assertInstanceSelection(view, vm.selectedProperty().get());
                }
                assertNull(vm.selectedProperty().get());
            }
        } finally {
            stage.getScene().setRoot(original);
            server.stop(0);
        }
    }

    private static void assertInstanceSelection(LauncherView view, GameInstance expected) {
        @SuppressWarnings("unchecked")
        ListView<GameInstance> list = (ListView<GameInstance>) view.lookup(".list-view");
        assertEquals(expected, list.getSelectionModel().getSelectedItem());
        if (expected == null) {
            assertNull(view.lookup("#instance-settings-form"));
        } else {
            assertTrue(view.lookupAll("#instance-settings-form .text-field").stream().filter(TextField.class::isInstance)
                .map(TextField.class::cast).anyMatch(field -> field.getText().equals(expected.name())));
        }
    }

    private void verifyStartupRecovery(Stage owner) throws Exception {
        Path data = Files.createDirectories(temp.resolve("recovery-ui"));
        SettingsStore store = new SettingsStore(data);
        LauncherSettings previous = SettingsDefaults.create(data.resolve("old-game").toString(), IsolationMode.ALL);
        store.save(previous);
        store.save(SettingsDefaults.create(data.resolve("new-game").toString(), IsolationMode.ALL));
        Files.writeString(data.resolve("settings.json"), "{broken");
        var error = new AtomicReference<Throwable>();
        chooseRecoveryButton("配置需要修复", ButtonBar.ButtonData.YES, error);
        assertTrue(StartupRecovery.configurations(owner, data, store::load));
        assertNull(error.get());
        assertEquals(previous, store.load());
        Path value = data.resolve("value"), records = data.resolve("operations");
        Files.writeString(value, "before");
        var operation = new OperationJournal.Store(records, data).begin("UI recovery fixture", data);
        operation.file(value, "after".getBytes());
        Files.writeString(value, "after");
        chooseRecoveryButton("发现未完成操作", ButtonBar.ButtonData.CANCEL_CLOSE, error);
        assertFalse(StartupRecovery.operations(owner, records));
        assertEquals("after", Files.readString(value));
        assertFalse(OperationJournal.pending(records).isEmpty());
        chooseRecoveryButton("发现未完成操作", ButtonBar.ButtonData.OK_DONE, error);
        assertTrue(StartupRecovery.operations(owner, records));
        assertNull(error.get());
        assertEquals("before", Files.readString(value));
        assertTrue(OperationJournal.pending(records).isEmpty());
    }

    private static void chooseRecoveryButton(String title, ButtonBar.ButtonData choice, AtomicReference<Throwable> error) {
        Platform.runLater(() -> {
            Window window = null;
            try {
                window = List.copyOf(Window.getWindows()).stream()
                    .filter(value -> value instanceof Stage stage && title.equals(stage.getTitle())).findFirst().orElseThrow();
                DialogPane pane = (DialogPane) window.getScene().getRoot();
                assertTrue(pane.getStyleClass().contains("retro-dialog"));
                if (title.equals("发现未完成操作")) {
                    ImageIO.write(SwingFXUtils.fromFXImage(window.getScene().snapshot(null), null), "png",
                        Path.of("build/ui-check/startup-recovery.png").toFile());
                }
                ButtonType type = pane.getButtonTypes().stream().filter(button -> button.getButtonData() == choice).findFirst()
                    .orElseThrow();
                ((Button) pane.lookupButton(type)).fire();
            } catch (Throwable ex) {
                error.set(ex);
                if (window != null) {
                    window.hide();
                }
            }
        });
    }

    private void verifyAvatarLifecycle(Stage stage) throws Exception {
        var original = stage.getScene().getRoot();
        Account microsoft = new Account("skin-fixture", "0123456789abcdef0123456789abcdef", "SkinFixture");
        var identity = new AtomicReference<>(microsoft);
        AccountAuthenticator auth = new AccountAuthenticator() {
            public Optional<Account> savedAccount() {
                return Optional.ofNullable(identity.get());
            }

            public GameSession login(TaskContext task) {
                identity.set(microsoft);
                return new GameSession(microsoft, "fixture", Instant.MAX);
            }

            public GameSession loginOffline(String name, TaskContext task) {
                identity.set(Account.offline(name));
                return GameSession.offline(identity.get());
            }

            public GameSession refresh(Account account, TaskContext task) {
                throw new AssertionError("No refresh expected");
            }

            public void logout(TaskContext task) {
                identity.set(null);
            }
        };
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, BLUE.getRGB());
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        var calls = new AtomicInteger();
        CountDownLatch loading = new CountDownLatch(1), release = new CountDownLatch(1), returned = new CountDownLatch(1);
        Path data = temp.resolve("avatar-state");
        new SettingsStore(data).save(SettingsDefaults.create(data.resolve("game").toString(), IsolationMode.ALL));
        try (var vm = ViewModels.create(new SettingsStore(data), new JsonInstanceRepository(data), null, auth, null, null,
            (account, task) -> {
                if (calls.incrementAndGet() == 2) {
                    loading.countDown();
                    // Simulate a transport completing a stale response despite
                    // cancellation.
                    boolean waiting = true;
                    while (waiting) {
                        try {
                            release.await();
                            waiting = false;
                        } catch (InterruptedException ex) {
                            Thread.interrupted();
                        }
                    }
                    returned.countDown();
                }
                return bytes.toByteArray();
            })) {
            AccountsPage page = new AccountsPage(stage, vm, ignored -> {
            });
            stage.getScene().setRoot(page);
            page.applyCss();
            page.layout();
            var avatar = (ImageView) page.lookup("#microsoft-avatar");
            var fallback = avatar.getImage();
            Object loop = new Object();
            Timeline poll = new Timeline(
                new KeyFrame(Duration.millis(20), event -> {
                    if (vm.accountsModel().accountAvatarProperty().get() != null) {
                        Platform.exitNestedEventLoop(loop, true);
                    }
                }));
            poll.setCycleCount(200);
            poll.setOnFinished(event -> Platform.exitNestedEventLoop(loop, false));
            poll.play();
            try {
                assertEquals(true, Platform.enterNestedEventLoop(loop));
            } finally {
                poll.stop();
            }
            assertSame(vm.accountsModel().accountAvatarProperty().get(), avatar.getImage());
            assertEquals(Color.BLUE, avatar.getImage().getPixelReader().getColor(0, 0));
            awaitAccountOperation(vm, vm.accountsModel()::logout);
            assertNull(vm.accountsModel().accountAvatarProperty().get());
            assertSame(fallback, avatar.getImage());
            awaitAccountOperation(vm, vm.accountsModel()::login);
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            awaitAccountOperation(vm, vm.accountsModel()::logout);
            awaitAccountOperation(vm, () -> vm.accountsModel().loginOffline("OfflineFixture"));
            release.countDown();
            assertTrue(returned.await(5, TimeUnit.SECONDS));
            Object drain = new Object();
            var pause = new PauseTransition(Duration.millis(200));
            pause.setOnFinished(event -> Platform.exitNestedEventLoop(drain, true));
            pause.play();
            Platform.enterNestedEventLoop(drain);
            assertNull(vm.accountsModel().accountAvatarProperty().get(), "Late Microsoft response must not replace offline avatar");
            assertSame(fallback, avatar.getImage());
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
            stage.getScene().setRoot(original);
        }
    }

    private void verifyBrowserLoginDialog(Stage stage) throws Exception {
        var original = stage.getScene().getRoot();
        var calls = new AtomicInteger();
        var release = new CountDownLatch(1);
        var authorized = new CountDownLatch(1);
        var verifying = new CountDownLatch(1);
        Account microsoft = new Account("browser-fixture", "0123456789abcdef0123456789abcdef", "BrowserFixture");
        AccountAuthenticator auth = new AccountAuthenticator() {
            public Optional<Account> savedAccount() {
                return Optional.empty();
            }

            public GameSession login(TaskContext task) throws Exception {
                int attempt = calls.incrementAndGet();
                task.report("请在浏览器中完成微软登录，完成后会自动返回启动器", -1);
                if (attempt == 1) {
                    // A late result from an already cancelled browser must not replace the
                    // active account.
                    while (release.getCount() != 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            Thread.interrupted();
                        }
                    }
                } else if (attempt == 2) {
                    authorized.await();
                    task.report("Minecraft 正版权限校验", -1);
                    verifying.await();
                } else {
                    while (true) {
                        task.checkCancelled();
                        Thread.sleep(20);
                    }
                }
                return new GameSession(microsoft, "fixture", Instant.MAX);
            }

            public GameSession loginOffline(String name, TaskContext task) {
                return GameSession.offline(Account.offline(name));
            }

            public GameSession refresh(Account account, TaskContext task) {
                throw new AssertionError();
            }

            public void logout(TaskContext task) {
            }
        };
        Path data = temp.resolve("browser-login-state");
        new SettingsStore(data).save(SettingsDefaults.create(data.resolve("game").toString(), IsolationMode.ALL));
        try (var vm = ViewModels.create(new SettingsStore(data), new JsonInstanceRepository(data), null, auth, null, null)) {
            stage.getScene().setRoot(new LauncherView(stage, vm));
            vm.accountsModel().login();
            assertTrue(vm.loggingInProperty().get());
            DialogPane pane = browserPane();
            assertNull(pane.lookup("#device-login-code"));
            assertNull(pane.lookup("#refresh-device-code"));
            waitForFx(() -> ((Label) pane.lookup("#browser-login-status")).getText().contains("浏览器"));
            pane.applyCss();
            pane.layout();
            ImageIO.write(SwingFXUtils.fromFXImage(pane.getScene().snapshot(null), null), "png", Path.of("build/ui-check/browser-login.png").toFile());
            Window window = pane.getScene().getWindow();
            ((Button) pane.lookupButton(pane.getButtonTypes().getFirst())).fire();
            waitForFx(() -> !vm.busyProperty().get());
            assertFalse(window.isShowing());
            release.countDown();
            awaitAccountOperation(vm, () -> vm.accountsModel().loginOffline("OfflineFixture"));
            assertTrue(vm.accountProperty().get().isOffline());
            awaitAccountOperation(vm, vm.accountsModel()::logout);
            vm.accountsModel().login();
            waitForFx(() -> calls.get() == 2);
            authorized.countDown();
            DialogPane validating = browserPane();
            waitForFx(() -> ((Label) validating.lookup("#browser-login-status")).getText().contains("正版权限"));
            assertTrue(validating.getScene().getWindow().isShowing());
            verifying.countDown();
            waitForFx(() -> !vm.busyProperty().get());
            assertEquals(microsoft, vm.accountProperty().get());
            assertFalse(vm.loggingInProperty().get());
            awaitAccountOperation(vm, vm.accountsModel()::logout);
            vm.accountsModel().login();
            waitForFx(() -> calls.get() == 3);
            ((Button) browserPane().lookup(".window-control")).fire();
            waitForFx(() -> !vm.loggingInProperty().get());
            assertNull(vm.accountProperty().get());
        } finally {
            release.countDown();
            authorized.countDown();
            verifying.countDown();
            stage.getScene().setRoot(original);
        }
    }

    private static DialogPane browserPane() {
        return (DialogPane) Window.getWindows().stream()
            .filter(window -> window.getScene() != null && window.getScene().lookup("#browser-login-dialog") != null).findFirst()
            .orElseThrow().getScene().getRoot();
    }

    private void verifyTaskProgressLayout(Stage stage) throws Exception {
        Path data = temp.resolve("progress-layout-state");
        new SettingsStore(data).save(SettingsDefaults.create(data.resolve("game").toString(), IsolationMode.ALL));
        try (var vm = ViewModels.create(new SettingsStore(data), new JsonInstanceRepository(data), null,
            new MicrosoftAuthentication(new HttpTransport(), data), null, null)) {
            InstallProgressDialog dialog = new InstallProgressDialog(stage, vm);
            dialog.show();
            try {
                DialogPane pane = dialog.getDialogPane();
                ProgressBar bar = (ProgressBar) pane.lookup("#task-progress-bar");
                Label message = (Label) pane.lookup("#task-progress-message");
                bar.progressProperty().unbind();
                message.textProperty().unbind();
                pane.applyCss();
                pane.layout();
                var original = bar.localToScene(bar.getBoundsInLocal());
                double width = pane.getScene().getWindow().getWidth();
                double height = pane.getScene().getWindow().getHeight();
                for (double progress : new double[] { -1, 0, .25, 1, -1, .75 }) {
                    for (String text : new String[] { "准备中", "正在下载：很长的资源路径/".repeat(30), "正在校验\n第一项\n第二项\n第三项\n第四项" }) {
                        bar.setProgress(progress);
                        message.setText(text);
                        pane.applyCss();
                        pane.layout();
                        ((Stage) pane.getScene().getWindow()).sizeToScene();
                        var bounds = bar.localToScene(bar.getBoundsInLocal());
                        assertEquals(496, bounds.getWidth(), .01);
                        assertEquals(24, bounds.getHeight(), .01);
                        assertEquals(original.getMinY(), bounds.getMinY(), .01);
                        assertEquals(width, pane.getScene().getWindow().getWidth(), .01);
                        assertEquals(height, pane.getScene().getWindow().getHeight(), .01);
                    }
                }
                ImageIO.write(SwingFXUtils.fromFXImage(pane.getScene().snapshot(null), null), "png", Path.of("build/ui-check/task-progress.png").toFile());
            } finally {
                dialog.close();
            }
        }
    }

    private void verifyModpackDialogs(Stage stage) throws Exception {
        Node original = stage.getScene().getRoot();
        Path data = temp.resolve("modpack-ui"), root = data.resolve("game");
        GameInstance instance = new GameInstance("pack-fixture", "整合包实例", "1.20.4", LoaderType.FABRIC, "0.16.0", IsolationOverride.ISOLATED, 4096);
        Path source = InstanceDirectories.directory(root, instance);
        Files.createDirectories(source.resolve("mods"));
        Files.createDirectories(source.resolve("saves"));
        Files.writeString(source.resolve("mods/test.jar"), "fixture");
        Files.writeString(source.resolve("mods/excluded.jar"), "excluded fixture");
        Files.createDirectories(source.resolve("config/nested"));
        Files.writeString(source.resolve("config/nested/settings.json"), "configuration");
        Files.writeString(source.resolve("options.txt"), "settings");
        Files.writeString(source.resolve("account.json"), "private");
        new SettingsStore(data).save(SettingsDefaults.create(root.toString(), IsolationMode.ALL));
        var repository = new JsonInstanceRepository(data);
        repository.save(instance);
        AccountAuthenticator auth = new AccountAuthenticator() {
            public Optional<Account> savedAccount() {
                return Optional.empty();
            }

            public GameSession login(TaskContext task) {
                throw new AssertionError();
            }

            public GameSession loginOffline(String name, TaskContext task) {
                return GameSession.offline(Account.offline(name));
            }

            public GameSession refresh(Account account, TaskContext task) {
                throw new AssertionError();
            }

            public void logout(TaskContext task) {
            }
        };
        try (var vm = ViewModels.create(new SettingsStore(data), repository, null, auth, null, null)) {
            LauncherView view = new LauncherView(stage, vm);
            stage.getScene().setRoot(view);
            view.applyCss();
            view.layout();
            assertNotNull(view.lookup("#import-modpack"));
            assertNotNull(view.lookup("#export-modpack"));
            assertFalse(view.lookup("#update-modpack").isVisible());
            Dialog<ButtonType> export = ModpackDialogs.exportPack(stage, vm, instance);
            DialogPane pane = export.getDialogPane();
            pane.applyCss();
            pane.layout();
            assertNotNull(pane.lookup("#modpack-type-mcbbs"));
            assertNotNull(pane.lookup("#modpack-type-multimc"));
            assertNotNull(pane.lookup("#modpack-type-server"));
            assertNotNull(pane.lookup("#modpack-type-modrinth"));
            ImageIO.write(SwingFXUtils.fromFXImage(pane.getScene().snapshot(null), null), "png", Path.of("build/ui-check/modpack-types.png").toFile());
            ((Button) pane.lookup("#modpack-type-server")).fire();
            pane.applyCss();
            pane.layout();
            @SuppressWarnings("unchecked")
            ComboBox<ModpackFormat> format = (ComboBox<ModpackFormat>) pane.lookup("#modpack-format");
            assertEquals(4, format.getItems().size());
            assertEquals(ModpackFormat.SERVER, format.getValue());
            assertFalse(pane.lookup("#modpack-file-api").isDisabled());
            ((TextField) pane.lookup("#modpack-file-api")).setText("https://example.com/pack");
            ((TextField) pane.lookup("#modpack-name")).setText("选择测试包");
            ((Button) pane.lookup("#modpack-export-next")).fire();
            waitForFx(() -> pane.lookup("#modpack-export-files") instanceof TreeView<?> tree && tree.getRoot().getChildren().size() >= 3);
            ModpackFileTree selector = (ModpackFileTree) pane.lookup("#modpack-file-selector");
            @SuppressWarnings("unchecked")
            TreeView<ModpackContent> tree = (TreeView<ModpackContent>) pane.lookup("#modpack-export-files");
            assertNotNull(packItem(tree.getRoot(), "account.json"));
            assertFalse(packItem(tree.getRoot(), "saves").isSelected());
            assertFalse(packItem(tree.getRoot(), "options.txt").isSelected());
            var mods = packItem(tree.getRoot(), "mods");
            var excludedMod = packItem(tree.getRoot(), "mods/excluded.jar");
            excludedMod.setSelected(false);
            assertTrue(mods.isIndeterminate());
            assertTrue(selector.selectedPaths().contains("mods/test.jar"));
            assertFalse(selector.selectedPaths().contains("mods/excluded.jar"));
            pane.applyCss();
            pane.layout();
            TreeCell<?> modsCell = tree.lookupAll(".tree-cell").stream().filter(TreeCell.class::isInstance).map(TreeCell.class::cast)
                .filter(cell -> cell.getTreeItem() == mods).findFirst().orElseThrow();
            var checkBoxSquare = modsCell.lookup(".check-box > .box");
            assertEquals(17, checkBoxSquare.getLayoutBounds().getWidth(), 0.01);
            assertEquals(17, checkBoxSquare.getLayoutBounds().getHeight(), 0.01);
            ((CheckBox) modsCell.lookup(".check-box")).fire();
            assertTrue(excludedMod.isSelected());
            assertFalse(mods.isIndeterminate());
            ((Button) pane.lookup("#modpack-select-none")).fire();
            assertTrue(selector.selectedPaths().isEmpty());
            ((Button) pane.lookup("#modpack-select-all")).fire();
            assertTrue(packItem(tree.getRoot(), "saves").isSelected());
            ((Button) pane.lookup("#modpack-select-recommended")).fire();
            assertFalse(packItem(tree.getRoot(), "saves").isSelected());
            excludedMod.setSelected(false);
            var selection = selector.selectedPaths();
            TextField search = (TextField) pane.lookup("#modpack-file-search");
            search.setText("nested/settings");
            search.fireEvent(new ActionEvent());
            assertEquals("config/nested/settings.json", tree.getSelectionModel().getSelectedItem().getValue().path());
            assertEquals(selection, selector.selectedPaths());
            ((Button) pane.lookup("#modpack-collapse-all")).fire();
            assertEquals(selection, selector.selectedPaths());
            ((Button) pane.lookup("#modpack-export-back")).fire();
            assertEquals("选择测试包", ((TextField) pane.lookup("#modpack-name")).getText());
            assertEquals(ModpackFormat.SERVER, ((ComboBox<?>) pane.lookup("#modpack-format")).getValue());
            ((Button) pane.lookup("#modpack-export-next")).fire();
            assertEquals(selection, selector.selectedPaths());
            ((Button) pane.lookup("#modpack-expand-all")).fire();
            pane.applyCss();
            pane.layout();
            ImageIO.write(SwingFXUtils.fromFXImage(pane.getScene().snapshot(null), null), "png", Path.of("build/ui-check/modpack-export.png").toFile());
            ((TextField) pane.lookup("#modpack-output-directory")).setText(data.resolve("exports").toString());
            ((TextField) pane.lookup("#modpack-filename")).setText("selected");
            ((Button) pane.lookup("#modpack-export-next")).fire();
            waitForFx(() -> !vm.busyProperty().get());
            try (var zip = new ZipFile(data.resolve("exports/selected.zip").toFile())) {
                assertNotNull(zip.getEntry("overrides/mods/test.jar"));
                assertNotNull(zip.getEntry("overrides/config/nested/settings.json"));
                assertNotNull(zip.getEntry("overrides/account.json"));
                assertNull(zip.getEntry("overrides/mods/excluded.jar"));
                assertNull(zip.getEntry("overrides/options.txt"));
            }
            var optionalManifest = JsonParser.parseString(
                "{\"files\":[{\"path\":\"mods/optional-a.jar\",\"fileSize\":100,\"env\":{\"client\":\"optional\"}},{\"path\":\"mods/optional-b.jar\",\"fileSize\":200,\"env\":{\"client\":\"optional\"}}]}")
                .getAsJsonObject();
            var info = new ModpackArchive.Info(ModpackFormat.MODRINTH, "测试整合包", "1.0.0", "作者", "导入说明", "1.20.4", LoaderType.FABRIC,
                "0.16.0", optionalManifest, "");
            Dialog<ButtonType> importing = ModpackDialogs.importOptions(stage, vm, temp.resolve("fixture.mrpack"), LocalModpackOperations.describe(info));
            DialogPane importPane = importing.getDialogPane();
            importPane.applyCss();
            importPane.layout();
            assertEquals("测试整合包", ((TextField) importPane.lookup("#modpack-instance-name")).getText());
            assertTrue(importPane.lookup("#modpack-optional-files").isVisible());
            ModpackFileTree optional = (ModpackFileTree) importPane.lookup("#modpack-optional-files");
            @SuppressWarnings("unchecked")
            TreeView<ModpackContent> optionalTree = (TreeView<ModpackContent>) optional.lookup("#modpack-export-files");
            packItem(optionalTree.getRoot(), "mods").setExpanded(true);
            packItem(optionalTree.getRoot(), "mods/optional-a.jar").setSelected(false);
            assertEquals(Set.of("mods/optional-b.jar"), optional.selectedPaths());
            waitForFx(() -> ((Label) optional.lookup("#modpack-selection-summary")).getText().startsWith("已选择 1 个文件"));
            ImageIO.write(SwingFXUtils.fromFXImage(importPane.getScene().snapshot(null), null), "png", Path.of("build/ui-check/modpack-import.png").toFile());
            ((Button) importPane.lookupButton(ButtonType.CANCEL)).fire();
            var malformed = new ModpackArchive.Info(ModpackFormat.MODRINTH, "无效清单", "1", "", "", "1.20.4", LoaderType.VANILLA, null,
                JsonParser.parseString("{\"files\":[42]}").getAsJsonObject(), "");
            Dialog<ButtonType> invalid = ModpackDialogs.importOptions(stage, vm, temp.resolve("invalid.mrpack"), LocalModpackOperations.describe(malformed));
            ButtonType invalidStart = invalid.getDialogPane().getButtonTypes().stream()
                .filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
            assertTrue(invalid.getDialogPane().lookupButton(invalidStart).isDisabled());
            ((Button) invalid.getDialogPane().lookupButton(ButtonType.CANCEL)).fire();
            Path archive = data.resolve("fixture.mrpack");
            Files.writeString(archive, "picker fixture");
            Platform.runLater(() -> {
                DialogPane picker = (DialogPane) Window.getWindows().stream()
                    .filter(w -> w.getScene() != null && w.getScene().lookup("#path-dialog") != null).findFirst().orElseThrow().getScene()
                    .getRoot();
                @SuppressWarnings("unchecked")
                ListView<Path> files = (ListView<Path>) picker.lookup("#path-entries");
                waitForFx(() -> !picker.lookupButton(ButtonType.OK).isDisabled());
                Path entry = files.getItems().stream().filter(path -> {
                    try {
                        return Files.isSameFile(path, archive);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }).findFirst().orElseThrow();
                files.getSelectionModel().select(entry);
                ((Button) picker.lookupButton(ButtonType.OK)).fire();
            });
            assertEquals(archive.toRealPath(), RetroPathDialog.modpack(stage, data, ViewModels.fileBrowser()).orElseThrow().toRealPath());
            ModpackFileTree large = new ModpackFileTree("大型整合包", path -> path.endsWith("-keep.jar"));
            List<ModpackContent> largeEntries = new ArrayList<>();
            for (int i = 0; i < 5000; i++) {
                largeEntries.add(new ModpackContent("mods/" + i + "-keep.jar", false, 1024));
                largeEntries.add(new ModpackContent("mods/" + i + "-skip.jar", false, 1024));
            }
            large.setEntries(largeEntries);
            assertEquals(5000, large.selectedPaths().size());
            ((Button) large.lookup("#modpack-select-none")).fire();
            assertTrue(large.selectedPaths().isEmpty());
            ((Button) large.lookup("#modpack-select-all")).fire();
            assertEquals(10000, large.selectedPaths().size());
            ((Button) large.lookup("#modpack-select-recommended")).fire();
            assertEquals(5000, large.selectedPaths().size());
        } finally {
            stage.getScene().setRoot((Parent) original);
        }
    }

    private static CheckBoxTreeItem<ModpackContent> packItem(TreeItem<ModpackContent> root, String path) {
        if (root.getValue().path().equals(path)) {
            return (CheckBoxTreeItem<ModpackContent>) root;
        }
        for (var child : root.getChildren()) {
            var found = packItem(child, path);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void verifyLoaderReinstallation(Stage stage) throws Exception {
        var originalRoot = stage.getScene().getRoot();
        Path data = temp.resolve("loader-ui"), game = data.resolve("game");
        new SettingsStore(data).save(SettingsDefaults.create(game.toString(), IsolationMode.ALL));
        GameInstance vanilla = rootFixture(game, "plain", "Plain");
        var instanceJson = JsonFiles.GSON.toJsonTree(rootFixture(game, "fabric", "Fabric")).getAsJsonObject();
        instanceJson.addProperty("loader", "FABRIC");
        instanceJson.addProperty("loaderVersion", "1");
        GameInstance fabric = JsonFiles.GSON.fromJson(instanceJson, GameInstance.class);
        var profile = JsonFiles.read(InstanceDirectories.profile(game, fabric));
        profile.add("fuzzInstance", instanceJson);
        JsonFiles.write(InstanceDirectories.profile(game, fabric), profile);
        JsonInstanceRepository backing = new JsonInstanceRepository(data);
        backing.save(vanilla);
        backing.save(fabric);
        var commitReached = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var releaseQuery = new AtomicReference<>(new CountDownLatch(1));
        var holdQuery = new AtomicBoolean();
        InstanceRepository repository = new InstanceRepository() {
            public List<GameInstance> findAll() throws IOException {
                return backing.findAll();
            }

            public void remove(String id) throws IOException {
                backing.remove(id);
            }

            public void save(GameInstance value) throws IOException {
                if ("2".equals(value.loaderVersion())) {
                    commitReached.countDown();
                    try {
                        if (!releaseCommit.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Commit test timeout");
                        }
                    } catch (InterruptedException ex) {
                        throw new IOException("Commit was interrupted", ex);
                    }
                }
                backing.save(value);
            }
        };
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (holdQuery.get()) {
                try {
                    releaseQuery.get().await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = "[{\"loader\":{\"version\":\"2\"}},{\"loader\":{\"version\":\"1\"}}]".getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        var constructor = HttpTransport.class.getDeclaredConstructor(UnaryOperator.class);
        constructor.setAccessible(true);
        HttpTransport http = constructor.newInstance(
            (UnaryOperator<String>) url -> "http://127.0.0.1:" + server.getAddress().getPort() + "/loaders");
        LoaderInstaller installer = (root, workspace, version, selected, task) -> {
            Path artifact = root.resolve("libraries/fixture/" + selected + ".jar");
            Files.createDirectories(artifact.getParent());
            Files.writeString(artifact, "fresh " + selected);
            return JsonParser.parseString("{\"mainClass\":\"Fixture\",\"libraries\":[{\"name\":\"fixture:loader:" + selected
                + "\",\"downloads\":{\"artifact\":{\"path\":\"fixture/" + selected + ".jar\",\"url\":\"\"}}}]}").getAsJsonObject();
        };
        var files = new GameFiles(http);
        var resolver = new VersionResolver();
        var install = new LocalGameInstallation(repository, files, resolver, Map.of(LoaderType.FABRIC, installer));
        AccountAuthenticator auth = new AccountAuthenticator() {
            public Optional<Account> savedAccount() {
                return Optional.empty();
            }

            public GameSession login(TaskContext task) {
                throw new AssertionError();
            }

            public GameSession loginOffline(String name, TaskContext task) {
                throw new AssertionError();
            }

            public GameSession refresh(Account account, TaskContext task) {
                throw new AssertionError();
            }

            public void logout(TaskContext task) {
            }
        };
        try (var vm = ViewModels.create(new SettingsStore(data), repository, new VersionCatalog(http), auth, install, null)) {
            var view = new LauncherView(stage, vm);
            stage.getScene().setRoot(view);
            view.applyCss();
            view.layout();
            assertTrue(view.lookup("#change-instance-loader").isDisabled());
            vm.instancesModel().selectInstance(fabric);
            view.applyCss();
            view.layout();
            assertFalse(view.lookup("#change-instance-loader").isDisabled());
            // The query result listener must survive GC while a network response is pending.
            assertSame(vm.loaderVersions(), vm.loaderVersions());
            holdQuery.set(true);
            ((Button) view.lookup("#change-instance-loader")).fire();
            waitForFx(() -> vm.busyProperty().get());
            System.gc();
            releaseQuery.get().countDown();
            holdQuery.set(false);
            waitForFx(() -> !vm.busyProperty().get() && vm.loaderVersions().size() == 2);
            DialogPane chooser = loaderPane();
            @SuppressWarnings("unchecked")
            var versions = (ComboBox<String>) chooser.lookup("#replacement-loader-version");
            assertEquals("1", versions.getValue());
            assertEquals("请选择加载器版本", versions.getPromptText());
            versions.setValue("2");
            ImageIO.write(SwingFXUtils.fromFXImage(chooser.getScene().snapshot(null), null), "png", Path.of("build/ui-check/loader-version.png").toFile());
            Window chooserWindow = chooser.getScene().getWindow();
            ((Button) chooser.lookupButton(chooser.getButtonTypes().getFirst())).fire();
            assertFalse(
                Window.getWindows().stream().anyMatch(window -> window instanceof Stage dialog && "完整重装加载器".equals(dialog.getTitle())));
            assertFalse(chooserWindow.isShowing());
            assertTrue(vm.installingProperty().get());
            waitForFx(() -> commitReached.getCount() == 0);
            assertTrue(vm.isCommitting());
            vm.cancel(); // Commit cannot be cancelled after publication has begun, and must
                         // not block FX.
            assertTrue(vm.busyProperty().get());
            releaseCommit.countDown();
            waitForFx(() -> !vm.busyProperty().get());
            assertEquals("2", vm.selectedProperty().get().loaderVersion());
            assertFalse(vm.isCommitting());
            assertEquals("fresh 2", Files.readString(game.resolve("libraries/fixture/2.jar")));
            assertEquals("world fabric", Files.readString(InstanceDirectories.directory(game, fabric).resolve("saves/world/level.dat")));
            assertEquals("Fabric 2", ((TextField) view.lookup("#instance-loader")).getText());
            assertFalse(vm.installingProperty().get());
            releaseQuery.set(new CountDownLatch(1));
            holdQuery.set(true);
            ((Button) view.lookup("#change-instance-loader")).fire();
            waitForFx(() -> !vm.busyProperty().get() && loaderPane().lookup("#refresh-loader-versions") != null);
            ((Button) loaderPane().lookup("#refresh-loader-versions")).fire();
            waitForFx(() -> vm.busyProperty().get());
            ((Button) loaderPane().lookup(".window-control")).fire();
            waitForFx(() -> !vm.busyProperty().get());
            releaseQuery.get().countDown();
            assertEquals("2", vm.selectedProperty().get().loaderVersion());
        } finally {
            releaseCommit.countDown();
            releaseQuery.get().countDown();
            server.stop(0);
            stage.getScene().setRoot(originalRoot);
        }
    }

    private void verifyDirectoryLoading(Stage stage) throws Exception {
        Path root = Files.createDirectories(temp.resolve("browser-loading")).toRealPath();
        Path large = Files.createDirectories(root.resolve("large"));
        Path small = Files.createDirectories(root.resolve("small"));
        Path expected = Files.createDirectories(small.resolve("only-folder"));
        for (int i = 0; i < 600; i++) {
            Files.createDirectories(large.resolve(String.format(Locale.ROOT, "folder-%04d", i)));
        }
        RetroPathDialog dialog = new RetroPathDialog(stage, "异步目录回归", large, true, ViewModels.fileBrowser());
        try {
            dialog.show();
            DialogPane pane = dialog.getDialogPane();
            Button choose = (Button) pane.lookupButton(ButtonType.OK);
            @SuppressWarnings("unchecked")
            ListView<Path> files = (ListView<Path>) pane.lookup("#path-entries");
            waitForFx(() -> !choose.isDisabled());
            assertEquals(600, files.getItems().size());
            assertEquals("folder-0000", files.getItems().getFirst().getFileName().toString());
            TextField address = (TextField) pane.lookup("#path-location");
            address.setText(large.toString());
            address.fireEvent(new ActionEvent());
            assertTrue(choose.isDisabled());
            address.setText(small.toString());
            address.fireEvent(new ActionEvent());
            waitForFx(() -> !choose.isDisabled());
            assertEquals(List.of(expected), files.getItems());
            assertEquals(small.toString(), address.getText());
            address.setText(large.toString());
            address.fireEvent(new ActionEvent());
            dialog.close();
            waitForFx(() -> !dialog.isShowing());
        } finally {
            dialog.close();
        }
    }

    private static void verifyRetroTooltip(ListCell<?> row) throws Exception {
        Tooltip tooltip = row.getTooltip();
        assertNotNull(tooltip);
        var position = row.localToScreen(row.getBoundsInLocal());
        tooltip.show(row, position.getMinX() + 20, position.getMaxY());
        try {
            var popup = (Region) tooltip.getSkin().getNode();
            popup.applyCss();
            popup.layout();
            assertEquals(Color.web("#ffffe1"), popup.getBackground().getFills().getFirst().getFill());
            assertEquals(CornerRadii.EMPTY, popup.getBackground().getFills().getFirst().getRadii());
            assertEquals(Color.BLACK, popup.getBorder().getStrokes().getFirst().getTopStroke());
            assertEquals(Color.BLACK, ((Label) popup).getTextFill());
            assertNull(popup.getEffect());
            ImageIO.write(SwingFXUtils.fromFXImage(popup.snapshot(null, null), null), "png", Path.of("build/ui-check/tooltip.png").toFile());
        } finally {
            tooltip.hide();
        }
    }

    private static void verifyResourcePacks(Stage stage, LauncherView view) throws Exception {
        var vm = view.viewModel();
        GameInstance instance = vm.selectedProperty().get();
        assertNotNull(instance);
        Path folder = vm.resourcePacksModel().directory(vm.settingsProperty().get(), instance);
        Path archive = ModFixtures.jar(folder.resolve("复古材质.zip"),
            Map.of("pack.mcmeta", "{\"pack\":{\"pack_format\":34,\"description\":\"测试资源包\"}}"));
        assertEquals(vm.modsModel().directory(vm.settingsProperty().get(), instance).resolveSibling("resourcepacks"), folder);
        view.showPage(6);
        @SuppressWarnings("unchecked")
        ListView<ContentFile> packs = (ListView<ContentFile>) view.lookup("#resource-pack-list");
        assertNotNull(packs);
        waitForFx(() -> packs.getItems().stream().anyMatch(pack -> pack.path().equals(archive)));
        assertEquals("复古材质", packs.getItems().getFirst().name());
        assertEquals("格式 34", packs.getItems().getFirst().version());
        packs.getSelectionModel().selectFirst();
        Button disable = (Button) view.lookup("#disable-resource-packs");
        assertFalse(disable.isDisabled());
        ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png",
            Path.of("build/ui-check/resource-packs.png").toFile());
        disable.fire();
        waitForFx(() -> !vm.busyProperty().get() && packs.getItems().stream()
            .anyMatch(pack -> pack.path().getFileName().toString().equals("复古材质.zip.disabled") && !pack.enabled()));
        assertTrue(Files.isRegularFile(folder.resolve("复古材质.zip.disabled")));
    }

    private static void verifySchematics(Stage stage, LauncherView view) throws Exception {
        var vm = view.viewModel();
        Path root = vm.schematicsModel().directory(vm.settingsProperty().get(), vm.selectedProperty().get());
        Path folder = Files.createDirectories(root.resolve("建筑"));
        Path file = Files.writeString(folder.resolve("村庄.litematic"), "fixture");
        Files.writeString(root.resolve("道路.schem"), "fixture");
        view.showPage(7);
        @SuppressWarnings("unchecked")
        ListView<ContentFile> list = (ListView<ContentFile>) view.lookup("#schematic-list");
        waitForFx(() -> list.getItems().stream().anyMatch(item -> item.path().equals(folder) && item.directory()));
        assertNotNull(view.lookup("#import-schematics"));
        assertNotNull(view.lookup("#schematics-create-folder"));
        assertNull(view.lookup("#enable-schematics"));
        ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png",
            Path.of("build/ui-check/schematic-folders.png").toFile());
        list.getSelectionModel().selectFirst();
        Event.fireEvent(list, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
            javafx.scene.input.KeyCode.ENTER, false, false, false, false));
        waitForFx(() -> list.getItems().stream().anyMatch(item -> item.path().equals(file)));
        ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png",
            Path.of("build/ui-check/schematics.png").toFile());
        ((Button) view.lookup("#schematics-back")).fire();
        waitForFx(() -> list.getItems().stream().anyMatch(item -> item.path().equals(folder) && item.directory()));
    }

    private static void verifyNavigationLayout(LauncherView view) throws Exception {
        VBox sidebar = (VBox) view.lookup(".navigation");
        var buttons = sidebar.getChildren().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
        assertEquals(List.of("游戏实例", "账号", "下载", "模组", "资源包", "原理图", "设置", "关于"), buttons.stream().map(Button::getText).toList());
        assertEquals("关于", buttons.getLast().getText());
        double gap = buttons.get(1).getBoundsInParent().getMinY() - buttons.getFirst().getBoundsInParent().getMaxY();
        for (int i = 1; i < buttons.size(); i++) {
            assertEquals(gap, buttons.get(i).getBoundsInParent().getMinY() - buttons.get(i - 1).getBoundsInParent().getMaxY(), 0.5);
        }
        var column = sidebar.localToScene(sidebar.getBoundsInLocal());
        double visibleLeft = view.localToScene(view.getBoundsInLocal()).getMinX() + view.getBorder().getInsets().getLeft();
        double visibleRight = column.getMaxX() - sidebar.getBorder().getInsets().getRight();
        var rendered = view.snapshot(null, null);
        Button first = buttons.getFirst();
        double iconX = first.getGraphic().getBoundsInParent().getMinX();
        double textX = first.lookup(".text").getBoundsInParent().getMinX();
        for (Button button : buttons) {
            assertEquals(iconX, button.getGraphic().getBoundsInParent().getMinX(), 0.5, button.getText() + "图标对齐");
            assertEquals(textX, button.lookup(".text").getBoundsInParent().getMinX(), 0.5, button.getText() + "文字对齐");
            var bounds = button.localToScene(button.getBoundsInLocal());
            double left = bounds.getMinX() - visibleLeft;
            double right = visibleRight - bounds.getMaxX();
            assertEquals(left, right, 0.5, button.getText() + "：窗口内边框至按钮、按钮至分隔线内侧的灰色留白应相同");
            int y = (int) ((bounds.getMinY() + bounds.getMaxY()) / 2);
            for (int x = (int) visibleLeft; x < (int) bounds.getMinX(); x++) {
                assertEquals(Color.web("#c0c0c0"), rendered.getPixelReader().getColor(x, y), "左侧测量区域应为实际灰色留白");
            }
            for (int x = (int) bounds.getMaxX(); x < (int) visibleRight; x++) {
                assertEquals(Color.web("#c0c0c0"), rendered.getPixelReader().getColor(x, y), "右侧测量区域应为实际灰色留白");
            }
        }
        Files.createDirectories(Path.of("build/ui-check"));
        SnapshotParameters capture = new SnapshotParameters();
        capture.setViewport(new Rectangle2D(0, 0, column.getMaxX() + 2, view.getHeight()));
        ImageIO.write(SwingFXUtils.fromFXImage(view.snapshot(capture, null), null), "png", Path.of("build/ui-check/navigation-gutters.png").toFile());
    }

    private static void verifyAboutPage(Stage stage, LauncherView view) throws Exception {
        view.showPage(5);
        view.applyCss();
        view.layout();
        assertEquals("版本：" + LauncherInfo.VERSION, ((Label) view.lookup("#about-version")).getText());
        assertEquals(URI.create("https://github.com/1024-byteeeee/FuzzLauncher"), view.lookup("#about-repository").getUserData());
        assertEquals(URI.create("https://github.com/1024-byteeeee/FuzzLauncher/issues"), view.lookup("#about-feedback").getUserData());
        var clipboard = Clipboard.getSystemClipboard();
        Map<DataFormat, Object> previous = new HashMap<>();
        for (var format : clipboard.getContentTypes()) {
            Object value = clipboard.getContent(format);
            if (value != null) {
                previous.put(format, value);
            }
        }
        try {
            ((Button) view.lookup("#copy-about-runtime")).fire();
            String copied = clipboard.getString();
            assertTrue(copied.contains(LauncherInfo.VERSION));
            assertTrue(copied.contains(System.getProperty("java.version")));
            assertTrue(copied.contains(System.getProperty("os.name")));
        } finally {
            clipboard.setContent(previous);
        }
        ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png", Path.of("build/ui-check/about.png").toFile());
    }

    private void verifyLocalMods(Stage stage, LauncherView view) throws Exception {
        LauncherViewModel vm = view.viewModel();
        waitForFx(() -> !vm.busyProperty().get());
        GameInstance first = vm.selectedProperty().get();
        GameInstance second = vm.instances().stream().filter(instance -> !instance.equals(first)).findFirst().orElseThrow();
        Path directory = vm.modsModel().directory(vm.settingsProperty().get(), first);
        ModFixtures.fabricWithLogo(directory.resolve("alpha.jar"), "alpha", 0xff32aa68);
        ModFixtures.fabric(directory.resolve("beta.jar"), "beta");
        ModFixtures.fabric(vm.modsModel().directory(vm.settingsProperty().get(), second).resolve("other.jar"), "other");
        view.showPage(4);
        view.applyCss();
        view.layout();
        @SuppressWarnings("unchecked")
        ListView<ContentFile> table = (ListView<ContentFile>) view.lookup("#mod-list");
        @SuppressWarnings("unchecked")
        ComboBox<GameInstance> instance = (ComboBox<GameInstance>) view.lookup("#mod-instance");
        waitForFx(() -> table.getItems().size() == 2 && !view.lookup("#import-mods").isDisabled());
        table.getSelectionModel().clearAndSelect(0);
        view.applyCss();
        view.layout();
        ListCell<?> firstRow = cell(table, 0);
        verifyRetroTooltip(firstRow);
        ImageView logo = (ImageView) firstRow.lookup(".mod-logo");
        assertEquals(0xff32aa68, logo.getImage().getPixelReader().getArgb(0, 0));
        assertNotSame(RetroPage.image("forge-retro"), RetroPage.image("mods"));
        Button modNavigation = view.lookupAll(".nav-button").stream().filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> button.getText().equals("模组")).findFirst().orElseThrow();
        assertSame(RetroPage.image("mods"), ((ImageView) modNavigation.getGraphic().lookup(".image-view")).getImage());
        assertEquals(Color.TRANSPARENT, RetroPage.image("mods").getPixelReader().getColor(0, 0));
        assertNotNull(firstRow.lookup(".mod-entry-name"));
        assertNotNull(firstRow.lookup(".mod-entry-version"));
        assertTrue(((Label) firstRow.lookup(".mod-entry-subtitle")).getText().contains("alpha.jar"));
        var detailsError = new AtomicReference<Throwable>();
        Platform.runLater(() -> {
            DialogPane pane = (DialogPane) Window.getWindows().stream()
                .filter(window -> window.getScene() != null && window.getScene().lookup("#mod-details-dialog") != null).findFirst()
                .orElseThrow().getScene().getRoot();
            try {
                assertTrue(((TextArea) pane.lookup("#mod-details")).getText().contains("alpha"));
            } catch (Throwable ex) {
                detailsError.set(ex);
            } finally {
                ((Button) pane.lookupButton(ButtonType.CLOSE)).fire();
            }
        });
        ((Button) firstRow.lookup(".mod-info")).fire();
        assertNull(detailsError.get());
        CheckBox toggle = (CheckBox) firstRow.lookup(".mod-enabled");
        assertTrue(toggle.isSelected());
        toggle.fire();
        assertTrue(toggle.isDisabled());
        waitForFx(() -> !vm.busyProperty().get() && table.getItems().stream().anyMatch(mod -> !mod.enabled()));
        assertTrue(Files.isRegularFile(directory.resolve("alpha.jar.disabled")));
        waitForFx(() -> !view.lookup("#enable-mods").isDisabled());
        ((Button) view.lookup("#enable-mods")).fire();
        waitForFx(() -> !vm.busyProperty().get() && table.getItems().size() == 2 && table.getItems().stream().allMatch(ContentFile::enabled));
        TextField search = (TextField) view.lookup("#mod-search");
        search.setText("no-match-fixture");
        assertTrue(table.getItems().isEmpty());
        assertTrue(view.lookup("#delete-mods").isDisabled());
        search.setText("alpha");
        assertEquals(1, table.getItems().size());
        search.clear();
        instance.setValue(second);
        instance.setValue(first);
        instance.setValue(second);
        waitForFx(() -> table.getItems().size() == 1 && table.getItems().getFirst().name().equals("other")
            && !view.lookup("#import-mods").isDisabled());
        instance.setValue(first);
        waitForFx(() -> table.getItems().size() == 2 && !view.lookup("#import-mods").isDisabled());
        table.getSelectionModel().selectAll();
        var runningField = LauncherViewModel.class.getDeclaredField("gameRunning");
        runningField.setAccessible(true);
        var running = (BooleanProperty) runningField.get(vm);
        running.set(true);
        try {
            for (String id : List.of("import-mods", "enable-mods", "disable-mods", "delete-mods")) {
                assertTrue(view.lookup("#" + id).isDisabled());
            }
            assertTrue(table.lookupAll(".mod-enabled").stream().allMatch(Node::isDisabled));
            vm.modsModel().setEnabled(directory, List.copyOf(table.getItems()), false, () -> {
                throw new AssertionError();
            });
            assertFalse(vm.busyProperty().get());
            assertTrue(Files.exists(directory.resolve("alpha.jar")));
        } finally {
            running.set(false);
        }
        Path sources = Files.createDirectories(temp.resolve("mod-picker-source")).toRealPath();
        Path one = ModFixtures.fabric(sources.resolve("one.jar"), "one");
        Path two = ModFixtures.fabric(sources.resolve("two.jar"), "two");
        var pickerError = new AtomicReference<Throwable>();
        Platform.runLater(() -> {
            DialogPane picker = (DialogPane) Window.getWindows().stream()
                .filter(window -> window.getScene() != null && window.getScene().lookup("#path-dialog") != null).findFirst().orElseThrow()
                .getScene().getRoot();
            try {
                TextField location = (TextField) picker.lookup("#path-location");
                location.setText(sources.toString());
                location.fireEvent(new ActionEvent());
                waitForFx(() -> !picker.lookupButton(ButtonType.OK).isDisabled());
                @SuppressWarnings("unchecked")
                ListView<Path> entries = (ListView<Path>) picker.lookup("#path-entries");
                assertTrue(entries.getItems().contains(one));
                assertTrue(entries.getItems().contains(two));
                entries.getSelectionModel().selectAll();
                ((Button) picker.lookupButton(ButtonType.OK)).fire();
            } catch (Throwable ex) {
                pickerError.set(ex);
                ((Button) picker.lookupButton(ButtonType.CANCEL)).fire();
            }
        });
        ((Button) view.lookup("#import-mods")).fire();
        if (pickerError.get() != null) {
            throw new AssertionError(pickerError.get());
        }
        waitForFx(() -> !vm.busyProperty().get() && table.getItems().size() == 4 && !view.lookup("#import-mods").isDisabled());
        assertTrue(Files.exists(one));
        assertTrue(Files.exists(two));
        ModFixtures.jar(directory.resolve("long.jar"), Map.of("fabric.mod.json",
            "{\"id\":\"long\",\"name\":\"" + "超长模组名称".repeat(40) + "\",\"version\":\"" + "1.0-long-version-".repeat(20) + "\"}"));
        ((Button) view.lookup("#refresh-mods")).fire();
        waitForFx(() -> table.getItems().size() == 5 && !view.lookup("#import-mods").isDisabled());
        @SuppressWarnings("unchecked")
        ComboBox<String> order = (ComboBox<String>) view.lookup("#mod-sort");
        order.setValue("按文件名");
        assertEquals("long.jar", table.getItems().get(2).path().getFileName().toString());
        view.applyCss();
        view.layout();
        ListCell<?> longRow = cell(table, 2);
        Node locate = longRow.lookup(".mod-locate"), info = longRow.lookup(".mod-info");
        assertTrue(info.localToScene(info.getBoundsInLocal()).getMaxX() <= stage.getScene().getWidth());
        assertTrue(longRow.lookup(".mod-entry-version").getBoundsInParent().getWidth() <= 161);
        assertTrue(longRow.lookup(".mod-entry-version").getBoundsInParent().getWidth() >= 40);
        assertTrue(locate.localToScene(locate.getBoundsInLocal()).getMaxX() < info.localToScene(info.getBoundsInLocal()).getMinX());
        ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png", Path.of("build/ui-check/mods.png").toFile());
        view.showPage(3);
        view.applyCss();
        view.layout();
    }

    private static DialogPane loaderPane() {
        return (DialogPane) Window.getWindows().stream()
            .filter(window -> window.getScene() != null && window.getScene().lookup("#loader-version-dialog") != null).findFirst()
            .orElseThrow().getScene().getRoot();
    }

    private static void waitForFx(BooleanSupplier done) {
        if (done.getAsBoolean()) {
            return;
        }
        Object loop = new Object();
        boolean[] finished = { false };
        var timer = new Timeline(new KeyFrame(Duration.millis(20), event -> {
            if (!finished[0] && done.getAsBoolean()) {
                finished[0] = true;
                Platform.exitNestedEventLoop(loop, true);
            }
        }));
        timer.setCycleCount(250);
        timer.setOnFinished(event -> {
            if (!finished[0]) {
                finished[0] = true;
                Platform.exitNestedEventLoop(loop, false);
            }
        });
        timer.play();
        try {
            assertEquals(true, Platform.enterNestedEventLoop(loop), "Timed out waiting for device login UI");
        } finally {
            timer.stop();
        }
    }

    private static void verifyDisabledMenuHighlight(Stage stage) {
        MenuItem disabled = new MenuItem("禁用项目");
        disabled.setDisable(true);
        MenuItem enabled = new MenuItem("可用项目");
        ContextMenu menu = new ContextMenu(disabled, enabled);
        menu.show(stage, stage.getX() + 50, stage.getY() + 50);
        try {
            Node root = menu.getScene().getRoot();
            root.applyCss();
            Node blocked = disabled.getStyleableNode();
            var idleBackground = ((Region) blocked).getBackground();
            var idleText = ((Label) blocked.lookup(".label")).getTextFill();
            blocked.pseudoClassStateChanged(PseudoClass.getPseudoClass("focused"), true);
            blocked.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true);
            root.applyCss();
            var background = ((Region) blocked).getBackground();
            assertEquals(idleBackground, background, "Disabled menu background must not change on hover");
            assertEquals(Color.web("#c0c0c0"), background.getFills().getFirst().getFill());
            assertEquals(1.0, blocked.getOpacity());
            assertEquals(idleText, ((Label) blocked.lookup(".label")).getTextFill());
            assertEquals(Color.GRAY, idleText);
            enabled.getStyleableNode().pseudoClassStateChanged(PseudoClass.getPseudoClass("focused"), true);
            root.applyCss();
            assertEquals(Color.NAVY, ((Region) enabled.getStyleableNode()).getBackground().getFills().getFirst().getFill());
        } finally {
            menu.hide();
        }
    }

    private static void verifyLauncherVisibility(Stage stage, LauncherView view, FuzzApplication app) throws Exception {
        view.showPage(3);
        view.applyCss();
        TextField root = (TextField) view.lookup("#game-root-path");
        String savedRoot = root.getText();
        root.setText(savedRoot + "-draft");
        view.showPage(3);
        assertSame(root, view.lookup("#game-root-path"));
        answerUnsavedSettings("继续编辑");
        view.showPage(0);
        assertSame(root, view.lookup("#game-root-path"));
        assertEquals(savedRoot + "-draft", root.getText());
        assertTrue(view.lookupAll(".selected-nav").stream().anyMatch(node -> Integer.valueOf(3).equals(node.getUserData())));
        root.setText(savedRoot);
        view.showPage(0);
        assertNull(view.lookup("#game-root-path"));
        view.showPage(3);
        ((TextField) view.lookup("#game-root-path")).setText(savedRoot + "-draft");
        answerUnsavedSettings("放弃更改");
        view.showPage(0);
        assertNull(view.lookup("#game-root-path"));
        view.showPage(3);
        assertEquals(savedRoot, ((TextField) view.lookup("#game-root-path")).getText());
        @SuppressWarnings("unchecked")
        var choice = (ComboBox<LauncherVisibility>) view.lookup("#launcher-visibility");
        assertEquals(List.of(LauncherVisibility.values()), choice.getItems());
        assertEquals(LauncherVisibility.KEEP, choice.getValue());
        var vmField = FuzzApplication.class.getDeclaredField("viewModel");
        vmField.setAccessible(true);
        var vm = (LauncherViewModel) vmField.get(app);
        LauncherSettings original = vm.settingsProperty().get();
        choice.setValue(LauncherVisibility.HIDE_AND_REOPEN);
        Button save = view.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> "保存设置".equals(button.getText())).findFirst().orElseThrow();
        awaitAccountOperation(vm, save::fire);
        assertEquals(LauncherVisibility.HIDE_AND_REOPEN, new SettingsStore(vm.dataDirectory()).load().launcherVisibility());
        view.showPage(0);
        view.showPage(3);
        assertEquals(LauncherVisibility.HIDE_AND_REOPEN, ((ComboBox<?>) view.lookup("#launcher-visibility")).getValue());
        view.showPage(0);
        awaitAccountOperation(vm, () -> vm.settingsModel().saveSettings(original));
        var quits = new AtomicInteger();
        var controller = new LauncherVisibilityController(stage, quits::incrementAndGet);
        try {
            controller.started(LauncherVisibility.KEEP);
            assertTrue(stage.isShowing());
            controller.exited(0);
            assertEquals(0, quits.get());
            controller.started(LauncherVisibility.HIDE_AND_REOPEN);
            assertFalse(stage.isShowing());
            assertFalse(Platform.isImplicitExit());
            controller.exited(0);
            assertTrue(stage.isShowing());
            assertFalse(stage.isIconified());
            assertTrue(Platform.isImplicitExit());
            controller.started(LauncherVisibility.HIDE);
            assertFalse(stage.isShowing());
            controller.exited(1);
            assertTrue(stage.isShowing(), "Crash must restore the window before displaying its error");
            controller.started(LauncherVisibility.HIDE);
            controller.exited(0);
            assertEquals(1, quits.get());
            stage.show();
            controller.started(LauncherVisibility.EXIT);
            assertEquals(2, quits.get());
        } finally {
            stage.setIconified(false);
            stage.show();
            Platform.setImplicitExit(true);
        }
    }

    private static void answerUnsavedSettings(String answer) {
        Platform.runLater(() -> {
            var pane = Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene)
                .map(scene -> scene.lookup("#unsaved-settings-question"))
                .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
            var choice = pane.getButtonTypes().stream().filter(type -> type.getText().equals(answer)).findFirst().orElseThrow();
            ((Button) pane.lookupButton(choice)).fire();
        });
    }

    private static void verifyRootBrowser(Stage stage, LauncherViewModel vm, Path data) throws Exception {
        var original = stage.getScene().getRoot();
        Path chosen = Files.createDirectories(data.resolve("picked-root")).toRealPath();
        SettingsPage settings = new SettingsPage(stage, vm, ignored -> {
        });
        stage.getScene().setRoot(settings);
        settings.applyCss();
        settings.layout();
        Button browse = (Button) settings.lookup("#browse-game-root");
        assertFalse(browse.isDisabled());
        Platform.runLater(() -> {
            Window window = Window.getWindows().stream().filter(w -> w.getScene() != null && w.getScene().lookup("#path-dialog") != null)
                .findFirst().orElseThrow();
            DialogPane pane = (DialogPane) window.getScene().getRoot();
            waitForFx(() -> !pane.lookupButton(ButtonType.OK).isDisabled());
            ((TextField) pane.lookup("#path-location")).setText(chosen.toString());
            ((Button) pane.lookupButton(ButtonType.OK)).fire();
            waitForFx(() -> !pane.lookupButton(ButtonType.OK).isDisabled());
            assertEquals(chosen.toString(), ((TextField) pane.lookup("#path-location")).getText());
            ((Button) pane.lookupButton(ButtonType.OK)).fire();
        });
        browse.fire();
        assertEquals(chosen.toString(), ((TextField) settings.lookup("#game-root-path")).getText());
        stage.getScene().setRoot(original);
    }

    private static GameInstance rootFixture(Path root, String id, String name) throws Exception {
        GameInstance item = new GameInstance(id, name, "base", LoaderType.VANILLA, null, IsolationOverride.INHERIT, 4096);
        JsonFiles.write(root.resolve("versions/base/base.json"), JsonParser.parseString("{\"id\":\"base\",\"mainClass\":\"Main\",\"libraries\":[]}"));
        Files.writeString(root.resolve("versions/base/base.jar"), "fixture client");
        var marker = new JsonObject();
        marker.addProperty("id", id);
        marker.addProperty("inheritsFrom", "base");
        marker.add("fuzzInstance", JsonFiles.GSON.toJsonTree(item));
        JsonFiles.write(InstanceDirectories.profile(root, item), marker);
        Path world = InstanceDirectories.directory(root, item).resolve("saves/world/level.dat");
        Files.createDirectories(world.getParent());
        Files.writeString(world, "world " + id);
        return item;
    }

    private static void verifyGameRootSwitch(Stage stage, LauncherViewModel vm, Path data) throws Exception {
        var restarts = new AtomicInteger();
        vm.settingsModel().onRestartRequired(restarts::incrementAndGet);
        var original = vm.settingsProperty().get();
        Path oldRoot = Path.of(original.gameRoot()), newRoot = data.resolve("other-game-root");
        GameInstance old = rootFixture(oldRoot, "old-root", "Old world"), target = rootFixture(newRoot, "new-root", "New world");
        awaitAccountOperation(vm, vm.instancesModel()::refreshInstances);
        assertEquals(List.of(old), vm.instances());
        verifyRootBrowser(stage, vm, data); // The browse button must also work when instances already exist.
        var changed = SettingsDefaults.create(newRoot.toString(), original.isolation(), original.downloadSource(), original.dataDirectory());
        awaitAccountOperation(vm, () -> vm.settingsModel().saveSettings(changed));
        assertEquals(1, restarts.get());
        assertEquals(List.of(target), vm.instances());
        assertEquals(target, vm.selectedProperty().get());
        assertEquals(changed, new SettingsStore(data).load());
        assertEquals("world old-root", Files.readString(InstanceDirectories.directory(oldRoot, old).resolve("saves/world/level.dat")));
        awaitAccountOperation(vm, () -> vm.settingsModel().saveSettings(original));
        assertEquals(2, restarts.get());
        assertEquals(List.of(old), vm.instances());
        assertEquals("world new-root", Files.readString(InstanceDirectories.directory(newRoot, target).resolve("saves/world/level.dat")));
        Path occupied = Files.createDirectories(data.resolve("occupied-data"));
        Files.writeString(occupied.resolve("keep.txt"), "keep");
        var failed = SettingsDefaults.create(newRoot.toString(), original.isolation(), original.downloadSource(), occupied.toString());
        awaitAccountOperation(vm, () -> vm.settingsModel().saveSettings(failed), false);
        assertEquals(2, restarts.get());
        assertEquals(original, vm.settingsProperty().get());
        assertEquals(original, new SettingsStore(data).load());
        assertEquals(List.of(old), new JsonInstanceRepository(data).findAll());
        Path emptyRoot = data.resolve("empty-game-root");
        awaitAccountOperation(vm, () -> vm.settingsModel()
            .saveSettings(SettingsDefaults.create(emptyRoot.toString(), original.isolation(), original.downloadSource())));
        assertTrue(vm.instances().isEmpty());
        assertNull(vm.selectedProperty().get());
        awaitAccountOperation(vm, () -> vm.settingsModel().saveSettings(original));
        assertEquals(List.of(old), vm.instances());
        Path movedRoot = data.resolve("moved-game-root");
        var migrated = SettingsDefaults.create(movedRoot.toString(), original.isolation(), original.downloadSource(),
            original.dataDirectory(), original.launcherVisibility());
        awaitAccountOperation(vm, () -> vm.settingsModel().saveSettings(migrated, true));
        assertEquals(5, restarts.get());
        assertEquals(List.of(old), vm.instances());
        assertFalse(Files.exists(oldRoot));
        assertEquals("world old-root", Files.readString(InstanceDirectories.directory(movedRoot, old).resolve("saves/world/level.dat")));
        // A post-copy configuration failure must roll back the destination, not remove source
        // files.
        Path rolledBack = data.resolve("rolled-back-root");
        var badMigration = SettingsDefaults.create(rolledBack.toString(), original.isolation(), original.downloadSource(),
            occupied.toString(), original.launcherVisibility());
        awaitAccountOperation(vm, () -> vm.settingsModel().saveSettings(badMigration, true), false);
        assertEquals(5, restarts.get());
        assertFalse(Files.exists(rolledBack));
        assertEquals(migrated, new SettingsStore(data).load());
        assertTrue(Files.isRegularFile(InstanceDirectories.profile(movedRoot, old)));
        awaitAccountOperation(vm, () -> vm.settingsModel().saveSettings(original, true));
        assertEquals(6, restarts.get());
        assertFalse(Files.exists(movedRoot));
        verifyMigrationQuestion(stage, vm, data, restarts);
    }

    private static void verifyMigrationQuestion(Stage stage, LauncherViewModel vm, Path data, AtomicInteger restarts) throws Exception {
        Node original = stage.getScene().getRoot();
        Path target = data.resolve("ui-root");
        try {
            SettingsPage page = new SettingsPage(stage, vm, ignored -> {
            });
            stage.getScene().setRoot(page);
            ((TextField) page.lookup("#game-root-path")).setText(target.toString());
            Button save = page.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> "保存设置".equals(button.getText())).findFirst().orElseThrow();
            int before = restarts.get();
            for (boolean cancel : new boolean[] { true, false }) {
                Platform.runLater(() -> {
                    var pane = (DialogPane) Window.getWindows().stream()
                        .filter(window -> window.getScene() != null && window.getScene().lookup("#game-root-migration-question") != null)
                        .findFirst().orElseThrow().getScene().getRoot();
                    assertEquals(List.of("迁移并重启", "不迁移，仅重启", "取消"), pane.getButtonTypes().stream().map(ButtonType::getText).toList());
                    var selected = pane.getButtonTypes().stream().filter(type -> type.getText().equals(cancel ? "取消" : "不迁移，仅重启"))
                        .findFirst().orElseThrow();
                    ((Button) pane.lookupButton(selected)).fire();
                });
                if (cancel) {
                    save.fire();
                    assertFalse(vm.busyProperty().get());
                    assertEquals(before, restarts.get());
                    assertFalse(Files.exists(target));
                } else {
                    awaitAccountOperation(vm, save::fire);
                    assertEquals(before + 1, restarts.get());
                    assertTrue(Files.isDirectory(target));
                }
            }
        } finally {
            stage.getScene().setRoot((Parent) original);
        }
    }

    private static void verifyInstanceEditingLock(LauncherView view, FuzzApplication app, JsonInstanceRepository repository)
        throws Exception {
        var vmField = FuzzApplication.class.getDeclaredField("viewModel");
        vmField.setAccessible(true);
        LauncherViewModel vm = (LauncherViewModel) vmField.get(app);
        var runningField = LauncherViewModel.class.getDeclaredField("gameRunning");
        runningField.setAccessible(true);
        var running = (BooleanProperty) runningField.get(vm);
        view.showPage(0);
        view.applyCss();
        view.layout();
        Node form = view.lookup("#instance-settings-form");
        var preview = (ImageView) view.lookup("#instance-preview-image");
        assertTrue(preview.isPreserveRatio());
        var frame = (Region) preview.getParent();
        double originalWidth = frame.getWidth();
        double originalHeight = frame.getHeight();
        for (double[] size : new double[][] { { 580, 90 }, { 580, 210 }, { 300, 250 } }) {
            frame.resize(size[0], size[1]);
            var crop = preview.getViewport();
            assertEquals(preview.getFitWidth() / preview.getFitHeight(), crop.getWidth() / crop.getHeight(), 0.000001,
                "Preview must crop instead of stretching at every aspect ratio");
            assertTrue(crop.getMinX() >= 0 && crop.getMinY() >= 0);
            assertTrue(crop.getMaxX() <= preview.getImage().getWidth() + 0.000001);
            assertTrue(crop.getMaxY() <= preview.getImage().getHeight() + 0.000001);
        }
        frame.resize(originalWidth, originalHeight);
        assertFalse(form.isDisabled());
        var before = repository.findAll();
        try {
            running.set(true);
            assertTrue(form.isDisabled());
            for (Node input : form.lookupAll(".text-field, .combo-box, .spinner, .button")) {
                assertTrue(input.isDisabled(), "Running instance input remained editable: " + input);
            }
            assertTrue(view.lookup("#save-instance-settings").isDisabled());
            // Navigate away and back: newly built controls must also inherit the lock.
            view.showPage(3);
            view.showPage(0);
            view.applyCss();
            view.layout();
            assertTrue(view.lookup("#instance-settings-form").isDisabled());
            assertEquals(before, repository.findAll());
        } finally {
            running.set(false);
        }
        assertFalse(view.lookup("#instance-settings-form").isDisabled());
        assertFalse(view.lookup("#instance-min-memory").isDisabled());
        assertFalse(view.lookup("#instance-memory").isDisabled());
        assertFalse(view.lookup("#instance-java-path").isDisabled());
        assertFalse(view.lookup("#save-instance-settings").isDisabled());
    }

    private void verifyAccountExclusion(Stage stage) throws Exception {
        var original = stage.getScene().getRoot();
        for (int state = 0; state < 3; state++) {
            Account initial = state == 0 ? null
                : state == 1 ? new Account("microsoft-fixture", "uuid-fixture", "MicrosoftFixture") : Account.offline("OfflineFixture");
            var saved = new AtomicReference<>(initial);
            var logins = new AtomicInteger();
            AccountAuthenticator auth = new AccountAuthenticator() {
                public Optional<Account> savedAccount() {
                    return Optional.ofNullable(saved.get());
                }

                public GameSession login(TaskContext task) {
                    logins.incrementAndGet();
                    saved.set(new Account("microsoft-fixture", "uuid-fixture", "MicrosoftFixture"));
                    return new GameSession(saved.get(), "fixture", Instant.MAX);
                }

                public GameSession loginOffline(String name, TaskContext task) {
                    logins.incrementAndGet();
                    saved.set(Account.offline(name));
                    return GameSession.offline(saved.get());
                }

                public GameSession refresh(Account account, TaskContext task) {
                    throw new AssertionError("No refresh expected");
                }

                public void logout(TaskContext task) {
                    saved.set(null);
                }
            };
            Path data = temp.resolve("account-state-" + state);
            new SettingsStore(data).save(SettingsDefaults.create(data.resolve("game").toString(), IsolationMode.ALL));
            try (var vm = ViewModels.create(new SettingsStore(data), new JsonInstanceRepository(data), null, auth, null, null)) {
                if (state == 0) {
                    verifyRootBrowser(stage, vm, data);
                    var field = LauncherViewModel.class.getDeclaredField("gameRunning");
                    field.setAccessible(true);
                    var running = (BooleanProperty) field.get(vm);
                    running.set(true);
                    try {
                        vm.instancesModel().updateInstance(
                            new GameInstance("guard", "Guard", "1.20.4", LoaderType.VANILLA, null, IsolationOverride.INHERIT, 4096));
                        assertFalse(vm.busyProperty().get(), "Running instance update must not start a task");
                        assertEquals("游戏运行中，请先退出游戏再修改实例设置", vm.errorProperty().get());
                        assertTrue(new JsonInstanceRepository(data).findAll().isEmpty());
                    } finally {
                        running.set(false);
                    }
                    verifyGameRootSwitch(stage, vm, data);
                }
                AccountsPage page = new AccountsPage(stage, vm, ignored -> {
                });
                stage.getScene().setRoot(page);
                page.applyCss();
                page.layout();
                Button microsoft = (Button) page.lookup("#microsoft-login");
                Button offline = (Button) page.lookup("#offline-login");
                TextField name = (TextField) page.lookup("#offline-username");
                Button microsoftLogout = (Button) page.lookup("#microsoft-logout");
                Button offlineLogout = (Button) page.lookup("#offline-logout");
                name.setText("NewOffline");
                assertEquals(state != 0, microsoft.isDisabled());
                assertEquals(state != 0, offline.isDisabled());
                assertEquals(state == 1, name.isDisabled());
                assertTrue(microsoftLogout.isVisible() && offlineLogout.isVisible());
                assertEquals(state != 1, microsoftLogout.isDisabled());
                assertEquals(state != 2, offlineLogout.isDisabled());
                if (initial != null) {
                    microsoft.fire();
                    offline.fire();
                    vm.accountsModel().login();
                    vm.accountsModel().loginOffline("NewOffline");
                    if (initial.isOffline()) {
                        microsoft.fire();
                        vm.accountsModel().login();
                    } else {
                        offline.fire();
                        vm.accountsModel().loginOffline("NewOffline");
                    }
                    assertEquals(0, logins.get(), "Logged-in accounts must not reach authentication or overwrite credentials");
                    assertEquals(initial, saved.get());
                    assertEquals(initial, vm.accountProperty().get());
                    assertFalse(vm.errorProperty().get().isBlank());
                    awaitAccountOperation(vm, vm.accountsModel()::logout);
                    assertNull(vm.accountProperty().get());
                    assertFalse(name.isDisabled());
                    assertFalse(microsoft.isDisabled());
                    assertFalse(offline.isDisabled());
                    assertTrue(microsoftLogout.isDisabled() && offlineLogout.isDisabled());
                    awaitAccountOperation(vm, initial.isOffline() ? vm.accountsModel()::login : () -> vm.accountsModel().loginOffline("NewOffline"));
                    assertEquals(!initial.isOffline(), vm.accountProperty().get().isOffline());
                    assertEquals(1, logins.get());
                    assertTrue(microsoft.isDisabled());
                    assertTrue(offline.isDisabled());
                }
            } finally {
                stage.getScene().setRoot(original);
            }
        }
    }

    private static void awaitAccountOperation(LauncherViewModel vm, Runnable action) {
        awaitAccountOperation(vm, action, true);
    }

    private static void awaitAccountOperation(LauncherViewModel vm, Runnable action, boolean success) {
        Object loop = new Object();
        PauseTransition timeout = new PauseTransition(Duration.seconds(5));
        timeout.setOnFinished(event -> Platform.exitNestedEventLoop(loop, false));
        ChangeListener<Boolean> listener = (observable, old, busy) -> {
            if (!busy) {
                Platform.runLater(() -> Platform.exitNestedEventLoop(loop, true));
            }
        };
        vm.busyProperty().addListener(listener);
        try {
            action.run();
            assertTrue(vm.busyProperty().get());
            timeout.play();
            assertEquals(true, Platform.enterNestedEventLoop(loop));
            assertEquals(success, vm.errorProperty().get().isBlank(), vm.errorProperty().get());
        } finally {
            timeout.stop();
            vm.busyProperty().removeListener(listener);
        }
    }

    private static void verifyDropdownHighlight(LauncherView view) {
        ComboBox<?> combo = (ComboBox<?>) view.lookup(".combo-box");
        Object original = combo.getValue();
        combo.show();
        ListView<?> list = (ListView<?>) ((ComboBoxListViewSkin<?>) combo.getSkin()).getPopupContent();
        list.applyCss();
        list.layout();
        ListCell<?> second = cell(list, 1);
        ListCell<?> third = cell(list, 2);
        Event.fireEvent(second, mouse(second, MouseEvent.MOUSE_MOVED));
        list.applyCss();
        assertEquals(Color.NAVY, second.getBackground().getFills().getFirst().getFill());
        Event.fireEvent(third, mouse(third, MouseEvent.MOUSE_MOVED));
        Event.fireEvent(list, mouse(list, MouseEvent.MOUSE_EXITED));
        list.applyCss();
        assertEquals(Color.WHITE, second.getBackground().getFills().getFirst().getFill());
        assertEquals(Color.NAVY, third.getBackground().getFills().getFirst().getFill());
        assertEquals(original, combo.getValue(), "Hover must not commit a selection");
        Event.fireEvent(third, mouse(third, MouseEvent.MOUSE_PRESSED));
        Event.fireEvent(third, mouse(third, MouseEvent.MOUSE_RELEASED));
        assertEquals(third.getItem(), combo.getValue(), "Click must commit the hovered item");
        combo.hide();
        combo.getSelectionModel().select(combo.getItems().indexOf(original));
    }

    private static ListCell<?> cell(ListView<?> list, int index) {
        return list.lookupAll(".list-cell").stream().filter(node -> node instanceof ListCell<?> cell && cell.getIndex() == index)
            .map(node -> (ListCell<?>) node).findFirst().orElseThrow();
    }

    private static MouseEvent mouse(Node target, EventType<MouseEvent> type) {
        var point = target.localToScene(5, 5);
        return new MouseEvent(type, point.getX(), point.getY(), point.getX(), point.getY(), MouseButton.PRIMARY, 1, false, false, false,
            false, type == MouseEvent.MOUSE_PRESSED, false, false, false, false, true,
            new PickResult(target, point.getX(), point.getY()));
    }
}
