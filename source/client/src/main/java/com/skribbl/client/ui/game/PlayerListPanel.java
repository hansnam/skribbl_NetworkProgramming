package com.skribbl.client.ui.game;

import com.skribbl.client.ui.avatar.AvatarView;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skribbl.io-style left player leaderboard with mini cartoon avatars,
 * rank badges, host crown, drawer brush indicator, and per-turn (+points) delta badges.
 */
public class PlayerListPanel extends VBox {
    private final VBox listContainer;
    private final Label headerLabel;
    private Map<String, Integer> turnDeltas = new HashMap<>();

    public PlayerListPanel() {
        setPadding(new Insets(10));
        setSpacing(8);
        setPrefWidth(235);
        setMinWidth(195);
        setStyle("-fx-background-color: #F1F5F9; -fx-border-color: #CBD5E1; -fx-border-width: 0 1.5 0 0;");

        headerLabel = new Label("🏆 Bảng xếp hạng (0)");
        headerLabel.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 13.5));
        headerLabel.setStyle("-fx-text-fill: #1E293B;");

        listContainer = new VBox(6);
        ScrollPane scrollPane = new ScrollPane(listContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(headerLabel, scrollPane);
    }

    public void setTurnDeltas(Map<String, Integer> deltas) {
        this.turnDeltas = deltas != null ? new HashMap<>(deltas) : new HashMap<>();
    }

    public void recordTurnDelta(String playerId, int points) {
        if (playerId != null && points > 0) {
            turnDeltas.merge(playerId, points, Integer::sum);
        }
    }

    public void clearTurnDeltas() {
        turnDeltas.clear();
    }

    public void updatePlayers(List<PlayerDto> players) {
        listContainer.getChildren().clear();
        if (players == null || players.isEmpty()) {
            headerLabel.setText("🏆 Bảng xếp hạng (0)");
            return;
        }
        headerLabel.setText("🏆 Người chơi (" + players.size() + "/6)");

        List<PlayerDto> sorted = new java.util.ArrayList<>(players);
        sorted.sort(Comparator.comparingInt(PlayerDto::score).reversed()
                .thenComparing(PlayerDto::id));

        int rank = 0;
        int previousScore = Integer.MIN_VALUE;
        for (int i = 0; i < sorted.size(); i++) {
            PlayerDto player = sorted.get(i);
            if (i == 0 || player.score() != previousScore) {
                rank = i + 1;
            }
            listContainer.getChildren().add(createPlayerRow(rank, player, i % 2 == 1));
            previousScore = player.score();
        }
    }

    private HBox createPlayerRow(int rank, PlayerDto p, boolean alternate) {
        HBox row = new HBox(8);
        row.setPadding(new Insets(7, 9, 7, 9));
        row.setAlignment(Pos.CENTER_LEFT);

        String bgColor = p.hasGuessed() ? "#DCFCE7" : (p.isDrawer() ? "#EFF6FF" : (alternate ? "#F8FAFC" : "#FFFFFF"));
        String borderColor = p.isDrawer() ? "#3B82F6" : (p.hasGuessed() ? "#22C55E" : "#E2E8F0");
        row.setStyle("-fx-background-color: " + bgColor + "; -fx-border-color: " + borderColor
                + "; -fx-border-width: 1.5; -fx-border-radius: 9; -fx-background-radius: 9;");

        AvatarView avatar = new AvatarView(p.avatar(), 34);

        VBox infoBox = new VBox(2);
        String rankMedal = switch (rank) {
            case 1 -> "#1 ";
            case 2 -> "#2 ";
            case 3 -> "#3 ";
            default -> "#" + rank + " ";
        };
        StringBuilder nameText = new StringBuilder(rankMedal).append(p.nickname());
        if (p.isHost()) nameText.append(" 👑");

        Label nameLabel = new Label(nameText.toString());
        nameLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12.5));
        nameLabel.setStyle("-fx-text-fill: #0F172A;");

        HBox scoreRow = new HBox(5);
        scoreRow.setAlignment(Pos.CENTER_LEFT);
        Label scoreLabel = new Label(p.score() + " điểm");
        scoreLabel.setStyle("-fx-text-fill: #475569; -fx-font-size: 11.5px; -fx-font-weight: bold;");
        scoreRow.getChildren().add(scoreLabel);

        Integer delta = turnDeltas.get(p.id());
        if (delta != null && delta > 0) {
            Label deltaLbl = new Label("(+" + delta + ")");
            deltaLbl.setStyle("-fx-text-fill: #15803D; -fx-font-size: 11px; -fx-font-weight: bold;");
            scoreRow.getChildren().add(deltaLbl);
        }

        infoBox.getChildren().addAll(nameLabel, scoreRow);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label statusIcon = new Label(p.isDrawer() ? "✏️" : (p.hasGuessed() ? "✅" : ""));
        statusIcon.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        row.getChildren().addAll(avatar, infoBox, spacer, statusIcon);
        return row;
    }
}
