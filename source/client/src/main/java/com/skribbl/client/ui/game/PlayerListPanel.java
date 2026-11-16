package com.skribbl.client.ui.game;

import com.skribbl.common.model.PlayerDto;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Comparator;
import java.util.List;

public class PlayerListPanel extends VBox {
    private final VBox listContainer;

    public PlayerListPanel() {
        setPadding(new Insets(10));
        setSpacing(8);
        setPrefWidth(220);
        setMinWidth(180);
        setStyle("-fx-background-color: #ECF0F1; -fx-border-color: #BDC3C7; -fx-border-width: 0 1 0 0;");

        Label title = new Label("Người chơi & Điểm:");
        title.setFont(Font.font("System", FontWeight.BOLD, 14));

        listContainer = new VBox(6);
        ScrollPane scrollPane = new ScrollPane(listContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(title, scrollPane);
    }

    public void updatePlayers(List<PlayerDto> players) {
        listContainer.getChildren().clear();
        if (players == null || players.isEmpty()) return;

        // Sắp xếp điểm từ cao xuống thấp
        List<PlayerDto> sorted = players.stream()
                .sorted(Comparator.comparingInt(PlayerDto::score).reversed())
                .toList();

        int rank = 1;
        for (PlayerDto p : sorted) {
            listContainer.getChildren().add(createPlayerRow(rank++, p));
        }
    }

    private HBox createPlayerRow(int rank, PlayerDto p) {
        HBox row = new HBox(8);
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setAlignment(Pos.CENTER_LEFT);

        // Tô màu nền xanh nhạt nếu người chơi đã đoán đúng trong lượt
        String bgColor = p.hasGuessed() ? "#D5F5E3" : "#FFFFFF";
        String borderColor = p.isDrawer() ? "#3498DB" : "#DCDCDC";
        row.setStyle("-fx-background-color: " + bgColor + "; -fx-border-color: " + borderColor
                + "; -fx-border-radius: 5; -fx-background-radius: 5;");

        VBox infoBox = new VBox(2);
        StringBuilder nameText = new StringBuilder("#" + rank + " " + p.nickname());
        if (p.isHost()) nameText.append(" [Chủ]");
        if (p.isDrawer()) nameText.append(" [Vẽ]");

        Label nameLabel = new Label(nameText.toString());
        nameLabel.setFont(Font.font("System", FontWeight.BOLD, 13));

        Label scoreLabel = new Label(p.score() + " điểm");
        scoreLabel.setStyle("-fx-text-fill: #555555; -fx-font-size: 12px;");

        infoBox.getChildren().addAll(nameLabel, scoreLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label statusIcon = new Label(p.isDrawer() ? "✏️" : (p.hasGuessed() ? "✅" : ""));
        row.getChildren().addAll(infoBox, spacer, statusIcon);
        return row;
    }
}