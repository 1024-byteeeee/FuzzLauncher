package com.fuzzlauncher.ui;

import com.fuzzlauncher.infrastructure.ModpackArchive.ContentEntry;
import java.util.*;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

/** Checkbox state is independent of row highlighting and survives collapsing or locating files. */
final class ModpackFileTree extends VBox {
  private final TreeView<ContentEntry> tree = new TreeView<>();
  private final CheckBoxTreeItem<ContentEntry> root;
  private final Label summary = new Label("正在读取文件…");
  private final Predicate<String> recommended;
  private final String rootName;
  private final Map<String, CheckBoxTreeItem<ContentEntry>> nodes = new LinkedHashMap<>();
  private boolean summaryPending;
  private String previousQuery = "";
  private int foundIndex = -1;

  ModpackFileTree(String rootName, Predicate<String> recommended) {
    super(8);
    this.rootName = rootName;
    this.recommended = recommended;
    root = new CheckBoxTreeItem<>(new ContentEntry("", true, 0));
    root.setExpanded(true);
    tree.setRoot(root);
    summary.setId("modpack-selection-summary");
    tree.setId("modpack-export-files");
    tree.setPrefHeight(330);
    tree.setMinHeight(160);
    tree.setCellFactory(
        view ->
            new TreeCell<>() {
              private final CheckBox checkbox = new CheckBox();
              private CheckBoxTreeItem<ContentEntry> current;
              private final javafx.beans.value.ChangeListener<Boolean> changed =
                  (o, before, after) -> sync();

              {
                checkbox.setFocusTraversable(false);
                checkbox.setOnAction(
                    event -> {
                      if (current != null)
                        selectAll(current, current.isIndeterminate() || !current.isSelected());
                      sync();
                      event.consume();
                    });
                setAccessibleRole(javafx.scene.AccessibleRole.CHECK_BOX_TREE_ITEM);
              }

              private void sync() {
                if (current != null) {
                  checkbox.setSelected(current.isSelected());
                  checkbox.setIndeterminate(current.isIndeterminate());
                }
              }

              @Override
              public void updateItem(ContentEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (current != null) {
                  current.selectedProperty().removeListener(changed);
                  current.indeterminateProperty().removeListener(changed);
                }
                current =
                    !empty && getTreeItem() instanceof CheckBoxTreeItem<ContentEntry> value
                        ? value
                        : null;
                if (current != null) {
                  current.selectedProperty().addListener(changed);
                  current.indeterminateProperty().addListener(changed);
                }
                setText(empty || item == null ? null : label(item));
                setGraphic(current == null ? null : checkbox);
                sync();
              }
            });
    tree.setOnKeyPressed(
        event -> {
          if (event.getCode() == KeyCode.SPACE) {
            var selected = tree.getSelectionModel().getSelectedItem();
            if (selected instanceof CheckBoxTreeItem<ContentEntry> item) {
              selectAll(item, !item.isSelected() || item.isIndeterminate());
              event.consume();
            }
          }
        });
    Button all = button("全选", "modpack-select-all", () -> selectAll(root, true));
    Button none = button("全不选", "modpack-select-none", () -> selectAll(root, false));
    Button defaults = button("恢复推荐", "modpack-select-recommended", this::restoreRecommended);
    Button expand = button("展开全部", "modpack-expand-all", () -> expand(root, true));
    Button collapse =
        button(
            "收起全部",
            "modpack-collapse-all",
            () -> {
              expand(root, false);
              root.setExpanded(true);
            });
    TextField locate = new TextField();
    locate.setId("modpack-file-search");
    locate.setPromptText("搜索文件名或路径，按 Enter 定位下一个");
    locate.setOnAction(event -> locate(locate.getText()));
    getChildren().addAll(new HBox(6, all, none, defaults, expand, collapse), locate, tree, summary);
    VBox.setVgrow(tree, Priority.ALWAYS);
    root.addEventHandler(
        CheckBoxTreeItem.checkBoxSelectionChangedEvent(), event -> scheduleSummary());
  }

  private static Button button(String text, String id, Runnable action) {
    Button result = new Button(text);
    result.setId(id);
    result.setOnAction(event -> action.run());
    return result;
  }

  void setEntries(List<ContentEntry> entries) {
    root.getChildren().clear();
    nodes.clear();
    nodes.put("", root);
    for (ContentEntry entry :
        entries.stream().sorted(Comparator.comparing(ContentEntry::path)).toList()) {
      String[] segments = entry.path().split("/");
      String path = "";
      for (int i = 0; i < segments.length; i++) {
        String parent = path;
        path = path.isEmpty() ? segments[i] : path + "/" + segments[i];
        if (!nodes.containsKey(path)) {
          if (nodes.size() > 100_000) throw new IllegalArgumentException("文件选择树超过 100000 项");
          ContentEntry value = i == segments.length - 1 ? entry : new ContentEntry(path, true, 0);
          CheckBoxTreeItem<ContentEntry> item = new CheckBoxTreeItem<>(value);
          nodes.get(parent).getChildren().add(item);
          nodes.put(path, item);
        }
      }
    }
    for (var item : nodes.values()) {
      if (!item.getValue().directory() && !item.getChildren().isEmpty())
        throw new IllegalArgumentException("文件和目录路径冲突：" + item.getValue().path());
      item.getChildren()
          .sort(
              Comparator.<TreeItem<ContentEntry>, Boolean>comparing(
                      child -> !child.getValue().directory())
                  .thenComparing(child -> child.getValue().path(), String.CASE_INSENSITIVE_ORDER));
    }
    restoreRecommended();
    updateSummary();
  }

