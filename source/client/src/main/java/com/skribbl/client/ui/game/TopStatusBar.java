package com.skribbl.client.ui.game;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public class TopStatusBar extends HBox {
    private final Label statusLabel;
    private final Label wordHintLabel;
    private final Label timerLabel;

    public TopStatusBar() {
        setPadding(new Insets(10, 20, 10, 20));
        setAlignment(Pos.CENTER_LEFT);
        setStyle("-fx-background-color: #2C3E50;");

        statusLabel = new Label("Đang chờ bắt đầu...");
        statusLabel.setTextFill(javafx.scene.paint.Color.WHITE);
        statusLabel.setFont(Font.font("System", FontWeight.BOLD, 14));

        wordHintLabel = new Label("_ _ _ _ _");
        wordHintLabel.setTextFill(javafx.scene.paint.Color.web("#F1C40F"));
        wordHintLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 20));

        timerLabel = new Label("60s");
        timerLabel.setTextFill(javafx.scene.paint.Color.web("#2ECC71"));
        timerLabel.setFont(Font.font("System", FontWeight.BOLD, 16));

        Region leftSpacer = new Region();
        Region rightSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        getChildren().addAll(statusLabel, leftSpacer, wordHintLabel, rightSpacer, timerLabel);
    }

    public void updateTurnInfo(String status, String wordDisplay, int seconds) {
        statusLabel.setText(status);
        wordHintLabel.setText(wordDisplay);
        timerLabel.setText(seconds + "s");
        timerLabel.setTextFill(javafx.scene.paint.Color.web("#2ECC71"));
    }

    public void updateTimerAndMask(int remainingSec, String mask, boolean isDrawer) {
        timerLabel.setText(remainingSec + "s");
        if (remainingSec <= 10) {
            timerLabel.setTextFill(javafx.scene.paint.Color.web("#E74C3C"));
        }
        if (!isDrawer && mask != null && !mask.isEmpty()) {
            wordHintLabel.setText(mask);
        }
    }
}