package com.fuzzlauncher.ui.shell;

import com.fuzzlauncher.application.model.LauncherInfo;
import com.fuzzlauncher.ui.about.AboutPage;
import com.fuzzlauncher.ui.accounts.AccountsPage;
import com.fuzzlauncher.ui.common.RetroPage;
import com.fuzzlauncher.ui.common.WindowDrag;
import com.fuzzlauncher.ui.common.WindowResize;
import com.fuzzlauncher.ui.downloads.DownloadsPage;
import com.fuzzlauncher.ui.instances.InstancesPage;
import com.fuzzlauncher.ui.mods.ContentPage;
import com.fuzzlauncher.ui.settings.SettingsPage;
import com.fuzzlauncher.ui.viewmodel.LauncherViewModel;

import javafx.beans.binding.Bindings;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;

import java.util.*;

/** Retro presentation only. Operations and persistent state live in the ViewModel. */
public final class LauncherView extends RetroPage {
    private final LauncherViewModel vm;
    private final BorderPane workspace = new BorderPane();
    private final List<Button> navigation = new ArrayList<>();
    private final Label title = new Label("游戏实例");
    private InstallProgressDialog installDialog;
    private BrowserLoginDialog loginDialog;
    private GameLogDialog gameLogDialog;

    public LauncherView(Stage stage, LauncherViewModel vm) {
        super(stage, ignored -> {
        });
        this.vm = vm;
        getStyleClass().add("window");
        WindowResize.install(this);
        setTop(chrome());
        setLeft(navigation());
        setCenter(workspace);
        setBottom(status());
        vm.loggingInProperty().addListener((observable, old, loggingIn) -> {
            if (loggingIn) {
                loginDialog = new BrowserLoginDialog(stage, vm);
                loginDialog.show();
            } else if (loginDialog != null) {
                loginDialog.close();
                loginDialog = null;
            }
        });
        vm.installingProperty().addListener((observable, old, installing) -> {
            if (installing) {
                installDialog = new InstallProgressDialog(stage, vm);
                installDialog.show();
            } else if (installDialog != null) {
                installDialog.close();
                installDialog = null;
            }
        });
        vm.errorProperty().addListener((o, old, value) -> {
            if (value != null && !value.isBlank()) {
                message(Alert.AlertType.ERROR, "操作未完成", value);
            }
        });
        vm.gamesModel().testGameProperty().addListener((observable, old, running) -> {
            if (running != null) {
                if (gameLogDialog != null) {
                    gameLogDialog.close();
                }
                gameLogDialog = new GameLogDialog(stage, vm.gamesModel(), running);
                gameLogDialog.show();
            }
        });
        showPage(0);
    }

    LauncherViewModel viewModel() {
        return vm;
    }

    private Node chrome() {
        HBox bar = new HBox(9, icon("grass", 28), label(LauncherInfo.TITLE, "title"));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("title-bar");
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        bar.getChildren().add(space);
        Button min = windowControl("M0 0 H12 V2 H0 Z", () -> stage.setIconified(true)),
            max = windowControl("M0 0 H12 V10 H0 Z M2 3 V8 H10 V3 Z", () -> stage.setMaximized(!stage.isMaximized())),
            close = windowControl("M0 0 H3 L6 3 L9 0 H12 L8 5 L12 10 H9 L6 7 L3 10 H0 L4 5 Z", () -> {
                if (vm.savingSettingsProperty().get() || vm.isCommitting()) {
                    message(Alert.AlertType.INFORMATION, "请稍候", "正在提交文件或保存设置，请等待完成后再退出。");
                    return;
                }
                if (!vm.busyProperty().get() || confirm("退出启动器", "当前任务将被取消。是否退出？")) {
                    vm.close();
                    stage.close();
                }
            });
        min.setAccessibleText("最小化");
        max.setAccessibleText("最大化或还原");
        close.setAccessibleText("关闭");
        for (Button b : List.of(min, max, close)) {
            bar.getChildren().add(b);
        }
        WindowDrag.install(bar, stage);
        return bar;
    }

    private Button windowControl(String path, Runnable action) {
        SVGPath glyph = new SVGPath();
        glyph.setContent(path);
        glyph.setFillRule(FillRule.EVEN_ODD);
        glyph.setSmooth(false);
        glyph.setMouseTransparent(true);
        StackPane graphic = new StackPane(glyph);
        graphic.setMinSize(14, 14);
        graphic.setPrefSize(14, 14);
        graphic.setMaxSize(14, 14);
        Button control = button("", action);
        control.setGraphic(graphic);
        control.getStyleClass().add("window-control");
        return control;
    }

