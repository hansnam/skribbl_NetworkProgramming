package com.skribbl.client.ui.game;

import com.skribbl.client.controller.ClientController;
import com.skribbl.client.ui.canvas.DrawingCanvas;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class GameView extends BorderPane {
    private final TopStatusBar topStatusBar;
    private final PlayerListPanel playerListPanel;
    private final DrawingCanvas drawingCanvas;
    private final TextArea chatArea;

    public GameView(ClientController controller) {
        this.topStatusBar = new TopStatusBar();
        this.playerListPanel = new PlayerListPanel();
        this.drawingCanvas = new DrawingCanvas(controller);
        this.chatArea = new TextArea();

        setMinSize(0, 0);
        setTop(topStatusBar);
        setLeft(playerListPanel);
        setCenter(drawingCanvas);
        setRight(createChatPanel(controller));
    }

    private VBox createChatPanel(ClientController controller) {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));
        panel.setPrefWidth(260);
        panel.setMinWidth(200);
        panel.setStyle("-fx-background-color: #ECF0F1; -fx-border-color: #BDC3C7; -fx-border-width: 0 0 0 1;");

        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        VBox.setVgrow(chatArea, Priority.ALWAYS);

        TextField inputField = new TextField();
        inputField.setPromptText("Nhập từ đoán hoặc lệnh (/create)...");

        Button sendBtn = new Button("Gửi");
        Runnable sendAction = () -> {
            String msg = inputField.getText();
            if (!msg.isBlank()) {
                controller.sendChat(msg);
                inputField.clear();
            }
        };

        sendBtn.setOnAction(e -> sendAction.run());
        inputField.setOnAction(e -> sendAction.run());

        HBox inputBox = new HBox(5, inputField, sendBtn);
        HBox.setHgrow(inputField, Priority.ALWAYS);

        panel.getChildren().addAll(new Label("Chat & Đoán từ:"), chatArea, inputBox);
        return panel;
    }

    public void appendChatMessage(String message) {
        chatArea.appendText(message + "\n");
    }

    public TopStatusBar getTopStatusBar() {
        return topStatusBar;
    }

    public PlayerListPanel getPlayerListPanel() {
        return playerListPanel;
    }

    public DrawingCanvas getDrawingCanvas() {
        return drawingCanvas;
    }
}