  void restoreRecommended() {
    List<CheckBoxTreeItem<ContentEntry>> items = new ArrayList<>(nodes.values());
    items.forEach(item -> item.setIndependent(true));
    try {
      for (int i = items.size() - 1; i >= 0; i--) {
        var item = items.get(i);
        if (item.getChildren().isEmpty()) {
          item.setSelected(item != root && recommended.test(item.getValue().path()));
          item.setIndeterminate(false);
        } else recompute(item);
      }
    } finally {
      items.forEach(item -> item.setIndependent(false));
    }
    scheduleSummary();
  }

  Set<String> selectedPaths() {
    Set<String> result = new LinkedHashSet<>();
    for (var item : nodes.values())
      if (item != root && item.getChildren().isEmpty() && item.isSelected())
        result.add(item.getValue().path());
    return Set.copyOf(result);
  }

  private static void selectAll(CheckBoxTreeItem<ContentEntry> item, boolean value) {
    List<CheckBoxTreeItem<ContentEntry>> items = new ArrayList<>();
    ArrayDeque<CheckBoxTreeItem<ContentEntry>> queue = new ArrayDeque<>();
    queue.add(item);
    while (!queue.isEmpty()) {
      var current = queue.removeFirst();
      items.add(current);
      for (var child : current.getChildren()) queue.add((CheckBoxTreeItem<ContentEntry>) child);
    }
    items.forEach(current -> current.setIndependent(true));
    try {
      for (var current : items) {
        current.setSelected(value);
        current.setIndeterminate(false);
      }
    } finally {
      items.forEach(current -> current.setIndependent(false));
    }
    for (var parent = item.getParent();
        parent instanceof CheckBoxTreeItem<ContentEntry> current;
        parent = parent.getParent()) {
      current.setIndependent(true);
      try {
        recompute(current);
      } finally {
        current.setIndependent(false);
      }
    }
  }

  private static void recompute(CheckBoxTreeItem<ContentEntry> item) {
    boolean all = true, any = false;
    for (var child : item.getChildren()) {
      var checked = (CheckBoxTreeItem<ContentEntry>) child;
      all &= checked.isSelected() && !checked.isIndeterminate();
      any |= checked.isSelected() || checked.isIndeterminate();
    }
    item.setSelected(all);
    item.setIndeterminate(any && !all);
  }

  private static void expand(TreeItem<ContentEntry> item, boolean expanded) {
    item.setExpanded(expanded);
    for (var child : item.getChildren()) expand(child, expanded);
  }

  private void locate(String text) {
    String query = text.strip().toLowerCase(Locale.ROOT);
    List<CheckBoxTreeItem<ContentEntry>> matches =
        nodes.entrySet().stream()
            .filter(
                e -> !e.getKey().isEmpty() && e.getKey().toLowerCase(Locale.ROOT).contains(query))
            .map(Map.Entry::getValue)
            .toList();
    if (query.isEmpty() || matches.isEmpty()) {
      summary.setText("没有找到匹配的文件或文件夹");
      return;
    }
    foundIndex = query.equals(previousQuery) ? (foundIndex + 1) % matches.size() : 0;
    previousQuery = query;
    var item = matches.get(foundIndex);
    for (TreeItem<ContentEntry> parent = item.getParent();
        parent != null;
        parent = parent.getParent()) parent.setExpanded(true);
    tree.getSelectionModel().select(item);
    tree.scrollTo(tree.getRow(item));
    updateSummary();
  }

  private void scheduleSummary() {
    if (summaryPending) return;
    summaryPending = true;
    Platform.runLater(
        () -> {
          summaryPending = false;
          updateSummary();
        });
  }

  private void updateSummary() {
    long count = 0, bytes = 0;
    for (var item : nodes.values()) {
      if (item != root && !item.getValue().directory() && item.isSelected()) {
        count++;
        bytes += item.getValue().size();
      }
    }
    summary.setText("已选择 " + count + " 个文件 · " + size(bytes));
  }

  private String label(ContentEntry entry) {
    if (entry.path().isEmpty()) return rootName;
    String name = entry.path().substring(entry.path().lastIndexOf('/') + 1);
    String hint =
        entry.path().contains("/")
            ? ""
            : switch (name.toLowerCase(Locale.ROOT)) {
              case "mods" -> " 模组";
              case "config" -> " 模组配置文件";
              case "resourcepacks" -> " 资源包（纹理包）";
              case "shaderpacks" -> " 光影包";
              case "saves" -> " 游戏世界";
              case "options.txt" -> " 游戏设置";
              case "servers.dat" -> " 多人游戏服务器列表";
              case "screenshots" -> " 游戏截图";
              default -> "";
            };
    return name + hint + (entry.directory() ? "" : "  (" + size(entry.size()) + ")");
  }

  private static String size(long bytes) {
    if (bytes >= 1073741824L) return String.format(Locale.ROOT, "%.2f GiB", bytes / 1073741824.0);
    if (bytes >= 1048576) return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    if (bytes >= 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
    return bytes + " B";
  }
}
