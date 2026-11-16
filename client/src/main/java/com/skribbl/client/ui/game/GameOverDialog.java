package com.skribbl.client.ui.game;

import com.skribbl.common.model.PlayerDto;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Comparator;
import java.util.List;

public class GameOverDialog extends Dialog<Boolean> {

    public GameOverDialog(List<PlayerDto> players) {
        setTitle("Tổng kết ván đấu");
        setHeaderText(null);

        ButtonType lobbyBtn = new ButtonType("Về sảnh chờ", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType stayBtn = new ButtonType("Ở lại phòng chơi tiếp", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(lobbyBtn, stayBtn);

        VBox root = new VBox(14);
        root.setPadding(new Insets(22));
        root.setAlignment(Pos.CENTER);
        root.setPrefWidth(380);
        root.setStyle("-fx-background-color: #2C3E50;");

        Label title = new Label("BẢNG XẾP HẠNG CHUNG CUỘC");
        title.setFont(Font.font("System", FontWeight.BOLD, 20));
        title.setStyle("-fx-text-fill: #F1C40F;");
        root.getChildren().add(title);

        if (players != null && !players.isEmpty()) {
            List<PlayerDto> sorted = players.stream()
                    .sorted(Comparator.comparingInt(PlayerDto::score).reversed())
                    .toList();

            int rank = 1;
            for (PlayerDto p : sorted) {
                HBox row = new HBox(10);
                row.setPadding(new Insets(10, 14, 10, 14));
                row.setAlignment(Pos.CENTER_LEFT);

                String medal = switch (rank) {
                    case 1 -> "Hạng 1";
                    case 2 -> "Hạng 2";
                    case 3 -> "Hạng 3";
                    default -> "#" + rank;
                };
                String bg = switch (rank) {
                    case 1 -> "#F1C40F";
                    case 2 -> "#BDC3C7";
                    case 3 -> "#E67E22";
                    default -> "#ECF0F1";
                };

                row.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 6;");

                Label rankLbl = new Label(medal);
                rankLbl.setFont(Font.font("System", FontWeight.BOLD, 13));

                Label nameLbl = new Label(p.nickname());
                nameLbl.setFont(Font.font("System", FontWeight.BOLD, 14));

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                Label scoreLbl = new Label(p.score() + " điểm");
                scoreLbl.setFont(Font.font("System", FontWeight.BOLD, 14));

                row.getChildren().addAll(rankLbl, nameLbl, spacer, scoreLbl);
                root.getChildren().add(row);
                rank++;
            }
        }

        getDialogPane().setContent(root);
        setResultConverter(btn -> btn == stayBtn);
    }
}