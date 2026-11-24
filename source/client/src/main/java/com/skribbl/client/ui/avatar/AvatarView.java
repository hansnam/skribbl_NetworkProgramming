package com.skribbl.client.ui.avatar;

import com.skribbl.common.model.AvatarDto;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;

/**
 * Renders a Skribbl.io-style customizable cartoon avatar on a JavaFX Canvas.
 */
public class AvatarView extends Canvas {

    public static final String[] BODY_COLORS = {
            "#FF6B6B", "#4ECDC4", "#FFE66D", "#A78BFA", "#38BDF8",
            "#F472B6", "#4ADE80", "#FB923C", "#E879F9", "#60A5FA"
    };

    private AvatarDto avatar;
    private Image remoteImage;

    public AvatarView(AvatarDto avatar, double size) {
        super(size, size);
        setAvatar(avatar);
    }

    public AvatarDto getAvatar() {
        return avatar;
    }

    public void setAvatar(AvatarDto value) {
        avatar = value != null ? value : AvatarDto.DEFAULT;
        remoteImage = null;
        draw();

        String imageId = avatar.imageId();
        if (imageId == null) {
            return;
        }

        AvatarImages.load(imageId).thenAccept(image -> Platform.runLater(() -> {
            if (imageId.equals(avatar.imageId())) {
                remoteImage = image;
                draw();
            }
        }));
    }

    public void draw() {
        double w = getWidth();
        double h = getHeight();
        GraphicsContext gc = getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);
        if (remoteImage != null) {
            gc.drawImage(remoteImage, 0, 0, w, h);
            return;
        }
        gc.setLineCap(StrokeLineCap.ROUND);

        String hex = BODY_COLORS[Math.floorMod(avatar.colorIndex(), BODY_COLORS.length)];
        Color bodyColor = Color.web(hex);

        double pad = w * 0.08;
        double d = w - pad * 2;

        // Body shadow & main circle
        gc.setFill(bodyColor);
        gc.fillOval(pad, pad, d, d);

        // Cheek blush
        gc.setFill(Color.rgb(255, 120, 140, 0.42));
        gc.fillOval(w * 0.16, h * 0.54, w * 0.14, h * 0.09);
        gc.fillOval(w * 0.70, h * 0.54, w * 0.14, h * 0.09);

        // Outline
        gc.setStroke(Color.web("#1E293B"));
        gc.setLineWidth(Math.max(1.8, w * 0.05));
        gc.strokeOval(pad, pad, d, d);

