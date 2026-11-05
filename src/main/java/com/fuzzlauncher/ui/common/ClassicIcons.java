package com.fuzzlauncher.ui.common;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;

final class ClassicIcons {
    private ClassicIcons() {
    }

    static Image grassBlock() {
        Canvas canvas = new Canvas(32, 32);
        var paint = canvas.getGraphicsContext2D();
        fillPath(paint, "#262318", "M3 8 L16 1 L29 8 V24 L16 31 L3 24 Z");
        fillPath(paint, "#9e6b42", "M4 9 L16 15 V30 L4 23 Z");
        fillPath(paint, "#674329", "M16 15 L28 9 V23 L16 30 Z");
        fillPath(paint, "#558635", "M4 9 L16 15 V19 L12 17 V19 L8 17 V15 L4 13 Z");
        fillPath(paint, "#355d2a", "M16 15 L28 9 V13 L24 15 V17 L20 19 V17 L16 20 Z");
        fillPath(paint, "#81ac44", "M4 8 L16 2 L28 8 L16 14 Z");
        fillPath(paint, "#b9d472", "M4 8 L16 2 L19 4 L16 4 L7 9 Z");
        fillPath(paint, "#639037", "M10 8 L14 6 L17 7 L13 9 Z M17 11 L21 9 L24 10 L20 12 Z");
        fillPath(paint, "#c38f58", "M5 16 L8 18 V20 L5 18 Z M9 22 L12 24 V26 L9 24 Z M12 19 L15 21 V23 L12 21 Z");
        fillPath(paint, "#95643f", "M18 22 L21 20 V22 L18 24 Z M23 17 L26 15 V17 L23 19 Z M23 23 L26 21 V23 L23 25 Z");
        return snapshot(canvas);
    }

    static Image player() {
        Canvas canvas = new Canvas(32, 32);
        var paint = canvas.getGraphicsContext2D();
        fillPath(paint, "#262125", "M3 3 H29 V29 H3 Z");
        fillPath(paint, "#d79d72", "M4 4 H28 V28 H4 Z");
        fillPath(paint, "#4f3629", "M4 4 H28 V16 H25 V12 H21 V10 H12 V12 H8 V16 H4 Z");
        fillPath(paint, "#79503a", "M4 4 H28 V6 H7 V12 H4 Z M9 8 H17 V10 H9 Z");
        fillPath(paint, "#f1bd90", "M8 15 H12 V13 H21 V15 H24 V23 H26 V26 H6 V23 H8 Z");
        fillPath(paint, "#fff5e4", "M6 17 H13 V20 H6 Z M19 17 H26 V20 H19 Z");
        fillPath(paint, "#385182", "M10 17 H13 V20 H10 Z M19 17 H22 V20 H19 Z");
        fillPath(paint, "#b87954", "M14 19 H18 V22 H14 Z");
        fillPath(paint, "#86553e", "M12 23 H20 V25 H12 Z");
        fillPath(paint, "#aa7153", "M4 26 H28 V28 H4 Z");
        return snapshot(canvas);
    }

    static Image globe() {
        Canvas canvas = new Canvas(32, 32);
        var paint = canvas.getGraphicsContext2D();
        paint.setFill(Color.web("#152b48"));
        paint.fillOval(2, 2, 28, 28);
        paint.setFill(Color.web("#2f6cb4"));
        paint.fillOval(3, 3, 26, 26);
        paint.setFill(Color.web("#75b0dc"));
        paint.fillArc(4, 4, 24, 24, 108, 101, javafx.scene.shape.ArcType.ROUND);
        paint.setFill(Color.web("#2f6cb4"));
        paint.fillOval(6, 6, 21, 21);
        fillPath(paint, "#8eb756", "M8 8 H13 V6 H17 V9 H15 V12 H12 V15 H9 V12 H6 Z");
        fillPath(paint, "#70963f", "M11 15 H14 V17 H18 V20 H16 V24 H14 V27 H12 V22 H10 V18 Z");
        fillPath(paint, "#91b95c", "M20 7 H24 L27 10 V14 H24 V17 H20 V14 H18 V11 H20 Z");
        fillPath(paint, "#629443", "M22 21 H25 L27 23 L25 26 H21 V23 Z");
        return snapshot(canvas);
    }

    static Image folder() {
        Canvas canvas = new Canvas(32, 32);
        var paint = canvas.getGraphicsContext2D();
        paint.setFill(Color.web("#382d19"));
        paint.fillPolygon(new double[] {2, 12, 15, 29, 29, 2}, new double[] {7, 7, 10, 10, 26, 26}, 6);
        paint.setFill(Color.web("#ce9433"));
        paint.fillPolygon(new double[] {3, 12, 15, 28, 28, 3}, new double[] {8, 8, 11, 11, 25, 25}, 6);
        paint.setFill(Color.web("#fff4bd"));
        paint.fillRect(3, 8, 9, 1);
        paint.fillRect(3, 9, 1, 15);
        paint.setFill(Color.web("#ffe189"));
        paint.fillRect(4, 13, 23, 11);
        paint.setFill(Color.web("#fff4bd"));
        paint.fillRect(4, 13, 23, 1);
        paint.setFill(Color.web("#ac7226"));
        paint.fillRect(4, 24, 23, 1);
        paint.fillRect(27, 13, 1, 12);
        return snapshot(canvas);
    }

    static Image wrench() {
        Canvas canvas = new Canvas(32, 32);
        var paint = canvas.getGraphicsContext2D();
        paint.beginPath();
        paint.appendSVGPath("M4 24 L14 14 L14 9 L17 5 L21 3 H25 L20 8 L21 12 L25 13 L30 8 V14 L27 18 L23 20 H19 L9 30 H5 L2 27 Z");
        paint.setFill(Color.web("#b7b7b7"));
        paint.fill();
        paint.setStroke(Color.web("#202020"));
        paint.setLineWidth(2);
        paint.stroke();
        paint.setStroke(Color.web("#f4f4f4"));
        paint.setLineWidth(1);
        paint.strokePolyline(new double[] {4.5, 15.5, 15.5, 18.5, 22.5}, new double[] {26.5, 15.5, 9.5, 6.5, 4.5}, 5);
        paint.setStroke(Color.web("#666666"));
        paint.strokePolyline(new double[] {7.5, 18.5, 23.5, 27.5}, new double[] {28.5, 18.5, 18.5, 15.5}, 4);
        return snapshot(canvas);
    }

    private static Image snapshot(Canvas canvas) {
        var options = new SnapshotParameters();
        options.setFill(Color.TRANSPARENT);
        return canvas.snapshot(options, null);
    }

    private static void fillPath(GraphicsContext paint, String color, String path) {
        paint.beginPath();
        paint.appendSVGPath(path);
        paint.setFill(Color.web(color));
        paint.fill();
    }
}
