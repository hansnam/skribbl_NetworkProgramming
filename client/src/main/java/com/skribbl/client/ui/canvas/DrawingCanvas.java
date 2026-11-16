package com.skribbl.client.ui.canvas;

import com.skribbl.client.controller.ClientController;
import com.skribbl.common.dto.DrawStrokeDto;
import com.skribbl.common.model.StrokeData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.List;

public class DrawingCanvas extends BorderPane {
    public static final double LOGICAL_WIDTH = 960.0;
    public static final double LOGICAL_HEIGHT = 600.0;

    private static final String[] PALETTE = {
            "#000000", "#7F8C8D", "#FFFFFF", "#E74C3C",
            "#E67E22", "#F1C40F", "#2ECC71", "#1ABC9C",
            "#3498DB", "#9B59B6", "#E84393", "#8D6E63"
    };

    private final Canvas canvas;
    private final GraphicsContext gc;
    private final HBox toolbar;
    private final StrokeThrottler throttler = new StrokeThrottler(2.0);
    private final ClientController controller;
    private Circle sizePreview;

    private String currentColor = "#000000";
    private double currentThickness = 4.0;
    private boolean eraserMode = false;
    private boolean drawingEnabled = false;
    private double prevX;
    private double prevY;

    public DrawingCanvas(ClientController controller) {
        this.controller = controller;
        this.canvas = new Canvas(LOGICAL_WIDTH, LOGICAL_HEIGHT);
        this.gc = canvas.getGraphicsContext2D();
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);

        StackPane canvasHolder = new StackPane(canvas);
        canvasHolder.setStyle("-fx-background-color: #ECF0F1;");
        canvasHolder.setMinSize(320, 200);

        canvasHolder.widthProperty().addListener((obs, o, n) -> fitCanvas(canvasHolder));
        canvasHolder.heightProperty().addListener((obs, o, n) -> fitCanvas(canvasHolder));

        this.toolbar = buildToolbar();
        setCenter(canvasHolder);
        setBottom(toolbar);

