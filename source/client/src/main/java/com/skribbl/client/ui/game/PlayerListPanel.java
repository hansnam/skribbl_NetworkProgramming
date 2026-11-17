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

    /**
     * Cập nhật danh sách người chơi và thứ hạng điểm số trên bảng PlayerListPanel.
     * Áp dụng thuật toán xếp hạng chuẩn (Standard Competition Ranking - "1224 ranking"):
     * Người có điểm bằng nhau sẽ nhận cùng một thứ hạng (ví dụ: điểm 500/500/200 sẽ xếp hạng là 1/1/3).
     *
     * @param players Danh sách snapshot người chơi nhận được từ server (List<PlayerDto>).
     */
    public void updatePlayers(List<PlayerDto> players) {
        listContainer.getChildren().clear();
        if (players == null || players.isEmpty()) return;

        // Sắp xếp: Điểm giảm dần (score reversed), nếu bằng điểm thì sắp theo playerId tăng dần
        List<PlayerDto> sorted = new java.util.ArrayList<>(players);
        sorted.sort(Comparator.comparingInt(PlayerDto::score).reversed()
                .thenComparing(PlayerDto::id));

        // Tính thứ hạng: Hạng theo vị trí đầu của nhóm cùng điểm (500/500/200 -> 1/1/3)
        int rank = 0;
        int previousScore = Integer.MIN_VALUE;
        for (int i = 0; i < sorted.size(); i++) {
            PlayerDto player = sorted.get(i);
            // Nếu là người đầu tiên hoặc điểm số khác người liền trước -> cập nhật hạng mới = index + 1
            if (i == 0 || player.score() != previousScore) {
                rank = i + 1;
            }
            listContainer.getChildren().add(createPlayerRow(rank, player));
            previousScore = player.score();
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