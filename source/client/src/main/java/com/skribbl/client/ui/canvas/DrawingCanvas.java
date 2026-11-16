package com.skribbl.client.ui.canvas;

import com.skribbl.client.controller.ClientController;
import com.skribbl.client.net.StrokeThrottler;
import com.skribbl.common.dto.DrawStrokeDto;
import com.skribbl.common.model.StrokeData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.List;

public class DrawingCanvas extends BorderPane {
    public static final double LOGICAL_WIDTH = 960.0;
    public static final double LOGICAL_HEIGHT = 600.0;

    private final Canvas canvas;
    private final GraphicsContext gc;
    private final HBox toolbar;
    private final ClientController controller;
    private final StrokeThrottler throttler = new StrokeThrottler();

    private String currentColor = "#000000";
    private int currentThickness = 5;
    private boolean isEraser = false;
    private boolean drawingEnabled = false;

    private double prevX = -1;
    private double prevY = -1;

    private static final String[] PALETTE = {
            "#000000", "#7F7F7F", "#880015", "#ED1C24",
            "#FF7F27", "#FFF200", "#22B14C", "#00A2E8",
            "#3F48CC", "#A349A4", "#FFFFFF", "#B97A57"
    };

    public DrawingCanvas(ClientController controller) {
        this.controller = controller;
        this.canvas = new Canvas(LOGICAL_WIDTH, LOGICAL_HEIGHT);
        this.gc = canvas.getGraphicsContext2D();

        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        clearCanvasLocal();

        // Bọc Canvas trong StackPane để tự động thu phóng theo kích thước cửa sổ mà không méo tỉ lệ
        StackPane canvasHolder = new StackPane(canvas);
        canvasHolder.setMinSize(0, 0);
        canvasHolder.setStyle("-fx-background-color: #E0E0E0;");
        canvasHolder.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            if (newBounds.getWidth() <= 0 || newBounds.getHeight() <= 0) return;
            double scale = Math.min(newBounds.getWidth() / LOGICAL_WIDTH, newBounds.getHeight() / LOGICAL_HEIGHT);
            canvas.setScaleX(scale);
            canvas.setScaleY(scale);
        });

        setupMouseHandlers();
        this.toolbar = createToolbar();

        setCenter(canvasHolder);
        setBottom(toolbar);
        setMinSize(0, 0);
        setStyle("-fx-border-color: #CCCCCC; -fx-border-width: 1; -fx-background-color: #FFFFFF;");
        setDrawingEnabled(false);
    }

    private void setupMouseHandlers() {
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (!drawingEnabled) return;
            throttler.reset();
            prevX = clampX(e.getX());
            prevY = clampY(e.getY());
            throttler.shouldSend(prevX, prevY);
            emitSegment(prevX, prevY, prevX, prevY);
        });

        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!drawingEnabled) return;
            double x = clampX(e.getX());
            double y = clampY(e.getY());
            if (throttler.shouldSend(x, y)) {
                emitSegment(prevX, prevY, x, y);
                prevX = x;
                prevY = y;
            }
        });

        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            prevX = -1;
            prevY = -1;
            throttler.reset();
        });
    }

    private double clampX(double x) {
        return Math.max(0, Math.min(LOGICAL_WIDTH, x));
    }

    private double clampY(double y) {
        return Math.max(0, Math.min(LOGICAL_HEIGHT, y));
    }

    private void emitSegment(double x1, double y1, double x2, double y2) {
        String hex = isEraser ? "#FFFFFF" : currentColor;
        DrawStrokeDto dto = new DrawStrokeDto(x1, y1, x2, y2, hex, currentThickness, isEraser);
        drawSegmentLocal(x1, y1, x2, y2, hex, currentThickness, isEraser);
        controller.sendDrawStroke(dto);
    }

    private void drawSegmentLocal(double x1, double y1, double x2, double y2,
                                  String hexColor, int thickness, boolean eraser) {
        gc.setStroke(eraser ? Color.WHITE : Color.web(hexColor));
        gc.setLineWidth(thickness);
        gc.strokeLine(x1, y1, x2, y2);
    }

    public void renderRemoteStroke(DrawStrokeDto dto) {
        drawSegmentLocal(dto.x1(), dto.y1(), dto.x2(), dto.y2(), dto.color(), dto.thickness(), dto.eraser());
    }

    public void renderSyncHistory(List<StrokeData> strokes) {
        clearCanvasLocal();
        if (strokes == null) return;
        for (StrokeData s : strokes) {
            drawSegmentLocal(s.x1(), s.y1(), s.x2(), s.y2(), s.color(), s.thickness(), s.eraser());
        }
    }

    public void clearCanvasLocal() {
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT);
    }

    public void setDrawingEnabled(boolean enabled) {
        this.drawingEnabled = enabled;
        this.toolbar.setVisible(enabled);
        this.toolbar.setManaged(enabled);
    }

    private HBox createToolbar() {
        HBox bar = new HBox(6);
        bar.setPadding(new Insets(6));
        bar.setAlignment(Pos.CENTER);
        bar.setStyle("-fx-background-color: #F4F4F4; -fx-border-color: #DDDDDD; -fx-border-width: 1 0 0 0;");

        for (String hex : PALETTE) {
            Button colorBtn = new Button();
            colorBtn.setPrefSize(22, 22);
            colorBtn.setMinSize(18, 18);
            colorBtn.setStyle("-fx-background-color: " + hex + "; -fx-border-color: #555555; -fx-border-radius: 3;");
            colorBtn.setOnAction(e -> {
                isEraser = false;
                currentColor = hex;
            });
            bar.getChildren().add(colorBtn);
        }

        Slider sizeSlider = new Slider(2, 24, 5);
        sizeSlider.setPrefWidth(80);
        sizeSlider.valueProperty().addListener((obs, oldVal, newVal) -> currentThickness = newVal.intValue());

        Button eraserBtn = new Button("Tẩy");
        eraserBtn.setOnAction(e -> isEraser = true);

        Button clearBtn = new Button("Xóa hết");
        clearBtn.setStyle("-fx-background-color: #E53935; -fx-text-fill: white;");
        clearBtn.setOnAction(e -> {
            clearCanvasLocal();
            controller.sendClearCanvas();
        });

        bar.getChildren().addAll(new Label("Cỡ:"), sizeSlider, eraserBtn, clearBtn);
        return bar;
    }
}