        clearCanvasLocal();
        setupMouseEvents();
        setDrawingEnabled(false);
    }

    private void fitCanvas(StackPane holder) {
        double availW = Math.max(100, holder.getWidth() - 16);
        double availH = Math.max(100, holder.getHeight() - 16);
        double scale = Math.min(availW / LOGICAL_WIDTH, availH / LOGICAL_HEIGHT);
        canvas.setScaleX(scale);
        canvas.setScaleY(scale);
    }

    private HBox buildToolbar() {
        HBox bar = new HBox(8);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.setAlignment(Pos.CENTER);
        bar.setStyle("-fx-background-color: #DFE6E9; -fx-border-color: #B2BEC3; -fx-border-width: 1 0 0 0;");

        sizePreview = new Circle(2.5, Color.BLACK);
        StackPane previewBox = new StackPane(sizePreview);
        previewBox.setPrefSize(28, 28);
        previewBox.setStyle("-fx-background-color: white; -fx-border-color: #95A5A6; -fx-border-radius: 14; -fx-background-radius: 14;");
        bar.getChildren().add(previewBox);

        for (String hex : PALETTE) {
            Button swatch = new Button();
            swatch.setPrefSize(22, 22);
            swatch.setMinSize(22, 22);
            swatch.setStyle("-fx-background-color: " + hex + "; -fx-border-color: #2D3436; -fx-border-width: 1; -fx-background-radius: 3;");
            swatch.setOnAction(e -> {
                eraserMode = false;
                currentColor = hex;
                sizePreview.setFill(Color.web(hex));
            });
            bar.getChildren().add(swatch);
        }

        Label sizeLbl = new Label("Cỡ:");
        Slider sizeSlider = new Slider(2, 24, 4);
        sizeSlider.setPrefWidth(85);
        sizeSlider.valueProperty().addListener((obs, o, n) -> {
            currentThickness = n.doubleValue();
            sizePreview.setRadius(Math.max(1.5, currentThickness / 2.0));
        });

        ToggleButton eraserBtn = new ToggleButton("Tẩy");
        eraserBtn.setOnAction(e -> {
            eraserMode = eraserBtn.isSelected();
            sizePreview.setFill(eraserMode ? Color.LIGHTGRAY : Color.web(currentColor));
        });

        Button fillBgBtn = new Button("Tô nền");
        fillBgBtn.setStyle("-fx-background-color: #3498DB; -fx-text-fill: white;");
        fillBgBtn.setOnAction(e -> fillBackgroundWithCurrentColor());

        Button clearBtn = new Button("Xóa hết");
        clearBtn.setStyle("-fx-background-color: #E74C3C; -fx-text-fill: white; -fx-font-weight: bold;");
        clearBtn.setOnAction(e -> {
            clearCanvasLocal();
            if (controller != null) {
                controller.sendClearCanvas();
            }
        });

        bar.getChildren().addAll(sizeLbl, sizeSlider, eraserBtn, fillBgBtn, clearBtn);
        return bar;
    }

    private void fillBackgroundWithCurrentColor() {
        if (!drawingEnabled) return;
        String bgColor = eraserMode ? "#FFFFFF" : currentColor;
        double step = 40.0;
        for (double y = 0; y <= LOGICAL_HEIGHT; y += step) {
            emitSegment(0, y, LOGICAL_WIDTH, y, bgColor, step + 4, false);
        }
    }

    private void setupMouseEvents() {
        canvas.setOnMousePressed(e -> {
            if (!drawingEnabled) return;
            throttler.reset();
            prevX = clampX(e.getX());
            prevY = clampY(e.getY());
            throttler.shouldSend(prevX, prevY);
            emitSegment(prevX, prevY, prevX, prevY);
        });

        canvas.setOnMouseDragged(e -> {
            if (!drawingEnabled) return;
            double x = clampX(e.getX());
            double y = clampY(e.getY());
            if (throttler.shouldSend(x, y)) {
                emitSegment(prevX, prevY, x, y);
                prevX = x;
                prevY = y;
            }
        });

        canvas.setOnMouseReleased(e -> throttler.reset());
    }

    private void emitSegment(double x1, double y1, double x2, double y2) {
        String activeColor = eraserMode ? "#FFFFFF" : currentColor;
        emitSegment(x1, y1, x2, y2, activeColor, currentThickness, eraserMode);
    }

    private void emitSegment(double x1, double y1, double x2, double y2, String color, double thickness, boolean eraser) {
        int intThickness = (int) Math.max(1, Math.round(thickness));
        DrawStrokeDto dto = new DrawStrokeDto(x1, y1, x2, y2, color, intThickness, eraser);
        drawSegment(dto);
        if (controller != null) {
            controller.sendDrawStroke(dto);
        }
    }

    public void renderRemoteStroke(DrawStrokeDto dto) {
        if (dto != null) {
            drawSegment(dto);
        }
    }

    public void renderSyncHistory(List<StrokeData> strokes) {
        clearCanvasLocal();
        if (strokes == null) return;
        for (StrokeData s : strokes) {
            drawSegment(new DrawStrokeDto(s.x1(), s.y1(), s.x2(), s.y2(), s.color(), s.thickness(), s.eraser()));
        }
    }

    private void drawSegment(DrawStrokeDto dto) {
        gc.setStroke(dto.eraser() ? Color.WHITE : Color.web(dto.color()));
        gc.setLineWidth(dto.thickness());
        gc.strokeLine(dto.x1(), dto.y1(), dto.x2(), dto.y2());
    }

    public void clearCanvasLocal() {
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT);
    }

    public void setDrawingEnabled(boolean enabled) {
        this.drawingEnabled = enabled;
        toolbar.setVisible(enabled);
        toolbar.setManaged(enabled);
    }

    private double clampX(double x) {
        return Math.max(0, Math.min(LOGICAL_WIDTH, x));
    }

    private double clampY(double y) {
        return Math.max(0, Math.min(LOGICAL_HEIGHT, y));
    }
}