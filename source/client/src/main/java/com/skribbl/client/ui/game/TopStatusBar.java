package com.skribbl.client.ui.game;

import com.skribbl.client.ui.audio.SoundManager;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

/**
 * Unified Skribbl.io-style Top Status Bar:
 * - Left: Circular Timer Badge + Round & Drawer Status + Role Hint
 * - Center: Word Mask with per-word character count hint e.g. "_ _ _   _ _ _ _ _ (3, 5)"
 * - Right: Room Code badge + Copy button + Start Game button + Mute toggle + Leave button
 */
public class TopStatusBar extends HBox {
    private final Label timerLabel;
    private final HBox timerBadge;
    private final Label statusLabel;
    private final Label roleHintLabel;
    private final Label wordHintLabel;
    private final Label wordLengthsLabel;
    private final Label roomBadgeLabel;
    private final Button copyCodeBtn;
    private final Button startBtn;
    private final Button muteBtn;
    private final Button leaveBtn;
    private String currentRoomCode = "";

    public TopStatusBar() {
        setPadding(new Insets(8, 14, 8, 14));
        setSpacing(12);
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("top-bar");
        setStyle("-fx-background-color: #162A5C; -fx-border-color: #2C4A8E; -fx-border-width: 0 0 2 0;");

        // 1. Timer Badge (Left)
        timerLabel = new Label("60s");
        timerLabel.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 16));
        timerLabel.setTextFill(Color.web("#1E293B"));
        timerBadge = new HBox(timerLabel);
        timerBadge.setAlignment(Pos.CENTER);
        timerBadge.setMinWidth(54);
        timerBadge.setPrefHeight(38);
        timerBadge.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 20; -fx-border-color: #22C55E; -fx-border-width: 3; -fx-border-radius: 20; -fx-padding: 2 10 2 10;");

        // 2. Round & Role Info
        statusLabel = new Label("Đang chờ bắt đầu...");
        statusLabel.setTextFill(Color.WHITE);
        statusLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13.5));

        roleHintLabel = new Label("Sảnh phòng chơi");
        roleHintLabel.setTextFill(Color.web("#93C5FD"));
        roleHintLabel.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11.5));

        VBox leftInfoBox = new VBox(2, statusLabel, roleHintLabel);
        leftInfoBox.setAlignment(Pos.CENTER_LEFT);

        // 3. Word Mask & Length Hint (Center)
        wordHintLabel = new Label("_ _ _ _ _");
        wordHintLabel.setTextFill(Color.web("#FACC15"));
        wordHintLabel.setFont(Font.font("Consolas", FontWeight.EXTRA_BOLD, 21));

        wordLengthsLabel = new Label("");
        wordLengthsLabel.setTextFill(Color.web("#CBD5E1"));
        wordLengthsLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));

        HBox wordBox = new HBox(8, wordHintLabel, wordLengthsLabel);
        wordBox.setAlignment(Pos.CENTER);
        wordBox.setPadding(new Insets(4, 16, 4, 16));
        wordBox.setStyle("-fx-background-color: #0F1F47; -fx-background-radius: 10; -fx-border-color: #3B63B8; -fx-border-radius: 10; -fx-border-width: 1.5;");

        // 4. Room Controls (Right)
        roomBadgeLabel = new Label("Chưa ở phòng");
        roomBadgeLabel.setTextFill(Color.web("#E2E8F0"));
        roomBadgeLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));

        copyCodeBtn = new Button("📋 Copy mã");
        copyCodeBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11.5px; -fx-background-radius: 6; -fx-cursor: hand;");
        copyCodeBtn.setVisible(false);
        copyCodeBtn.setManaged(false);
        copyCodeBtn.setOnAction(e -> copyRoomCode());

        startBtn = new Button("🚀 Bắt đầu");
        startBtn.setStyle("-fx-background-color: #53D13A; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px; -fx-background-radius: 7; -fx-cursor: hand;");

        muteBtn = new Button(SoundManager.isMuted() ? "🔇" : "🔊");
        muteBtn.setTooltip(new Tooltip("Bật / Tắt âm thanh hiệu ứng"));
        muteBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 7; -fx-cursor: hand;");
        muteBtn.setOnAction(e -> {
            boolean muted = SoundManager.toggleMute();
            muteBtn.setText(muted ? "🔇" : "🔊");
        });

        leaveBtn = new Button("🚪 Rời phòng");
        leaveBtn.setStyle("-fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px; -fx-background-radius: 7; -fx-cursor: hand;");

        HBox rightControls = new HBox(6, roomBadgeLabel, copyCodeBtn, startBtn, muteBtn, leaveBtn);
        rightControls.setAlignment(Pos.CENTER_RIGHT);

        Region leftSpacer = new Region();
        Region rightSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        getChildren().addAll(timerBadge, leftInfoBox, leftSpacer, wordBox, rightSpacer, rightControls);
    }

    private void copyRoomCode() {
        if (!copyCodeBtn.isVisible() || currentRoomCode == null || currentRoomCode.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(currentRoomCode);
        boolean copied = Clipboard.getSystemClipboard().setContent(content);
        copyCodeBtn.setText(copied ? "✅ Đã chép!" : "Chép mã thất bại");
        PauseTransition pause = new PauseTransition(Duration.seconds(1.8));
        pause.setOnFinished(ev -> copyCodeBtn.setText("📋 Copy mã"));
        pause.play();
    }

    public void updateTurnInfo(String status, String wordDisplay, int seconds) {
        statusLabel.setText(status);
        setWordDisplayWithLengths(wordDisplay);
        timerLabel.setText(seconds + "s");
        timerBadge.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 20; -fx-border-color: #22C55E; -fx-border-width: 3; -fx-border-radius: 20; -fx-padding: 2 10 2 10;");
        timerLabel.setTextFill(Color.web("#15803D"));
    }

    public void updateTimerAndMask(int remainingSec, String mask, boolean isDrawer) {
        timerLabel.setText(remainingSec + "s");
        if (remainingSec <= 10) {
            timerBadge.setStyle("-fx-background-color: #FEF2F2; -fx-background-radius: 20; -fx-border-color: #EF4444; -fx-border-width: 3; -fx-border-radius: 20; -fx-padding: 2 10 2 10;");
            timerLabel.setTextFill(Color.web("#DC2626"));
        } else {
            timerBadge.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 20; -fx-border-color: #22C55E; -fx-border-width: 3; -fx-border-radius: 20; -fx-padding: 2 10 2 10;");
            timerLabel.setTextFill(Color.web("#15803D"));
        }
        if (!isDrawer && mask != null && !mask.isEmpty()) {
            setWordDisplayWithLengths(mask);
        }
    }

    private void setWordDisplayWithLengths(String raw) {
        if (raw == null || raw.isBlank()) {
            wordHintLabel.setText("");
            wordLengthsLabel.setText("");
            return;
        }
        // Format underscore masks spaced out cleanly for readability, plus compute word lengths e.g. (3, 5)
        String[] parts = raw.split(" ");
        StringBuilder formatted = new StringBuilder();
        StringBuilder lengths = new StringBuilder("(");
        boolean firstWord = true;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!firstWord) {
                formatted.append("   ");
                lengths.append(", ");
            }
            for (int i = 0; i < part.length(); i++) {
                if (i > 0) formatted.append(' ');
                formatted.append(part.charAt(i));
            }
            lengths.append(part.length());
            firstWord = false;
        }
        lengths.append(')');
        wordHintLabel.setText(formatted.toString());
        wordLengthsLabel.setText(firstWord ? "" : lengths.toString());
    }

    public void setRoomMeta(String code, boolean isPrivate, String badgeText) {
        this.currentRoomCode = code != null ? code : "";
        roomBadgeLabel.setText(badgeText);
        boolean showCopy = isPrivate && !this.currentRoomCode.isBlank();
        copyCodeBtn.setVisible(showCopy);
        copyCodeBtn.setManaged(showCopy);
    }

    public void setRoleHintText(String text) {
        roleHintLabel.setText(text != null ? text : "");
    }

    public Button getStartBtn() {
        return startBtn;
    }

    public Button getLeaveBtn() {
        return leaveBtn;
    }
}
