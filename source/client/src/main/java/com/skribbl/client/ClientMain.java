package com.skribbl.client;

import com.skribbl.client.controller.ClientController;
import com.skribbl.client.ui.game.GameView;
import com.skribbl.client.ui.lobby.LobbyView;
import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.net.URL;

/**
 * Điểm khởi động JavaFX: khởi tạo sảnh chờ, màn hình chơi và các callback vào/rời phòng.
 * Dùng chung một controller và một Scene để giữ trạng thái kết nối khi chuyển màn hình.
 */
public class ClientMain extends Application {
    private ClientController controller;

    @Override
    public void start(Stage primaryStage) {
        controller = new ClientController("Player");
        GameView gameView = new GameView(controller);
        controller.setGameView(gameView);

        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double initialWidth = Math.min(1220, screenBounds.getWidth() * 0.88);
        double initialHeight = Math.min(720, screenBounds.getHeight() * 0.88);

        LobbyView initialLobby = new LobbyView(controller, () -> {
            if (controller.enterOfflineMode()) {
                primaryStage.getScene().setRoot(gameView);
            }
        });

        controller.setLobbyView(initialLobby);
        Scene scene = new Scene(initialLobby, initialWidth, initialHeight);
        URL cssUrl = ClientMain.class.getResource("/com/skribbl/client/styles.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        controller.setOnRoomJoinedCallback(room -> {
            primaryStage.setTitle("skribbl.io Clone — " + controller.getNickname() + " [Phòng " + room.roomCode() + "]");
            scene.setRoot(gameView);
        });

        controller.setOnRoomLeftCallback(() -> {
            primaryStage.setTitle("skribbl.io Clone — Multiplayer Drawing & Guessing");
            scene.setRoot(initialLobby);
        });

        primaryStage.setTitle("skribbl.io Clone — Multiplayer Drawing & Guessing");
        primaryStage.setScene(scene);
        primaryStage.setResizable(true);
        primaryStage.setMinWidth(820);
        primaryStage.setMinHeight(520);

        primaryStage.setOnCloseRequest(event -> controller.shutdown());
        primaryStage.show();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
