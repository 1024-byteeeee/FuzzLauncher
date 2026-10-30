package com.fuzzlauncher.ui;

import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.input.MouseEvent;

/** Keeps the popup's last hovered row highlighted without committing its value. */
public final class RetroComboBox<T> extends ComboBox<T> {
    private static final PseudoClass POINTER_HIGHLIGHT = PseudoClass.getPseudoClass("pointer-highlight");
    private ListView<?> popup;

    public RetroComboBox() {
        skinProperty().addListener((observable, oldSkin, skin) -> {
            if (skin instanceof ComboBoxListViewSkin<?> listSkin && listSkin.getPopupContent() instanceof ListView<?> list) {
                popup = list;
                list.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
                    if (!(event.getTarget() instanceof Node target)) {
                        return;
                    }
                    for (Node node = target; node != null && node != list; node = node.getParent()) {
                        if (node instanceof ListCell<?> cell && cell.getListView() == list && !cell.isEmpty()) {
                            list.getFocusModel().focus(cell.getIndex());
                            list.pseudoClassStateChanged(POINTER_HIGHLIGHT, true);
                            break;
                        }
                    }
                });
            }
        });
        showingProperty().addListener((observable, wasShowing, showing) -> {
            if (!showing && popup != null) {
                popup.pseudoClassStateChanged(POINTER_HIGHLIGHT, false);
            }
        });
    }

    public RetroComboBox(ObservableList<T> items) {
        this();
        setItems(items);
    }
}
