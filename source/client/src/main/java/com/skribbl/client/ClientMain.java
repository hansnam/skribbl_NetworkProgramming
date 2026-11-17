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
 * Điểm khởi động JavaFX: khởi tạo sảnh chờ, màn hình chơi và các callback vào/rời phòng.
 * Dùng chung một controller và một Scene để giữ trạng thái kết nối khi chuyển màn hình.
 */
public class ClientMain extends Application {
    private ClientController controller;

    @Override
    public void start(Stage primaryStage) {
        // JavaFX gọi trên FX thread; controller được dùng chung cho cả sảnh và màn hình chơi.
        controller = new ClientController("Player");
        GameView gameView = new GameView(controller);
        controller.setGameView(gameView);

        // Giới hạn cửa sổ ở 85% vùng màn hình khả dụng, tối đa 1150 x 680 pixel.
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double initialWidth = Math.min(1150, screenBounds.getWidth() * 0.85);
        double initialHeight = Math.min(680, screenBounds.getHeight() * 0.85);

        LobbyView initialLobby = new LobbyView(controller, () -> {
            // Chỉ mở màn hình vẽ thử khi controller xác nhận có thể chuyển sang ngoại tuyến.
            // Nếu vẫn đang kết nối server, giữ sảnh chờ để hiển thị lý do từ chối.
            if (controller.enterOfflineMode()) {
                primaryStage.getScene().setRoot(gameView);
            }
        });

        // Controller cập nhật thông báo và khóa/mở các ô nhập trên chính sảnh đang hiển thị.
        controller.setLobbyView(initialLobby);
        Scene scene = new Scene(initialLobby, initialWidth, initialHeight);
        // Controller gọi sau ROOM_UPDATE xác nhận đã vào phòng, không phải ngay khi nối TCP.
        controller.setOnRoomJoinedCallback(room -> {
            primaryStage.setTitle("Skribbl Client - " + controller.getNickname());
            scene.setRoot(gameView);
        });

        // Dùng lại sảnh cũ để controller không giữ reference tới một view đã bị thay thế.
        // Callback chạy sau khi server xác nhận rời phòng hoặc khi mất kết nối.
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
