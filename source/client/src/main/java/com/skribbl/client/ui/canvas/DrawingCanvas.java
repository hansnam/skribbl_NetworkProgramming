package com.skribbl.client.ui.canvas;

import com.skribbl.client.controller.ClientController;
import com.skribbl.client.net.StrokeThrottler;
import com.skribbl.client.ui.avatar.AvatarView;
import com.skribbl.common.dto.DrawStrokeDto;
import com.skribbl.common.dto.FillCanvasDto;
import com.skribbl.common.dto.GameOverDto;
import com.skribbl.common.dto.TurnEndDto;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.StrokeData;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.transform.Transform;
import javafx.util.Duration;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Skribbl.io-style DrawingCanvas:
 * - 960x600 logical coordinate system with automatic aspect-ratio scaling.
 * - 26-color 2-row palette + active color swatch.
 * - 5 circular brush size presets (4px, 8px, 14px, 22px, 32px).
 * - Tools: Brush (B), Eraser (E), Flood Fill Bucket (F), Undo (U), Clear (C), and Reactions (Like/Dislike).
 * - Built-in In-Canvas Overlay layer for Word Selection, Turn Reveal, and Game Over Podium.
 */
public class DrawingCanvas extends BorderPane {
    public static final double LOGICAL_WIDTH = 960.0;
    public static final double LOGICAL_HEIGHT = 600.0;

    private static final String[] SKRIBBL_PALETTE_26 = {
            // Row 1 (Light / Bright)
            "#FFFFFF", "#C1C1C1", "#EF130B", "#FF7100", "#FFE400", "#00CC00", "#00FE93",
            "#00B2FF", "#231FD3", "#A300BA", "#DF69A7", "#FFAC8E", "#A0522D",
            // Row 2 (Dark / Rich)
            "#000000", "#505050", "#740B07", "#C23800", "#E8A200", "#004619", "#00785D",
            "#00569E", "#0E0865", "#550069", "#873554", "#CC774D", "#63300D"
    };

    private static final int[] BRUSH_SIZES = {4, 8, 14, 22, 32};

    private final Canvas canvas;
    private final GraphicsContext gc;
    private final StackPane canvasHolder;
    private final StackPane overlayPane;
    private final Label reactionToastLabel;
    private final HBox reactionBar;
    private final HBox toolbar;
    private final Rectangle activeColorSwatch;
    private final ClientController controller;
    private final StrokeThrottler throttler = new StrokeThrottler();
    private final List<StrokeData> localHistory = new ArrayList<>();

    private String currentColor = "#000000";
    private int currentThickness = 5;
    private boolean isEraser = false;
    private boolean isFillMode = false;
    private boolean drawingEnabled = false;
    private long strokeSeq = 0;
    private String currentStrokeGroupId = "";
    private Timeline overlayTimeline;

    private Button brushToolBtn;
    private Button eraserToolBtn;
    private Button fillToolBtn;
    private final List<Button> sizePresetBtns = new ArrayList<>();

    private double prevX = -1;
    private double prevY = -1;

