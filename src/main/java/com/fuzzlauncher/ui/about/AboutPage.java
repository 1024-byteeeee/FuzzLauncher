package com.fuzzlauncher.ui.about;

import com.fuzzlauncher.application.model.LauncherInfo;
import com.fuzzlauncher.ui.common.RetroPage;
import com.fuzzlauncher.ui.viewmodel.LauncherViewModel;

import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Product information and user-invoked links. Opening the page performs no network requests. */
public final class AboutPage extends RetroPage {
    private final LauncherViewModel vm;

    public AboutPage(Stage stage, LauncherViewModel vm, Consumer<Integer> navigate) {
        super(stage, navigate);
        this.vm = vm;
        AboutInfo info = AboutInfo.load();
        Label title = label(info.name(), "section-title");
        Label version = new Label("版本：" + LauncherInfo.VERSION);
        version.setId("about-version");
        Label description = new Label(info.description());
        description.setWrapText(true);
        VBox identity = new VBox(8, title, version, description, new Label("作者：" + info.author()));
        identity.setMinWidth(0);
        HBox.setHgrow(identity, Priority.ALWAYS);
        HBox header = new HBox(18, icon("launcher", 64), identity);
        header.setAlignment(Pos.CENTER_LEFT);
        Button repository = linkButton("项目仓库", info.repository());
        repository.setId("about-repository");
        Button feedback = linkButton("反馈问题", info.issues());
        feedback.setId("about-feedback");
        VBox product = section(header, new HBox(8, repository, feedback));

        Map<String, String> runtime = new LinkedHashMap<>();
        runtime.put("操作系统", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        runtime.put("系统架构", System.getProperty("os.arch"));
        runtime.put("Java", System.getProperty("java.runtime.version", System.getProperty("java.version")));
        runtime.put("Java 供应商", System.getProperty("java.vendor"));
        runtime.put("JavaFX", System.getProperty("javafx.runtime.version", System.getProperty("javafx.version", "未知")));
        GridPane runtimeInfo = new GridPane();
        runtimeInfo.setHgap(18);
        runtimeInfo.setVgap(8);
        runtimeInfo.setId("about-runtime");
        ColumnConstraints keys = new ColumnConstraints();
        keys.setMinWidth(120);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        values.setMinWidth(0);
        runtimeInfo.getColumnConstraints().addAll(keys, values);
        int row = 0;
        for (var entry : runtime.entrySet()) {
            Label value = new Label(entry.getValue());
            value.setWrapText(true);
            value.setMinWidth(0);
            value.setMaxWidth(Double.MAX_VALUE);
            runtimeInfo.addRow(row++, new Label(entry.getKey() + "："), value);
        }
        Label copied = new Label();
        Button copy = button("复制运行信息", () -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(info.name() + " " + LauncherInfo.VERSION + "\n"
                + runtime.entrySet().stream().map(entry -> entry.getKey() + "：" + entry.getValue()).collect(Collectors.joining("\n")));
            try {
                if (!Clipboard.getSystemClipboard().setContent(content)) {
                    throw new IllegalStateException("剪贴板当前不可用，请重试");
                }
                copied.setText("已复制");
            } catch (IllegalStateException ex) {
                message(Alert.AlertType.ERROR, "无法复制运行信息", ex.getMessage());
            }
        });
        copy.setId("copy-about-runtime");
        HBox copyBar = new HBox(12, copy, copied);
        copyBar.setAlignment(Pos.CENTER_LEFT);
        VBox environment = section(label("运行环境", "section-title"), runtimeInfo, copyBar);

        VBox body = new VBox(14, product, environment);
        body.setMinWidth(0);
        body.setPadding(new Insets(0, 5, 0, 0));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setId("about-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox content = new VBox(12, label("关于", "section-title"), scroll);
        content.getStyleClass().add("content");
        setCenter(content);
    }

    private static VBox section(javafx.scene.Node... content) {
        VBox section = new VBox(10, content);
        section.setMinWidth(0);
        section.getStyleClass().add("details-box");
        return section;
    }

    private Button linkButton(String text, String address) {
        Button button = button(text, () -> browse(address));
        button.setUserData(URI.create(address));
        return button;
    }

    private void browse(String address) {
        try {
            Desktop.getDesktop().browse(URI.create(address));
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            message(Alert.AlertType.ERROR, "无法打开网页", ex.getMessage());
        }
    }
}
