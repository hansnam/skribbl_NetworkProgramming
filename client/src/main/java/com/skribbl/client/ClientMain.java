package com.skribbl.client;

import com.skribbl.client.controller.ClientController;
import com.skribbl.client.ui.game.GameView;
import com.skribbl.client.ui.lobby.LobbyView;
import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

public class ClientMain extends Application {
    private ClientController controller;

    @Override
    public void start(Stage primaryStage) {
        controller = new ClientController("Player");
        GameView gameView = new GameView(controller);
        controller.setGameView(gameView);

        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double initialWidth = Math.min(1150, screenBounds.getWidth() * 0.85);
        double initialHeight = Math.min(680, screenBounds.getHeight() * 0.85);

        LobbyView initialLobby = new LobbyView(controller, () -> {
            gameView.getDrawingCanvas().setDrawingEnabled(true);
            gameView.appendChatMessage("[Hệ thống] Đang ở chế độ vẽ thử ngoại tuyến.");
            primaryStage.getScene().setRoot(gameView);
        });

        Scene scene = new Scene(initialLobby, initialWidth, initialHeight);

        controller.setOnRoomJoinedCallback(room -> {
            primaryStage.setTitle("Skribbl Client - " + controller.getNickname() + " | Phòng: " + room.roomCode());
            scene.setRoot(gameView);
        });

        controller.setOnRoomLeftCallback(() -> {
            primaryStage.setTitle("Skribbl Client");
            scene.setRoot(new LobbyView(controller, () -> {
                gameView.getDrawingCanvas().setDrawingEnabled(true);
                scene.setRoot(gameView);
            }));
        });

        primaryStage.setTitle("Skribbl Client");
        primaryStage.setScene(scene);
        primaryStage.setResizable(true);
        primaryStage.setMinWidth(760);
        primaryStage.setMinHeight(480);

        primaryStage.setOnCloseRequest(event -> controller.shutdown());
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}