    public DrawingCanvas(ClientController controller) {
        this.controller = controller;
        this.canvas = new Canvas(LOGICAL_WIDTH, LOGICAL_HEIGHT);
        this.gc = canvas.getGraphicsContext2D();

        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        clearCanvasLocal();

        activeColorSwatch = new Rectangle(32, 32, Color.web(currentColor));
        activeColorSwatch.setArcWidth(8);
        activeColorSwatch.setArcHeight(8);
        activeColorSwatch.setStroke(Color.web("#1E293B"));
        activeColorSwatch.setStrokeWidth(2);

        // In-Canvas Overlay Layer (for Word Choice, Turn Reveal, Game Over Podium)
        overlayPane = new StackPane();
        overlayPane.setVisible(false);
        overlayPane.setManaged(false);
        overlayPane.setStyle("-fx-background-color: rgba(15, 23, 42, 0.78);");

        // Floating Reaction Toast & Like/Dislike Bar (top-right of canvas)
        reactionToastLabel = new Label("");
        reactionToastLabel.setVisible(false);
        reactionToastLabel.setStyle("-fx-background-color: rgba(15,23,42,0.85); -fx-text-fill: #FACC15; -fx-font-weight: bold; -fx-padding: 6 14 6 14; -fx-background-radius: 16;");
        StackPane.setAlignment(reactionToastLabel, Pos.TOP_CENTER);
        StackPane.setMargin(reactionToastLabel, new Insets(12, 0, 0, 0));

        Button likeBtn = new Button("👍");
        likeBtn.setTooltip(new Tooltip("Thích hình vẽ này!"));
        likeBtn.setStyle("-fx-background-color: rgba(34,197,94,0.9); -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 16; -fx-cursor: hand;");
        likeBtn.setOnAction(e -> controller.sendReaction(true));

        Button dislikeBtn = new Button("👎");
        dislikeBtn.setTooltip(new Tooltip("Chê hình vẽ này!"));
        dislikeBtn.setStyle("-fx-background-color: rgba(239,68,68,0.9); -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 16; -fx-cursor: hand;");
        dislikeBtn.setOnAction(e -> controller.sendReaction(false));

        reactionBar = new HBox(6, likeBtn, dislikeBtn);
        reactionBar.setAlignment(Pos.TOP_RIGHT);
        reactionBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        reactionBar.setVisible(false);
        StackPane.setAlignment(reactionBar, Pos.TOP_RIGHT);
        StackPane.setMargin(reactionBar, new Insets(10, 12, 0, 0));

        // IMPORTANT: canvas MUST be child index 0 of canvasHolder so ClientFlowTest.findCanvas finds it first
        canvasHolder = new StackPane(canvas, reactionBar, reactionToastLabel, overlayPane);
        canvasHolder.setMinSize(0, 0);
        canvasHolder.setStyle("-fx-background-color: #CBD5E1;");
        canvasHolder.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            if (newBounds.getWidth() <= 0 || newBounds.getHeight() <= 0) return;
            double scale = Math.min(newBounds.getWidth() / LOGICAL_WIDTH, newBounds.getHeight() / LOGICAL_HEIGHT);
            canvas.setScaleX(scale);
            canvas.setScaleY(scale);
        });

        setupMouseAndKeyHandlers();
        this.toolbar = createToolbar();

        setCenter(canvasHolder);
        setBottom(toolbar);
        setMinSize(0, 0);
        setStyle("-fx-border-color: #94A3B8; -fx-border-width: 1.5; -fx-background-color: #FFFFFF;");
        setDrawingEnabled(false);
    }

    private void setupMouseAndKeyHandlers() {
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (!drawingEnabled) return;
            canvas.requestFocus();
            double x = clampX(e.getX());
            double y = clampY(e.getY());
            currentStrokeGroupId = "g-" + (++strokeSeq);

            if (isFillMode) {
                floodFillLocal(x, y, currentColor);
                localHistory.add(new StrokeData(x, y, x, y, currentColor, 1, false, currentStrokeGroupId, true));
                controller.sendFillCanvas(new FillCanvasDto(x, y, currentColor, currentStrokeGroupId));
                return;
            }

            throttler.reset();
            prevX = x;
            prevY = y;
            throttler.shouldSend(prevX, prevY);
            emitSegment(prevX, prevY, prevX, prevY);
        });

        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!drawingEnabled || isFillMode) return;
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

        // Keyboard shortcuts: B (Brush), E (Eraser), F (Fill), U (Undo), C (Clear)
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (!drawingEnabled || e.getTarget() instanceof javafx.scene.control.TextInputControl) {
                return;
            }
            switch (e.getCode()) {
                case B -> selectTool(false, false);
                case E -> selectTool(true, false);
                case F -> selectTool(false, true);
                case U -> controller.sendUndoCanvas();
                case C -> controller.sendClearCanvas();
                case Z -> {
                    if (e.isControlDown()) {
                        controller.sendUndoCanvas();
                    }
                }
                default -> { }
            }
        });
    }

    private void selectTool(boolean eraser, boolean fill) {
        this.isEraser = eraser;
        this.isFillMode = fill;
        if (brushToolBtn != null) {
            styleToolButton(brushToolBtn, !eraser && !fill);
            styleToolButton(eraserToolBtn, eraser);
            styleToolButton(fillToolBtn, fill);
        }
        canvas.setCursor(drawingEnabled ? Cursor.CROSSHAIR : Cursor.DEFAULT);
    }

    private void styleToolButton(Button btn, boolean active) {
        if (btn == null) return;
        if (active) {
            btn.setStyle("-fx-background-color: #DBEAFE; -fx-text-fill: #1D4ED8; -fx-font-weight: bold; -fx-border-color: #2563EB; -fx-border-width: 2; -fx-border-radius: 7; -fx-background-radius: 7; -fx-cursor: hand;");
        } else {
            btn.setStyle("-fx-background-color: #FFFFFF; -fx-text-fill: #1E293B; -fx-font-weight: bold; -fx-border-color: #CBD5E1; -fx-border-width: 1.5; -fx-border-radius: 7; -fx-background-radius: 7; -fx-cursor: hand;");
        }
    }

    private double clampX(double x) {
        return Math.max(0, Math.min(LOGICAL_WIDTH, x));
    }

    private double clampY(double y) {
        return Math.max(0, Math.min(LOGICAL_HEIGHT, y));
    }

    private void emitSegment(double x1, double y1, double x2, double y2) {
        String hex = isEraser ? "#FFFFFF" : currentColor;
        DrawStrokeDto dto = new DrawStrokeDto(x1, y1, x2, y2, hex, currentThickness, isEraser, currentStrokeGroupId);
        drawSegmentLocal(x1, y1, x2, y2, hex, currentThickness, isEraser);
        localHistory.add(dto.toStrokeData());
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

    public void renderRemoteFill(FillCanvasDto dto) {
        if (dto == null) return;
        floodFillLocal(dto.x(), dto.y(), dto.color());
    }

    public void renderSyncHistory(List<StrokeData> strokes) {
        clearCanvasLocal();
        localHistory.clear();
        if (strokes == null) return;
        localHistory.addAll(strokes);
        for (StrokeData s : strokes) {
            if (s.fill()) {
                floodFillLocal(s.x1(), s.y1(), s.color());
            } else {
                drawSegmentLocal(s.x1(), s.y1(), s.x2(), s.y2(), s.color(), s.thickness(), s.eraser());
            }
        }
    }

    public void undoLocalOffline() {
        if (localHistory.isEmpty()) return;
        int lastIdx = localHistory.size() - 1;
        String group = localHistory.get(lastIdx).strokeGroupId();
        if (group == null || group.isBlank()) {
            localHistory.remove(lastIdx);
        } else {
            while (!localHistory.isEmpty() && group.equals(localHistory.get(localHistory.size() - 1).strokeGroupId())) {
                localHistory.remove(localHistory.size() - 1);
            }
        }
        List<StrokeData> copy = new ArrayList<>(localHistory);
        renderSyncHistory(copy);
    }

    /**
     * Non-recursive Scanline Flood Fill at logical coordinates (startX, startY) on 960x600 Canvas.
     */
    public void floodFillLocal(double startX, double startY, String hexColor) {
        int sx = Math.max(0, Math.min((int) LOGICAL_WIDTH - 1, (int) Math.round(startX)));
        int sy = Math.max(0, Math.min((int) LOGICAL_HEIGHT - 1, (int) Math.round(startY)));
        int w = (int) LOGICAL_WIDTH;
        int h = (int) LOGICAL_HEIGHT;

        Color fillColor = Color.web(hexColor != null ? hexColor : "#000000");
        int replacementArgb = (0xFF << 24)
                | ((int) Math.round(fillColor.getRed() * 255) << 16)
                | ((int) Math.round(fillColor.getGreen() * 255) << 8)
                | ((int) Math.round(fillColor.getBlue() * 255));

        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(Transform.scale(1.0 / Math.max(0.01, canvas.getScaleX()), 1.0 / Math.max(0.01, canvas.getScaleY())));
        WritableImage snap = new WritableImage(w, h);
        canvas.snapshot(params, snap);

        PixelReader reader = snap.getPixelReader();
        WritablePixelFormat<IntBuffer> format = WritablePixelFormat.getIntArgbInstance();
        int[] pixels = new int[w * h];
        reader.getPixels(0, 0, w, h, format, pixels, 0, w);

        int targetArgb = pixels[sy * w + sx];
        if (colorsClose(targetArgb, replacementArgb, 8)) {
            return;
        }

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(sy * w + sx);

        while (!queue.isEmpty()) {
            int idx = queue.pollFirst();
            int x = idx % w;
            int y = idx / w;
            int rowOffset = y * w;

            if (!colorsClose(pixels[rowOffset + x], targetArgb, 28)) {
                continue;
            }

            int left = x;
            while (left > 0 && colorsClose(pixels[rowOffset + left - 1], targetArgb, 28)) {
                left--;
            }
            int right = x;
            while (right < w - 1 && colorsClose(pixels[rowOffset + right + 1], targetArgb, 28)) {
                right++;
            }

            boolean spanAbove = false;
            boolean spanBelow = false;
            for (int ix = left; ix <= right; ix++) {
                pixels[rowOffset + ix] = replacementArgb;

                if (y > 0) {
                    boolean matchAbove = colorsClose(pixels[(y - 1) * w + ix], targetArgb, 28);
                    if (!spanAbove && matchAbove) {
                        queue.addLast((y - 1) * w + ix);
                        spanAbove = true;
                    } else if (spanAbove && !matchAbove) {
                        spanAbove = false;
                    }
                }
                if (y < h - 1) {
                    boolean matchBelow = colorsClose(pixels[(y + 1) * w + ix], targetArgb, 28);
                    if (!spanBelow && matchBelow) {
                        queue.addLast((y + 1) * w + ix);
                        spanBelow = true;
                    } else if (spanBelow && !matchBelow) {
                        spanBelow = false;
                    }
                }
            }
        }

        PixelWriter writer = gc.getPixelWriter();
        writer.setPixels(0, 0, w, h, format, pixels, 0, w);
    }

    private static boolean colorsClose(int a, int b, int tol) {
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return dr <= tol && dg <= tol && db <= tol;
    }

    public void clearCanvasLocal() {
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT);
    }

    public void setDrawingEnabled(boolean enabled) {
        this.drawingEnabled = enabled;
        prevX = -1;
        prevY = -1;
        throttler.reset();
        this.toolbar.setVisible(enabled);
        this.toolbar.setManaged(enabled);
        canvas.setCursor(enabled ? Cursor.CROSSHAIR : Cursor.DEFAULT);
    }

    public void setReactionBarVisible(boolean visible) {
        reactionBar.setVisible(visible);
    }

    public void showReactionToast(String text) {
        reactionToastLabel.setText(text);
        reactionToastLabel.setVisible(true);
        PauseTransition pause = new PauseTransition(Duration.seconds(2.5));
        pause.setOnFinished(e -> reactionToastLabel.setVisible(false));
        pause.play();
    }

    // ── In-Canvas Overlays (Word Choice, Turn Reveal, Game Over Podium) ──

    public void showWordChoicesOverlay(List<String> words, int chooseSeconds, IntConsumer onChosen) {
        stopOverlayTimeline();
        int seconds = chooseSeconds > 0 ? chooseSeconds : 10;

        VBox card = new VBox(16);
        card.setAlignment(Pos.CENTER);
        card.setMaxSize(520, 250);
        card.setPadding(new Insets(24));
        card.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 16; -fx-border-color: #3B82F6; -fx-border-width: 3; -fx-border-radius: 16;");

        Label title = new Label("🎨 TỚI LƯỢT BẠN VẼ!");
        title.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 22));
        title.setStyle("-fx-text-fill: #1E3A8A;");

        Label timerLbl = new Label("Hãy chọn 1 trong 3 từ khóa (" + seconds + "s):");
        timerLbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        timerLbl.setStyle("-fx-text-fill: #475569;");

        HBox btnRow = new HBox(12);
        btnRow.setAlignment(Pos.CENTER);
        for (int i = 0; i < words.size(); i++) {
            final int idx = i;
            String w = words.get(i);
            Button btn = new Button(w.toUpperCase() + " (" + w.length() + ")");
            btn.setPrefHeight(46);
            btn.setMinWidth(135);
            btn.setStyle("-fx-background-color: #3B9DF8; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-radius: 10; -fx-cursor: hand;");
            btn.setOnAction(e -> {
                hideOverlay();
                onChosen.accept(idx);
            });
            btnRow.getChildren().add(btn);
        }

        card.getChildren().addAll(title, timerLbl, btnRow);
        showOverlayNode(card);

        final int[] rem = {seconds};
        overlayTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            rem[0]--;
            if (rem[0] > 0) {
                timerLbl.setText("Hãy chọn 1 trong 3 từ khóa (" + rem[0] + "s):");
            } else {
                hideOverlay();
            }
        }));
        overlayTimeline.setCycleCount(seconds);
        overlayTimeline.play();
    }

    public void showTurnRevealOverlay(TurnEndDto dto) {
        stopOverlayTimeline();
        if (dto == null) return;

        VBox card = new VBox(12);
        card.setAlignment(Pos.CENTER);
        card.setMaxSize(460, 290);
        card.setPadding(new Insets(22));
        card.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 16; -fx-border-color: #FACC15; -fx-border-width: 3; -fx-border-radius: 16;");

        String wordText = (dto.word() == null || dto.word().isBlank())
                ? "Lượt bị hủy trước khi chọn từ"
                : "Đáp án là: " + dto.word().toUpperCase();
        Label wordLbl = new Label(wordText);
        wordLbl.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 22));
        wordLbl.setStyle("-fx-text-fill: #15803D;");

        String reasonVi = switch (dto.reason() != null ? dto.reason() : "") {
            case "ALL_GUESSED" -> "🎉 Tất cả người chơi đều đã đoán đúng!";
            case "TIME_UP" -> "⏰ Hết thời gian vẽ!";
            case "DRAWER_LEFT" -> "🚪 Người vẽ đã rời phòng!";
            default -> "Kết thúc lượt";
        };
        Label reasonLbl = new Label(reasonVi);
        reasonLbl.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 13px;");

        VBox scoreBox = new VBox(5);
        scoreBox.setAlignment(Pos.CENTER);
        Map<String, Integer> scores = dto.turnScores() != null ? dto.turnScores() : Map.of();
        if (dto.players() != null) {
            for (PlayerDto p : dto.players()) {
                int gain = scores.getOrDefault(p.id(), 0);
                Label row = new Label(p.nickname() + ": " + (gain > 0 ? "+" + gain + " điểm" : "+0 điểm"));
                row.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: " + (gain > 0 ? "#16A34A;" : "#64748B;"));
                scoreBox.getChildren().add(row);
            }
        }

        card.getChildren().addAll(wordLbl, reasonLbl, scoreBox);
        showOverlayNode(card);
    }

    public void showGameOverOverlay(GameOverDto dto, Runnable onPlayAgain) {
        stopOverlayTimeline();
        if (dto == null) return;

        VBox card = new VBox(14);
        card.setAlignment(Pos.CENTER);
        card.setMaxSize(540, 360);
        card.setPadding(new Insets(24));
        card.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 18; -fx-border-color: #F59E0B; -fx-border-width: 3; -fx-border-radius: 18;");

        List<String> winnerNames = new ArrayList<>();
        if (dto.winners() != null) {
            for (PlayerDto w : dto.winners()) {
                winnerNames.add(w.nickname());
            }
        }
        Label title = new Label("🏆 KẾT THÚC VÁN ĐẤU!");
        title.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 24));
        title.setStyle("-fx-text-fill: #B45309;");

        Label winnerLbl = new Label("Quán quân: " + String.join(", ", winnerNames));
        winnerLbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));
        winnerLbl.setStyle("-fx-text-fill: #15803D;");

        // Top 3 Podium Row
        HBox podium = new HBox(18);
        podium.setAlignment(Pos.BOTTOM_CENTER);
        if (dto.players() != null) {
            int limit = Math.min(3, dto.players().size());
            String[] medals = {"🥇 Hạng 1", "🥈 Hạng 2", "🥉 Hạng 3"};
            for (int i = 0; i < limit; i++) {
                PlayerDto p = dto.players().get(i);
                VBox slot = new VBox(4);
                slot.setAlignment(Pos.CENTER);
                slot.setPadding(new Insets(8, 14, 8, 14));
                slot.setStyle("-fx-background-color: #F8FAFC; -fx-background-radius: 12; -fx-border-color: #CBD5E1; -fx-border-radius: 12;");
                AvatarView av = new AvatarView(p.avatar(), i == 0 ? 54 : 44);
                Label mLbl = new Label(medals[i]);
                mLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #B45309; -fx-font-size: 12px;");
                Label nLbl = new Label(p.nickname());
                nLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #0F172A; -fx-font-size: 13px;");
                Label sLbl = new Label(p.score() + " điểm");
                sLbl.setStyle("-fx-text-fill: #15803D; -fx-font-weight: bold; -fx-font-size: 12px;");
                slot.getChildren().addAll(av, mLbl, nLbl, sLbl);
                podium.getChildren().add(slot);
            }
        }

        HBox actions = new HBox(12);
        actions.setAlignment(Pos.CENTER);
        Button closeBtn = new Button("Đóng bảng kết quả");
        closeBtn.setStyle("-fx-background-color: #64748B; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> hideOverlay());
        actions.getChildren().add(closeBtn);

        if (onPlayAgain != null) {
            Button playAgainBtn = new Button("🚀 Chơi ván mới");
            playAgainBtn.setStyle("-fx-background-color: #53D13A; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-cursor: hand;");
            playAgainBtn.setOnAction(e -> {
                hideOverlay();
                onPlayAgain.run();
            });
            actions.getChildren().add(0, playAgainBtn);
        }

        card.getChildren().addAll(title, winnerLbl, podium, actions);
        showOverlayNode(card);
    }

    private void showOverlayNode(Region content) {
        overlayPane.getChildren().setAll(content);
        overlayPane.setVisible(true);
        overlayPane.setManaged(true);
    }

    public void hideOverlay() {
        stopOverlayTimeline();
        overlayPane.getChildren().clear();
        overlayPane.setVisible(false);
        overlayPane.setManaged(false);
    }

    private void stopOverlayTimeline() {
        if (overlayTimeline != null) {
            overlayTimeline.stop();
            overlayTimeline = null;
        }
    }

    private HBox createToolbar() {
        HBox bar = new HBox(8);
        bar.setPadding(new Insets(7, 10, 7, 10));
        bar.setAlignment(Pos.CENTER);
        bar.setStyle("-fx-background-color: #F8FAFC; -fx-border-color: #CBD5E1; -fx-border-width: 1.5 0 0 0;");

        // 1. 26-Color 2-Row Skribbl Palette
        GridPane colorGrid = new GridPane();
        colorGrid.setHgap(2);
        colorGrid.setVgap(2);
        colorGrid.setAlignment(Pos.CENTER);

        for (int i = 0; i < SKRIBBL_PALETTE_26.length; i++) {
            final String hex = SKRIBBL_PALETTE_26[i];
            Button colorBtn = new Button();
            colorBtn.setPrefSize(18, 18);
            colorBtn.setMinSize(16, 16);
            colorBtn.setMaxSize(18, 18);
            colorBtn.setStyle("-fx-background-color: " + hex + "; -fx-border-color: #475569; -fx-border-width: 0.8; -fx-background-radius: 2; -fx-border-radius: 2; -fx-cursor: hand;");
            colorBtn.setOnAction(e -> {
                currentColor = hex;
                activeColorSwatch.setFill(Color.web(hex));
                if (isEraser) {
                    selectTool(false, false);
                }
            });
            colorGrid.add(colorBtn, i % 13, i / 13);
        }

        // 2. 5 Circular Brush Size Presets
        HBox sizeBox = new HBox(4);
        sizeBox.setAlignment(Pos.CENTER);
        for (int size : BRUSH_SIZES) {
            Button sizeBtn = new Button();
            sizeBtn.setPrefSize(28, 28);
            sizeBtn.setMinSize(26, 26);
            Circle dot = new Circle(Math.max(2.5, Math.min(10.0, size * 0.34)), Color.web("#0F172A"));
            sizeBtn.setGraphic(dot);
            sizeBtn.setTooltip(new Tooltip("Cỡ cọ: " + size + "px"));
            sizeBtn.setOnAction(e -> {
                currentThickness = size;
                highlightSizePreset(sizeBtn);
            });
            sizePresetBtns.add(sizeBtn);
            sizeBox.getChildren().add(sizeBtn);
        }
        highlightSizePreset(sizePresetBtns.get(0));

        // 3. Tool Buttons: Brush (B), Eraser (E), Fill Bucket (F), Undo (U)
        brushToolBtn = new Button("🖌 Bút");
        brushToolBtn.setTooltip(new Tooltip("Bút vẽ (Phím tắt: B)"));
        brushToolBtn.setOnAction(e -> selectTool(false, false));

        eraserToolBtn = new Button("🧽 Tẩy");
        eraserToolBtn.setTooltip(new Tooltip("Cục tẩy (Phím tắt: E)"));
        eraserToolBtn.setOnAction(e -> selectTool(true, false));

        fillToolBtn = new Button("🪣 Đổ màu");
        fillToolBtn.setTooltip(new Tooltip("Đổ màu vùng kín (Phím tắt: F)"));
        fillToolBtn.setOnAction(e -> selectTool(false, true));

        selectTool(false, false);

        Button undoBtn = new Button("↩ Hoàn tác");
        undoBtn.setTooltip(new Tooltip("Hoàn tác nét vừa vẽ (Phím tắt: U / Ctrl+Z)"));
        styleToolButton(undoBtn, false);
        undoBtn.setOnAction(e -> controller.sendUndoCanvas());

        // IMPORTANT: clearBtn MUST remain the very last child (toolbar.getChildren().getLast()) for ClientFlowTest!
        Button clearBtn = new Button("🗑 Xóa hết");
        clearBtn.setTooltip(new Tooltip("Xóa trắng bảng vẽ (Phím tắt: C)"));
        clearBtn.setStyle("-fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 7; -fx-cursor: hand;");
        clearBtn.setOnAction(e -> controller.sendClearCanvas());

        bar.getChildren().addAll(activeColorSwatch, colorGrid, sizeBox, brushToolBtn, eraserToolBtn, fillToolBtn, undoBtn, clearBtn);
        return bar;
    }

    private void highlightSizePreset(Button activeBtn) {
        for (Button b : sizePresetBtns) {
            styleToolButton(b, b == activeBtn);
        }
    }
}
