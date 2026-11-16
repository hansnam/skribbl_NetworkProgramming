package com.skribbl.client.ui.game;

import com.skribbl.common.model.PlayerDto;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Comparator;
import java.util.List;

public class PlayerListPanel extends VBox {
    private final VBox listContainer;

    public PlayerListPanel() {
        setPrefWidth(210);
        setMinWidth(180);
        setStyle("-fx-background-color: #F4F6F7; -fx-border-color: #D5DBDB; -fx-border-width: 0 1 0 0;");

        Label header = new Label("Người chơi & Điểm:");
        header.setFont(Font.font("System", FontWeight.BOLD, 13));
        header.setPadding(new Insets(10, 12, 8, 12));

        listContainer = new VBox(6);
        listContainer.setPadding(new Insets(4, 8, 8, 8));

        ScrollPane scrollPane = new ScrollPane(listContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(header, scrollPane);
    }

    public void updatePlayers(List<PlayerDto> players) {
        listContainer.getChildren().clear();
        if (players == null || players.isEmpty()) return;

        List<PlayerDto> sorted = players.stream()
                .sorted(Comparator.comparingInt(PlayerDto::score).reversed())
                .toList();

        int rank = 1;
        for (PlayerDto p : sorted) {
            HBox card = new HBox(8);
            card.setPadding(new Insets(8, 10, 8, 10));
            card.setAlignment(Pos.CENTER_LEFT);

            String bgColor = p.hasGuessed() ? "#D4EFDF" : (p.isDrawer() ? "#FCF3CF" : "#FFFFFF");
            card.setStyle("-fx-background-color: " + bgColor + "; -fx-border-color: #D5DBDB; -fx-border-radius: 5; -fx-background-radius: 5;");

            Label rankLbl = new Label("#" + rank++);
            rankLbl.setFont(Font.font("System", FontWeight.BOLD, 12));
            rankLbl.setStyle("-fx-text-fill: #5D6D7E;");

            VBox infoBox = new VBox(2);
            HBox.setHgrow(infoBox, Priority.ALWAYS);

            String badge = p.isHost() ? " [Chủ]" : "";
            String role = p.isDrawer() ? " (Đang vẽ)" : (p.hasGuessed() ? " (Đã đoán)" : "");
            Label nameLbl = new Label(p.nickname() + badge + role);
            nameLbl.setFont(Font.font("System", FontWeight.BOLD, 12));

            Label scoreLbl = new Label(p.score() + " điểm");
            scoreLbl.setStyle("-fx-text-fill: #2E86C1; -fx-font-size: 11px;");

            infoBox.getChildren().addAll(nameLbl, scoreLbl);
            card.getChildren().addAll(rankLbl, infoBox);
            listContainer.getChildren().add(card);
        }
    }
}