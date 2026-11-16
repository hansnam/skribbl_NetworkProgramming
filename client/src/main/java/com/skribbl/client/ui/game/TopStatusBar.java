package com.skribbl.client.ui.game;

import com.skribbl.client.controller.ClientController;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public class TopStatusBar extends HBox {
    private final Label roomBadgeLabel;
    private final Button copyCodeBtn;
    private final Label turnLabel;
    private final Label wordMaskLabel;
    private final Label timerLabel;
    private final Button startGameBtn;
    private final Button leaveRoomBtn;

    private String currentRoomCode = "";

    public TopStatusBar(ClientController controller) {
        setSpacing(10);
        setPadding(new Insets(10, 14, 10, 14));
        setAlignment(Pos.CENTER_LEFT);
        setStyle("-fx-background-color: #2C3E50;");

        roomBadgeLabel = new Label("Phòng: ---");
        roomBadgeLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
        roomBadgeLabel.setStyle("-fx-text-fill: #1ABC9C; -fx-background-color: #1A252F; -fx-padding: 4 8 4 8; -fx-background-radius: 4;");

        copyCodeBtn = new Button("Copy mã");
        copyCodeBtn.setStyle("-fx-background-color: #34495E; -fx-text-fill: #ECF0F1; -fx-font-size: 11px;");
        copyCodeBtn.setVisible(false);
        copyCodeBtn.setManaged(false);
        copyCodeBtn.setOnAction(e -> {
            if (!currentRoomCode.isEmpty()) {
                ClipboardContent content = new ClipboardContent();
                content.putString(currentRoomCode);
                Clipboard.getSystemClipboard().setContent(content);
                copyCodeBtn.setText("Đã copy!");
            }
        });

        turnLabel = new Label("Đang chờ bắt đầu...");
        turnLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
        turnLabel.setStyle("-fx-text-fill: #ECF0F1;");

        Region leftSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);

        wordMaskLabel = new Label("_ _ _ _ _");
        wordMaskLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 20));
        wordMaskLabel.setStyle("-fx-text-fill: #F1C40F;");

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        startGameBtn = new Button("Bắt đầu game");
        startGameBtn.setStyle("-fx-background-color: #2ECC71; -fx-text-fill: white; -fx-font-weight: bold;");
        startGameBtn.setVisible(false);
        startGameBtn.setManaged(false);
        startGameBtn.setOnAction(e -> {
            if (controller != null) controller.startGame();
        });

        timerLabel = new Label("60s");
        timerLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        timerLabel.setStyle("-fx-text-fill: #2ECC71;");

        leaveRoomBtn = new Button("Rời phòng");
        leaveRoomBtn.setStyle("-fx-background-color: #E74C3C; -fx-text-fill: white; -fx-font-size: 12px;");
        leaveRoomBtn.setOnAction(e -> {
            if (controller != null) controller.leaveRoom();
        });

        getChildren().addAll(
                roomBadgeLabel, copyCodeBtn, turnLabel,
                leftSpacer, wordMaskLabel, rightSpacer,
                startGameBtn, timerLabel, leaveRoomBtn
        );
    }

    public void updateRoomHeader(String roomCode, boolean isHost, boolean isWaiting, int playerCount) {
        this.currentRoomCode = roomCode != null ? roomCode : "";
        boolean hasCode = !this.currentRoomCode.isEmpty();
        roomBadgeLabel.setText(hasCode ? "Phòng: " + this.currentRoomCode : "Ngoại tuyến");
        copyCodeBtn.setText("Copy mã");
        copyCodeBtn.setVisible(hasCode);
        copyCodeBtn.setManaged(hasCode);

        boolean canStart = isHost && isWaiting && playerCount >= 2;
        startGameBtn.setVisible(canStart);
        startGameBtn.setManaged(canStart);
    }

    public void updateTurnInfo(String roleText, String wordDisplay, int seconds) {
        turnLabel.setText(roleText);
        if (wordDisplay != null && !wordDisplay.isBlank()) {
            wordMaskLabel.setText(wordDisplay);
        }
        updateTimerColor(seconds);
    }

    public void updateTimerAndMask(int remainingSec, String wordMask, boolean isDrawer) {
        updateTimerColor(remainingSec);
        if (!isDrawer && wordMask != null && !wordMask.isBlank()) {
            wordMaskLabel.setText(wordMask);
        }
    }

    private void updateTimerColor(int sec) {
        timerLabel.setText(sec + "s");
        if (sec <= 10) {
            timerLabel.setStyle("-fx-text-fill: #E74C3C;");
        } else {
            timerLabel.setStyle("-fx-text-fill: #2ECC71;");
        }
    }
}