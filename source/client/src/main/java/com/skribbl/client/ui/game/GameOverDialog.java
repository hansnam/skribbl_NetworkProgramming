package com.skribbl.client.ui.game;

import com.skribbl.common.dto.GameOverDto;
import com.skribbl.common.model.PlayerDto;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Hộp thoại hiển thị bảng kết quả khi ván đấu kết thúc (GAME_OVER):
 * - Hiển thị tên người/nhóm người chiến thắng có điểm số cao nhất.
 * - Tái sử dụng PlayerListPanel để hiển thị toàn bộ bảng xếp hạng điểm số cuối cùng.
 * - Nút "Đóng kết quả" để đóng cửa sổ (không làm người chơi rời phòng).
 */
public final class GameOverDialog extends Stage {
    /**
     * Khởi tạo cửa sổ GameOverDialog.
     *
     * @param owner Cửa sổ cha sở hữu dialog này (Window).
     * @param dto   Dữ liệu kết thúc ván từ server gồm danh sách winners và players (GameOverDto).
     */
    public GameOverDialog(Window owner, GameOverDto dto) {
        if (owner != null) {
            initOwner(owner);
        }
        initModality(Modality.NONE); // Không khóa cửa sổ chính
        setTitle("Kết quả ván đấu");

        // Gom danh sách tên những người chiến thắng
        List<String> names = new ArrayList<>();
        for (PlayerDto winner : dto.winners()) {
            names.add(winner.nickname());
        }
        Label title = new Label("Người chiến thắng: " + String.join(", ", names));
        title.setWrapText(true);

        // Hiển thị bảng điểm tổng kết bằng PlayerListPanel
        PlayerListPanel scores = new PlayerListPanel();
        scores.updatePlayers(dto.players());

        // Nút bấm đóng dialog
        Button closeButton = new Button("Đóng kết quả");
        closeButton.setOnAction(event -> close());

        // Thiết lập layout BorderPane
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));
        root.setTop(title);
        root.setCenter(scores);
        root.setBottom(closeButton);
        setScene(new Scene(root, 560, 380));
    }
}
