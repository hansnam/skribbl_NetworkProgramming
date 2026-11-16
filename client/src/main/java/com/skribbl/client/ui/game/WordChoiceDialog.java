package com.skribbl.client.ui.game;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.List;

public class WordChoiceDialog extends Dialog<Integer> {
    private int remaining;
    private Timeline timeline;

    public WordChoiceDialog(List<String> choices, int chooseSeconds) {
        setTitle("Chọn từ khóa để vẽ");
        setHeaderText(null);
        getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        getDialogPane().lookupButton(ButtonType.CANCEL).setVisible(false);

        this.remaining = Math.max(3, chooseSeconds);
        Label countdownLabel = new Label("Chọn 1 từ khóa (" + remaining + "s):");
        countdownLabel.setFont(Font.font("System", FontWeight.BOLD, 15));

        HBox buttonsBox = new HBox(12);
        buttonsBox.setAlignment(Pos.CENTER);

        for (int i = 0; i < choices.size(); i++) {
            final int idx = i;
            Button btn = new Button(choices.get(i).toUpperCase());
            btn.setPrefSize(130, 44);
            btn.setStyle("-fx-background-color: #3498DB; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;");
            btn.setOnAction(e -> {
                stopTimer();
                setResult(idx);
                close();
            });
            buttonsBox.getChildren().add(btn);
        }

        VBox content = new VBox(15, countdownLabel, buttonsBox);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.CENTER);
        getDialogPane().setContent(content);

        timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remaining--;
            countdownLabel.setText("Chọn 1 từ khóa (" + remaining + "s):");
            if (remaining <= 0) {
                stopTimer();
                setResult(0);
                close();
            }
        }));
        timeline.setCycleCount(remaining);
        timeline.play();

        setOnCloseRequest(e -> stopTimer());
    }

    private void stopTimer() {
        if (timeline != null) {
            timeline.stop();
        }
    }
}