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
    private Timeline countdownTimeline;

    public WordChoiceDialog(List<String> words, int chooseSeconds) {
        int seconds = chooseSeconds > 0 ? chooseSeconds : 10;
        setTitle("Lượt của bạn!");
        setHeaderText("Hãy chọn 1 từ khóa dưới đây để vẽ:");

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.CENTER);

        Label instruction = new Label("Chọn nhanh trước khi hết " + seconds + " giây:");
        HBox buttonBox = new HBox(12);
        buttonBox.setAlignment(Pos.CENTER);

        for (int i = 0; i < words.size(); i++) {
            final int index = i;
            Button btn = new Button(words.get(i));
            btn.setFont(Font.font("System", FontWeight.BOLD, 14));
            btn.setPrefWidth(130);
            btn.setPrefHeight(40);
            btn.setOnAction(e -> {
                stopCountdown();
                setResult(index);
                close();
            });
            buttonBox.getChildren().add(btn);
        }

        content.getChildren().addAll(instruction, buttonBox);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().lookupButton(ButtonType.CLOSE).setVisible(false);

        final int[] remaining = {seconds};
        countdownTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remaining[0]--;
            if (remaining[0] > 0) {
                instruction.setText("Chọn nhanh trước khi hết " + remaining[0] + " giây:");
            } else {
                stopCountdown();
                close();
            }
        }));
        countdownTimeline.setCycleCount(seconds);
        countdownTimeline.play();

        setOnCloseRequest(e -> stopCountdown());
        setOnHidden(e -> stopCountdown());
    }

    private void stopCountdown() {
        if (countdownTimeline != null) {
            countdownTimeline.stop();
        }
    }

    /**
     * Đóng hộp thoại và dừng bộ đếm ngược Timeline ngay lập tức:
     * Được ClientController gọi chủ động khi server chốt từ (TURN_START),
     * kết thúc lượt (TURN_END), chuyển phòng hoặc mất kết nối.
     */
    public void closeAndStop() {
        stopCountdown();
        close();
    }
}