    private Node navigation() {
        VBox box = new VBox(4);
        box.getStyleClass().add("navigation");
        box.setPrefWidth(215);
        box.setMinWidth(215);
        String[] names = { "游戏实例", "账号", "下载", "模组", "资源包", "光影包", "原理图", "设置", "关于" };
        String[] icons = { "nav-grass", "player", "nav-globe", "mods", "resource-packs", "shader-packs", "schematics", "wrench", "about" };
        int[] pages = { 0, 2, 1, 4, 6, 8, 7, 3, 5 };
        for (int i = 0; i < names.length; i++) {
            int page = pages[i];
            Button b = button(names[i], () -> showPage(page));
            b.setUserData(page);
            StackPane iconSlot = new StackPane(navigationIcon(icons[i]));
            iconSlot.setAlignment(Pos.CENTER);
            iconSlot.setMinSize(36, 36);
            iconSlot.setPrefSize(36, 36);
            iconSlot.setMaxSize(36, 36);
            b.setGraphic(iconSlot);
            b.setGraphicTextGap(12);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setMaxWidth(Double.MAX_VALUE);
            b.getStyleClass().add("nav-button");
            navigation.add(b);
            box.getChildren().add(b);
        }
        return box;
    }

    private static ImageView navigationIcon(String name) {
        // The grass block has a tapered silhouette and needs a little more room to match the other icons.
        ImageView view = icon(name, name.equals("nav-grass") ? 36 : 32);
        var image = view.getImage();
        var pixels = image.getPixelReader();
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        int left = width, top = height, right = -1, bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((pixels.getArgb(x, y) >>> 24) != 0) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right >= left) {
            view.setViewport(new Rectangle2D(left, top, right - left + 1, bottom - top + 1));
        }
        return view;
    }

    @Override
    protected void showPage(int page) {
        if (workspace.getCenter() instanceof SettingsPage settings && (page == 3 || !settings.confirmLeave())) {
            return;
        }
        for (int i = 0; i < navigation.size(); i++) {
            navigation.get(i).getStyleClass().remove("selected-nav");
            if (Objects.equals(navigation.get(i).getUserData(), page)) {
                navigation.get(i).getStyleClass().add("selected-nav");
            }
        }
        title.setText(new String[] { "游戏实例", "下载游戏", "微软账号", "启动器设置", "模组管理", "关于", "资源包管理", "原理图管理", "光影包管理" }[page]);
        workspace.setCenter(switch (page) {
            case 1 -> new DownloadsPage(stage, vm, this::showPage);
            case 2 -> new AccountsPage(stage, vm, this::showPage);
            case 3 -> new SettingsPage(stage, vm, this::showPage);
            case 4 -> new ContentPage(stage, vm, vm.modsModel(), this::showPage);
            case 5 -> new AboutPage(stage, vm, this::showPage);
            case 6 -> new ContentPage(stage, vm, vm.resourcePacksModel(), this::showPage);
            case 7 -> new ContentPage(stage, vm, vm.schematicsModel(), this::showPage);
            case 8 -> new ContentPage(stage, vm, vm.shaderPacksModel(), this::showPage);
            default -> new InstancesPage(stage, vm, this::showPage);
        });
    }

    private Node status() {
        Label identity = new Label();
        identity.textProperty()
            .bind(Bindings.createStringBinding(
                () -> vm.accountProperty().get() == null ? "未登录"
                    : (vm.accountProperty().get().isOffline() ? "离线: " : "用户: ") + vm.accountProperty().get().name(),
                vm.accountProperty()));
        identity.getStyleClass().add("status-cell");
        Label status = new Label();
        status.textProperty().bind(vm.statusProperty());
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);
        status.getStyleClass().add("status-cell");
        ProgressBar progress = new ProgressBar();
        progress.progressProperty().bind(vm.progressProperty());
        progress.setPrefWidth(150);
        progress.minHeightProperty().bind(status.heightProperty());
        progress.prefHeightProperty().bind(status.heightProperty());
        progress.maxHeightProperty().bind(status.heightProperty());
        Button cancel = button("取消", vm::cancel);
        cancel.disableProperty().bind(vm.busyProperty().not().or(vm.savingSettingsProperty()));
        HBox box = new HBox(5, identity, status, progress, cancel);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("status-bar");
        return box;
    }
}