        drawEyes(gc, w, h, Math.floorMod(avatar.eyesIndex(), AvatarDto.EYES_COUNT));
        drawMouth(gc, w, h, Math.floorMod(avatar.mouthIndex(), AvatarDto.MOUTH_COUNT));
    }

    private void drawEyes(GraphicsContext gc, double w, double h, int type) {
        double lx = w * 0.34;
        double rx = w * 0.66;
        double y = h * 0.40;
        double r = w * 0.09;
        gc.setStroke(Color.web("#1E293B"));
        gc.setFill(Color.web("#1E293B"));
        gc.setLineWidth(Math.max(1.6, w * 0.045));

        switch (type) {
            case 0 -> { // Classic white eyes with pupils
                gc.setFill(Color.WHITE);
                gc.fillOval(lx - r, y - r, r * 2, r * 2);
                gc.fillOval(rx - r, y - r, r * 2, r * 2);
                gc.strokeOval(lx - r, y - r, r * 2, r * 2);
                gc.strokeOval(rx - r, y - r, r * 2, r * 2);
                gc.setFill(Color.web("#1E293B"));
                double pr = r * 0.52;
                gc.fillOval(lx - pr * 0.7, y - pr, pr * 2, pr * 2);
                gc.fillOval(rx - pr * 0.7, y - pr, pr * 2, pr * 2);
            }
            case 1 -> { // Cool sunglasses
                gc.setFill(Color.web("#0F172A"));
                gc.fillRoundRect(w * 0.18, y - r * 0.9, w * 0.28, r * 1.8, 6, 6);
                gc.fillRoundRect(w * 0.54, y - r * 0.9, w * 0.28, r * 1.8, 6, 6);
                gc.strokeLine(w * 0.45, y - r * 0.2, w * 0.55, y - r * 0.2);
                gc.strokeLine(w * 0.10, y - r * 0.3, w * 0.18, y - r * 0.3);
                gc.strokeLine(w * 0.82, y - r * 0.3, w * 0.90, y - r * 0.3);
            }
            case 2 -> { // Happy curved arcs ^ ^
                gc.strokeArc(lx - r, y - r * 0.8, r * 2, r * 1.6, 15, 150, ArcType.OPEN);
                gc.strokeArc(rx - r, y - r * 0.8, r * 2, r * 1.6, 15, 150, ArcType.OPEN);
            }
            case 3 -> { // Wink ;)
                gc.setFill(Color.WHITE);
                gc.fillOval(lx - r, y - r, r * 2, r * 2);
                gc.strokeOval(lx - r, y - r, r * 2, r * 2);
                gc.setFill(Color.web("#1E293B"));
                gc.fillOval(lx - r * 0.4, y - r * 0.4, r * 0.9, r * 0.9);
                gc.strokeLine(rx - r, y - r * 0.5, rx + r * 0.8, y);
                gc.strokeLine(rx - r, y + r * 0.5, rx + r * 0.8, y);
            }
            case 4 -> { // Determined angry brows
                gc.fillOval(lx - r * 0.55, y - r * 0.4, r * 1.1, r * 1.1);
                gc.fillOval(rx - r * 0.55, y - r * 0.4, r * 1.1, r * 1.1);
                gc.strokeLine(lx - r, y - r * 1.1, lx + r * 0.8, y - r * 0.4);
                gc.strokeLine(rx + r, y - r * 1.1, rx - r * 0.8, y - r * 0.4);
            }
            case 5 -> { // Surprised wide eyes
                double wr = r * 1.25;
                gc.setFill(Color.WHITE);
                gc.fillOval(lx - wr, y - wr, wr * 2, wr * 2);
                gc.fillOval(rx - wr, y - wr, wr * 2, wr * 2);
                gc.strokeOval(lx - wr, y - wr, wr * 2, wr * 2);
                gc.strokeOval(rx - wr, y - wr, wr * 2, wr * 2);
                gc.setFill(Color.web("#1E293B"));
                gc.fillOval(lx - r * 0.35, y - r * 0.35, r * 0.7, r * 0.7);
                gc.fillOval(rx - r * 0.35, y - r * 0.35, r * 0.7, r * 0.7);
            }
            case 6 -> { // Sleepy half-closed
                gc.setFill(Color.WHITE);
                gc.fillArc(lx - r, y - r, r * 2, r * 2, 180, 180, ArcType.CHORD);
                gc.fillArc(rx - r, y - r, r * 2, r * 2, 180, 180, ArcType.CHORD);
                gc.strokeArc(lx - r, y - r, r * 2, r * 2, 180, 180, ArcType.CHORD);
                gc.strokeArc(rx - r, y - r, r * 2, r * 2, 180, 180, ArcType.CHORD);
            }
            default -> { // Starry / Sparkle eyes
                gc.fillOval(lx - r * 0.75, y - r * 0.75, r * 1.5, r * 1.5);
                gc.fillOval(rx - r * 0.75, y - r * 0.75, r * 1.5, r * 1.5);
                gc.setFill(Color.WHITE);
                gc.fillOval(lx - r * 0.35, y - r * 0.45, r * 0.55, r * 0.55);
                gc.fillOval(rx - r * 0.35, y - r * 0.45, r * 0.55, r * 0.55);
            }
        }
    }

    private void drawMouth(GraphicsContext gc, double w, double h, int type) {
        double cx = w * 0.50;
        double y = h * 0.65;
        double mw = w * 0.34;
        double mh = h * 0.20;
        gc.setStroke(Color.web("#1E293B"));
        gc.setFill(Color.web("#1E293B"));
        gc.setLineWidth(Math.max(1.6, w * 0.045));

        switch (type) {
            case 0 -> // Classic big smile
                    gc.strokeArc(cx - mw / 2, y - mh * 0.5, mw, mh, 195, 150, ArcType.OPEN);
            case 1 -> { // Laughing open mouth
                gc.setFill(Color.web("#991B1B"));
                gc.fillArc(cx - mw / 2, y - mh * 0.5, mw, mh * 1.1, 180, 180, ArcType.CHORD);
                gc.strokeArc(cx - mw / 2, y - mh * 0.5, mw, mh * 1.1, 180, 180, ArcType.CHORD);
            }
            case 2 -> { // Surprised "O" mouth
                double r = w * 0.08;
                gc.setFill(Color.web("#7F1D1D"));
                gc.fillOval(cx - r, y - r * 0.6, r * 2, r * 2);
                gc.strokeOval(cx - r, y - r * 0.6, r * 2, r * 2);
            }
            case 3 -> { // Cat ":3" mouth
                double r = mw * 0.25;
                gc.strokeArc(cx - r * 2, y - r * 0.6, r * 2, r * 1.5, 180, 180, ArcType.OPEN);
                gc.strokeArc(cx, y - r * 0.6, r * 2, r * 1.5, 180, 180, ArcType.OPEN);
            }
            case 4 -> // Cool smirk
                    gc.strokeLine(cx - mw * 0.35, y + mh * 0.1, cx + mw * 0.4, y - mh * 0.2);
            case 5 -> { // Tongue out :P
                gc.strokeArc(cx - mw / 2, y - mh * 0.5, mw, mh * 0.8, 195, 150, ArcType.OPEN);
                gc.setFill(Color.web("#FB7185"));
                gc.fillOval(cx - mw * 0.15, y, mw * 0.3, mh * 0.55);
                gc.strokeOval(cx - mw * 0.15, y, mw * 0.3, mh * 0.55);
            }
            case 6 -> { // Grin with teeth
                gc.setFill(Color.WHITE);
                gc.fillRoundRect(cx - mw * 0.45, y - mh * 0.2, mw * 0.9, mh * 0.55, 6, 6);
                gc.strokeRoundRect(cx - mw * 0.45, y - mh * 0.2, mw * 0.9, mh * 0.55, 6, 6);
                gc.strokeLine(cx, y - mh * 0.2, cx, y + mh * 0.35);
            }
            default -> { // Mustache smile
                gc.strokeArc(cx - mw * 0.45, y - mh * 0.2, mw * 0.45, mh * 0.6, 0, 180, ArcType.OPEN);
                gc.strokeArc(cx, y - mh * 0.2, mw * 0.45, mh * 0.6, 0, 180, ArcType.OPEN);
            }
        }
    }
}
