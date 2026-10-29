package com.fuzzlauncher.ui;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.ModpackArchive;
import java.nio.file.*;
import java.util.*;
import javafx.beans.property.*;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** Three export steps retain their controls and choices when moving back and forth. */
final class ModpackExportDialog extends Dialog<ButtonType> {
  ModpackExportDialog(Stage owner, LauncherViewModel vm, GameInstance instance) {
    RetroDialogs.decorate(this, owner, "导出整合包向导");
    getDialogPane().setId("export-modpack-dialog");
    setResizable(true);
    ComboBox<ModpackFormat> format = new RetroComboBox<>();
    format.setId("modpack-format");
    format.getItems().setAll(ModpackFormat.values());
    format.setValue(ModpackFormat.MCBBS);
    format.setMaxWidth(Double.MAX_VALUE);
    VBox types = new VBox(12, new Label("请选择整合包类型"));
    TextField name = field(instance.name(), "modpack-name"),
        version = field("1.0.0", "modpack-version"),
        author = field("", "modpack-author");
    TextArea description = new TextArea();
    description.setId("modpack-description");
    description.setWrapText(true);
    description.setPrefRowCount(3);
    TextField fileApi = field("", "modpack-file-api");
    fileApi.setPromptText("https://example.com/my-pack");
    fileApi.disableProperty().bind(format.valueProperty().isNotEqualTo(ModpackFormat.SERVER));
    GridPane metadata = new GridPane();
    metadata.setHgap(12);
    metadata.setVgap(12);
    String[] names = {"整合包类型", "名称", "版本", "作者", "说明", "服务器文件地址"};
    Node[] fields = {format, name, version, author, description, fileApi};
    for (int i = 0; i < fields.length; i++) {
      metadata.add(new Label(names[i]), 0, i);
      metadata.add(fields[i], 1, i);
      GridPane.setHgrow(fields[i], Priority.ALWAYS);
    }
    Label error = new Label();
    error.setWrapText(true);
    error.setStyle("-fx-text-fill: #a00000;");
    Set<String> excludedDefaults = Set.of("saves", "screenshots", "servers.dat", "options.txt");
    ModpackFileTree selector =
        new ModpackFileTree(
            instance.name(),
            path -> !excludedDefaults.contains(path.split("/")[0].toLowerCase(Locale.ROOT)));
    selector.setId("modpack-file-selector");
    TextField directory = field(System.getProperty("user.home"), "modpack-output-directory");
    TextField filename = field(instance.name(), "modpack-filename");
    Button browse = new Button("浏览");
    browse.setOnAction(
        event -> {
          Path initial;
          try {
            initial = Path.of(directory.getText());
          } catch (InvalidPathException ex) {
            initial = Path.of(System.getProperty("user.home"));
          }
          RetroPathDialog.directory(owner, "选择导出目录", initial)
              .ifPresent(p -> directory.setText(p.toString()));
        });
    HBox folder = new HBox(8, directory, browse);
    HBox.setHgrow(directory, Priority.ALWAYS);
    Label suffix = new Label();
    suffix
        .textProperty()
        .bind(
            javafx.beans.binding.Bindings.createStringBinding(
                () -> "文件名（自动添加 " + format.getValue().extension() + "）", format.valueProperty()));
    GridPane output = new GridPane();
    output.setHgap(12);
    output.setVgap(8);
    output.addRow(0, new Label("导出目录"), folder);
    output.addRow(1, suffix, filename);
    GridPane.setHgrow(folder, Priority.ALWAYS);
    GridPane.setHgrow(filename, Priority.ALWAYS);
    Label note = new Label("灰色勾号表示只包含部分子项。勾选的文件和文件夹会完整导出。");
    note.setWrapText(true);
    note.setMinHeight(Region.USE_PREF_SIZE);
    Label serverHint =
        new Label("服务器整合包导出后，将 ZIP 解压并把其中的 server-manifest.json 与 overrides/ 上传到文件地址。");
    serverHint.setWrapText(true);
    serverHint.setMinHeight(Region.USE_PREF_SIZE);
    serverHint.visibleProperty().bind(format.valueProperty().isEqualTo(ModpackFormat.SERVER));
    serverHint.managedProperty().bind(serverHint.visibleProperty());
    VBox metadataPage = new VBox(15, metadata, serverHint);
    VBox selection = new VBox(10, new Label("选中你想添加到整合包中的文件或文件夹"), selector, output, note);
    VBox.setVgrow(selector, Priority.ALWAYS);
    Label steps = new Label();
    steps.setId("modpack-export-step");
    steps.getStyleClass().add("section-title");
    BorderPane page = new BorderPane();
    page.setPadding(new Insets(8, 0, 8, 0));
    VBox content = new VBox(10, steps, page, error);
    content.setPadding(new Insets(12));
    content.setPrefSize(850, 660);
    VBox.setVgrow(page, Priority.ALWAYS);
    getDialogPane().setContent(content);
    ButtonType backType = new ButtonType("上一步", ButtonBar.ButtonData.BACK_PREVIOUS);
    ButtonType nextType = new ButtonType("下一步", ButtonBar.ButtonData.NEXT_FORWARD);
    getDialogPane().getButtonTypes().addAll(backType, nextType, ButtonType.CANCEL);
    Button back = (Button) getDialogPane().lookupButton(backType),
        next = (Button) getDialogPane().lookupButton(nextType);
    back.setId("modpack-export-back");
    next.setId("modpack-export-next");
    ((Button) getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
    IntegerProperty step = new SimpleIntegerProperty();
    BooleanProperty scanned = new SimpleBooleanProperty();
    StringProperty scanError = new SimpleStringProperty("");
    Runnable render =
        () -> {
          steps.setText(
              switch (step.get()) {
                case 0 -> "1 选择类型   →   2 填写信息   →   3 选择文件";
                case 1 -> "2 填写整合包信息";
                default -> "3 选择导出内容";
              });
          page.setCenter(step.get() == 0 ? types : step.get() == 1 ? metadataPage : selection);
          back.setDisable(step.get() == 0);
          next.setText(step.get() == 2 ? "导出" : "下一步");
          next.setDisable(step.get() == 2 && !scanned.get());
          error.setText(scanError.get());
        };
    for (ModpackFormat type : ModpackFormat.values()) {
      String detail =
          switch (type) {
            case MCBBS -> "MCBBS 整合包（推荐）\n可由 HMCL 等启动器导入";
            case MULTIMC -> "MultiMC\n可由 MultiMC、Prism Launcher 和 HMCL 导入";
            case SERVER -> "服务器自动更新整合包\n通过文件服务器发布客户端更新";
            case MODRINTH -> "Modrinth\n通用 .mrpack 格式，支持客户端文件与覆盖层";
          };
      Button choice = new Button(detail + "    →");
      choice.setId("modpack-type-" + type.name().toLowerCase(Locale.ROOT));
      choice.setMaxWidth(Double.MAX_VALUE);
      choice.setStyle("-fx-alignment: center-left; -fx-padding: 14;");
      choice.setOnAction(
          event -> {
            format.setValue(type);
            step.set(1);
            render.run();
          });
      types.getChildren().add(choice);
    }
    back.addEventFilter(
        ActionEvent.ACTION,
        event -> {
          event.consume();
          step.set(Math.max(0, step.get() - 1));
          render.run();
        });
    next.addEventFilter(
        ActionEvent.ACTION,
        event -> {
          try {
            if (step.get() == 0) {
              event.consume();
              step.set(1);
              render.run();
              return;
            }
            if (name.getText().isBlank() || version.getText().isBlank())
              throw new IllegalArgumentException("请填写整合包名称和版本");
            if (format.getValue() == ModpackFormat.SERVER)
              ModpackArchive.baseUrl(fileApi.getText());
            if (step.get() == 1) {
              event.consume();
              step.set(2);
              render.run();
              return;
            }
            if (!scanned.get()) throw new IllegalArgumentException("文件列表尚未读取完成");
            String outputName = filename.getText().strip();
            ModpackArchive.validatePath(outputName);
            if (outputName.contains("/")) throw new IllegalArgumentException("文件名不能包含目录");
            Path destination =
                Path.of(directory.getText()).resolve(outputName + format.getValue().extension());
            boolean replace = Files.exists(destination);
            if (replace
                && RetroDialogs.alert(
                            owner,
                            Alert.AlertType.CONFIRMATION,
                            "覆盖导出文件",
                            "文件已存在，是否替换？\n" + destination)
                        .showAndWait()
                        .orElse(ButtonType.CANCEL)
                    != ButtonType.OK) {
              event.consume();
              return;
            }
            vm.exportModpack(
                instance,
                format.getValue(),
                name.getText().strip(),
                version.getText().strip(),
                author.getText().strip(),
                description.getText(),
                fileApi.getText().strip(),
                selector.selectedPaths(),
                destination,
                replace);
          } catch (Exception ex) {
            error.setText(ex.getMessage());
            event.consume();
          }
        });
    Path sourcePath =
        new IsolationPolicy()
            .gameDirectory(
                Path.of(vm.settingsProperty().get().gameRoot()),
                instance,
                vm.settingsProperty().get().isolation());
    try {
      Path state = sourcePath.resolve(ModpackArchive.STATE);
      if (Files.isRegularFile(state)) {
        var manifest =
            com.fuzzlauncher.infrastructure.JsonFiles.read(state).getAsJsonObject("manifest");
        version.setText(
            com.fuzzlauncher.infrastructure.JsonFiles.string(
                manifest,
                "versionId",
                com.fuzzlauncher.infrastructure.JsonFiles.string(manifest, "version", "1.0.0")));
        author.setText(com.fuzzlauncher.infrastructure.JsonFiles.string(manifest, "author", ""));
        description.setText(
            com.fuzzlauncher.infrastructure.JsonFiles.string(
                manifest,
                "summary",
                com.fuzzlauncher.infrastructure.JsonFiles.string(manifest, "description", "")));
        fileApi.setText(com.fuzzlauncher.infrastructure.JsonFiles.string(manifest, "fileApi", ""));
      }
    } catch (java.io.IOException | RuntimeException ignored) {
      /* Editable defaults remain available. */
    }
    Task<List<ModpackArchive.ContentEntry>> scan =
        new Task<>() {
          @Override
          protected List<ModpackArchive.ContentEntry> call() throws Exception {
            return ModpackArchive.scanExport(
                sourcePath,
                instance,
                new TaskContext() {
                  public void report(String message, double progress) {}

                  public boolean cancelled() {
                    return isCancelled();
                  }
                });
          }
        };
    scan.setOnSucceeded(
        event -> {
          try {
            selector.setEntries(scan.getValue());
            scanned.set(true);
            next.setDisable(false);
          } catch (IllegalArgumentException ex) {
            scanError.set("无法展示文件列表：" + ex.getMessage());
            error.setText(scanError.get());
            scanned.set(false);
          }
        });
    scan.setOnFailed(
        event -> {
          scanError.set("无法读取游戏目录：" + scan.getException().getMessage());
          error.setText(scanError.get());
          scanned.set(false);
        });
    setOnHidden(event -> scan.cancel(true));
    Thread.ofVirtual().name("modpack-export-scan").start(scan);
    render.run();
  }

  private static TextField field(String value, String id) {
    TextField result = new TextField(value);
    result.setId(id);
    return result;
  }
}
