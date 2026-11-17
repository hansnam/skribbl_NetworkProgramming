package com.skribbl.client;

import com.skribbl.client.controller.ClientController;
import com.skribbl.client.ui.game.GameView;
import com.skribbl.client.ui.lobby.LobbyView;
import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Điểm khởi động JavaFX do TV3 quản lý. P06 chỉ ghép LobbyView và callback vào/rời phòng;
 * giữ một controller và một Scene để các view nhận cùng trạng thái kết nối.
 */
public class ClientMain extends Application {
    private ClientController controller;

    @Override
    public void start(Stage primaryStage) {
        // JavaFX gọi trên FX thread; controller được dùng chung cho cả sảnh và màn hình chơi.
        controller = new ClientController("Player");
        GameView gameView = new GameView(controller);
        controller.setGameView(gameView);

        // Giữ cách tính kích thước theo màn hình của TV3, không đổi layout theo mẫu tài liệu.
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double initialWidth = Math.min(1150, screenBounds.getWidth() * 0.85);
        double initialHeight = Math.min(680, screenBounds.getHeight() * 0.85);

        LobbyView initialLobby = new LobbyView(controller, () -> {
            // P08/TV3: enterOfflineMode phải kiểm tra trạng thái online trước khi bật vẽ thử.
            // Callback chỉ đổi nội dung cửa sổ, không tự mở một kết nối khác.
            if (controller.enterOfflineMode()) {
                primaryStage.getScene().setRoot(gameView);
            }
        });

        // API P08/TV3: giữ đúng view đang hiển thị để báo lỗi và mở/khóa controls.
        controller.setLobbyView(initialLobby);
        Scene scene = new Scene(initialLobby, initialWidth, initialHeight);
        // Controller gọi sau ROOM_UPDATE xác nhận đã vào phòng, không phải ngay khi nối TCP.
        controller.setOnRoomJoinedCallback(room -> {
            primaryStage.setTitle("Skribbl Client - " + controller.getNickname());
            scene.setRoot(gameView);
        });

        // Dùng lại sảnh cũ để controller không giữ reference tới một view đã bị thay thế.
        // P08/TV3 phải gọi callback sau LEAVE ACK hoặc disconnect, không gọi ngay sau send.
        controller.setOnRoomLeftCallback(() -> {
            primaryStage.setTitle("Skribbl Client");
            scene.setRoot(initialLobby);
        });

        primaryStage.setTitle("Skribbl Client");
        primaryStage.setScene(scene);
        primaryStage.setResizable(true);
        primaryStage.setMinWidth(760);
        primaryStage.setMinHeight(480);

        // Người dùng đóng cửa sổ: yêu cầu controller dọn kết nối/worker của mình.
        primaryStage.setOnCloseRequest(event -> controller.shutdown());
        primaryStage.show();
    }

    @Override
    public void stop() {
        // JavaFX gọi khi ứng dụng dừng, kể cả đường dừng không đi qua nút đóng cửa sổ.
        // Controller phải cho phép shutdown lặp vì onCloseRequest có thể đã gọi trước đó.
        if (controller != null) {
            controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
