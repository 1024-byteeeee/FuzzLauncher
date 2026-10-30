package com.fuzzlauncher.ui;

import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import javafx.stage.*;

/** Shared window chrome for every launcher-owned dialog. */
public final class RetroDialogs {
    private RetroDialogs() {
    }

    public static void decorate(Dialog<?> dialog, Window owner, String title) {
        if (owner != null && owner.getScene() != null && dialog.getOwner() == null) {
            dialog.initOwner(owner);
        }
        dialog.initStyle(StageStyle.UNDECORATED);
        dialog.setTitle(title);
        DialogPane pane = dialog.getDialogPane();
        pane.setHeaderText(null);
        pane.setGraphic(null);
        pane.getStyleClass().addAll("window", "retro-dialog");
        pane.getStylesheets().add(RetroDialogs.class.getResource("/com/fuzzlauncher/retro.css").toExternalForm());
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        SVGPath glyph = new SVGPath();
        glyph.setContent("M0 0 H3 L6 3 L9 0 H12 L8 5 L12 10 H9 L6 7 L3 10 H0 L4 5 Z");
        glyph.setFillRule(FillRule.EVEN_ODD);
        glyph.setSmooth(false);
        glyph.setMouseTransparent(true);
        StackPane graphic = new StackPane(glyph);
        graphic.setMinSize(14, 14);
        graphic.setPrefSize(14, 14);
        graphic.setMaxSize(14, 14);
        Button close = new Button();
        close.setGraphic(graphic);
        close.setFocusTraversable(false);
        close.setAccessibleText("关闭");
        close.getStyleClass().add("window-control");
        close.setOnAction(event -> dialog.close());
        HBox bar = new HBox(9, RetroPage.icon("grass", 28), RetroPage.label(title, "title"), space, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("title-bar");
        double[] drag = new double[2];
        bar.setOnMousePressed(event -> {
            Window window = pane.getScene().getWindow();
            drag[0] = event.getScreenX() - window.getX();
            drag[1] = event.getScreenY() - window.getY();
        });
        bar.setOnMouseDragged(event -> {
            Window window = pane.getScene().getWindow();
            window.setX(event.getScreenX() - drag[0]);
            window.setY(event.getScreenY() - drag[1]);
        });
        pane.setHeader(bar);
        dialog.setOnShown(event -> ((Stage) pane.getScene().getWindow()).getIcons().setAll(RetroPage.image("launcher")));
    }

    public static Alert alert(Window owner, Alert.AlertType type, String title, String text) {
        Alert dialog = new Alert(type);
        dialog.setContentText(text);
        dialog.getDialogPane().setMinWidth(550);
        dialog.getDialogPane().getButtonTypes()
            .setAll(type == Alert.AlertType.CONFIRMATION ? new ButtonType[] { ButtonType.OK, ButtonType.CANCEL }
                : new ButtonType[] { ButtonType.OK });
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("确定");
        if (type == Alert.AlertType.CONFIRMATION) {
            ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
        }
        decorate(dialog, owner, title);
        return dialog;
    }
}
