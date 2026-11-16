package com.skribbl.client;

import com.skribbl.client.controller.ClientController;
import com.skribbl.client.ui.game.GameView;
import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.UUID;

public class ClientMain extends Application {
    private static final String SERVER_HOST = "127.0.0.1";
    private static final int SERVER_PORT = 8888;

    private ClientController controller;

    @Override
    public void start(Stage primaryStage) {
        String nickname = "Player_" + UUID.randomUUID().toString().substring(0, 4);

        controller = new ClientController(nickname);
        GameView gameView = new GameView(controller);
        controller.setGameView(gameView);

        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double initialWidth = Math.min(1150, screenBounds.getWidth() * 0.85);
        double initialHeight = Math.min(680, screenBounds.getHeight() * 0.85);

        Scene scene = new Scene(gameView, initialWidth, initialHeight);
        primaryStage.setTitle("Skribbl Client - " + nickname);
        primaryStage.setScene(scene);

        primaryStage.setResizable(true);
        primaryStage.setMinWidth(760);
        primaryStage.setMinHeight(480);

        primaryStage.setOnCloseRequest(event -> controller.shutdown());
        primaryStage.show();

        try {
            controller.connectToServer(SERVER_HOST, SERVER_PORT);
        } catch (Exception e) {
            gameView.appendChatMessage("[Lỗi] Không thể kết nối tới Server: " + e.getMessage());
            gameView.appendChatMessage("[Hệ thống] Đã bật chế độ vẽ thử ngoại tuyến.");
            gameView.getDrawingCanvas().setDrawingEnabled(true);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}