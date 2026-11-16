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
    private final TextField chatInput;

    public GameView(ClientController controller) {
        this.topStatusBar = new TopStatusBar(controller);
        this.playerListPanel = new PlayerListPanel();
        this.drawingCanvas = new DrawingCanvas(controller);

        setTop(topStatusBar);
        setLeft(playerListPanel);
        setCenter(drawingCanvas);

        VBox rightPanel = new VBox(8);
        rightPanel.setPadding(new Insets(10));
        rightPanel.setPrefWidth(270);
        rightPanel.setMinWidth(220);
        rightPanel.setStyle("-fx-background-color: #F8F9F9; -fx-border-color: #D5DBDB; -fx-border-width: 0 0 0 1;");

        Label chatTitle = new Label("Chat & Đoán từ:");
        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        VBox.setVgrow(chatArea, Priority.ALWAYS);

        chatInput = new TextField();
        chatInput.setPromptText("Nhập từ đoán hoặc lệnh (/start)...");
        HBox.setHgrow(chatInput, Priority.ALWAYS);

        Button sendBtn = new Button("Gửi");
        Runnable submitAction = () -> {
            String text = chatInput.getText();
            if (text != null && !text.isBlank()) {
                controller.sendChat(text);
                chatInput.clear();
            }
        };
        sendBtn.setOnAction(e -> submitAction.run());
        chatInput.setOnAction(e -> submitAction.run());

        HBox inputBox = new HBox(6, chatInput, sendBtn);
        rightPanel.getChildren().addAll(chatTitle, chatArea, inputBox);
        setRight(rightPanel);
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

    public void appendChatMessage(String message) {
        chatArea.appendText(message + "\n");
    }
}