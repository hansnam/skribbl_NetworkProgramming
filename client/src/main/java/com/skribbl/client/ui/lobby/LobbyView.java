package com.skribbl.client.ui.lobby;

import com.skribbl.client.controller.ClientController;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.UUID;

public class LobbyView extends VBox {
    private final TextField hostField;
    private final TextField portField;
    private final TextField nicknameField;
    private final ComboBox<Integer> roundsBox;
    private final ComboBox<Integer> timeBox;
    private final TextField roomCodeField;
    private final Label statusLabel;

    public LobbyView(ClientController controller, Runnable onOfflineMode) {
        setSpacing(15);
        setPadding(new Insets(30));
        setAlignment(Pos.CENTER);
        setStyle("-fx-background-color: #2C3E50;");

        Label title = new Label("SKRIBBL MULTIPLAYER");
        title.setFont(Font.font("System", FontWeight.BOLD, 28));
        title.setStyle("-fx-text-fill: #F1C40F;");

        VBox card = new VBox(12);
        card.setMaxWidth(420);
        card.setPadding(new Insets(25));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 10;");

        Label hostLbl = new Label("Địa chỉ IP Server & Port:");
        hostLbl.setFont(Font.font("System", FontWeight.BOLD, 13));

        hostField = new TextField("127.0.0.1");
        hostField.setPromptText("IP Server");
        HBox.setHgrow(hostField, Priority.ALWAYS);

        portField = new TextField("8888");
        portField.setPromptText("Port");
        portField.setPrefWidth(80);
        HBox serverBox = new HBox(8, hostField, portField);

        Label nameLbl = new Label("Tên hiển thị (Nickname):");
        nameLbl.setFont(Font.font("System", FontWeight.BOLD, 13));
        nicknameField = new TextField("Player_" + UUID.randomUUID().toString().substring(0, 4));

        roundsBox = new ComboBox<>();
        roundsBox.getItems().addAll(2, 3, 5, 8, 10);
        roundsBox.setValue(3);
        roundsBox.setPrefWidth(95);

        timeBox = new ComboBox<>();
        timeBox.getItems().addAll(30, 45, 60, 80, 120);
        timeBox.setValue(60);
        timeBox.setPrefWidth(95);

        HBox configBox = new HBox(10,
                new Label("Số vòng:"), roundsBox,
                new Label("Giây/lượt:"), timeBox
        );
        configBox.setAlignment(Pos.CENTER_LEFT);

        Button createBtn = new Button("Tạo phòng mới");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setPrefHeight(38);
        createBtn.setStyle("-fx-background-color: #2ECC71; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");

        Separator sep = new Separator();

        Label joinLbl = new Label("Hoặc nhập mã phòng (5 ký tự) để tham gia:");
        joinLbl.setFont(Font.font("System", FontWeight.BOLD, 13));

        roomCodeField = new TextField();
        roomCodeField.setPromptText("Ví dụ: A7K9P");
        HBox.setHgrow(roomCodeField, Priority.ALWAYS);

        Button joinBtn = new Button("Vào phòng");
        joinBtn.setPrefHeight(36);
        joinBtn.setPrefWidth(105);
        joinBtn.setStyle("-fx-background-color: #3498DB; -fx-text-fill: white; -fx-font-weight: bold;");

        HBox joinBox = new HBox(8, roomCodeField, joinBtn);

        Button offlineBtn = new Button("Vẽ thử ngoại tuyến (Không cần Server)");
        offlineBtn.setMaxWidth(Double.MAX_VALUE);
        offlineBtn.setStyle("-fx-background-color: #95A5A6; -fx-text-fill: white;");

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #E74C3C; -fx-font-size: 12px;");
        statusLabel.setWrapText(true);

        createBtn.setOnAction(e -> {
            if (connectIfNeeded(controller)) {
                int rounds = roundsBox.getValue() != null ? roundsBox.getValue() : 3;
                int seconds = timeBox.getValue() != null ? timeBox.getValue() : 60;
                statusLabel.setStyle("-fx-text-fill: #27AE60;");
                statusLabel.setText("Đang tạo phòng (" + rounds + " vòng, " + seconds + "s)...");
                controller.createRoom(rounds, seconds);
            }
        });

        joinBtn.setOnAction(e -> {
            String code = roomCodeField.getText().trim();
            if (code.isEmpty()) {
                statusLabel.setStyle("-fx-text-fill: #E74C3C;");
                statusLabel.setText("Vui lòng nhập mã phòng!");
                return;
            }
            if (connectIfNeeded(controller)) {
                statusLabel.setStyle("-fx-text-fill: #27AE60;");
                statusLabel.setText("Đang vào phòng " + code.toUpperCase() + "...");
                controller.joinRoom(code);
            }
        });

        offlineBtn.setOnAction(e -> onOfflineMode.run());

        card.getChildren().addAll(
                hostLbl, serverBox,
                nameLbl, nicknameField,
                configBox, createBtn,
                sep,
                joinLbl, joinBox,
                offlineBtn, statusLabel
        );

        getChildren().addAll(title, card);
    }

    private boolean connectIfNeeded(ClientController controller) {
        String host = hostField.getText().trim();
        String portStr = portField.getText().trim();
        String nick = nicknameField.getText().trim();

        if (host.isEmpty() || portStr.isEmpty() || nick.isEmpty()) {
            statusLabel.setStyle("-fx-text-fill: #E74C3C;");
            statusLabel.setText("Vui lòng nhập đầy đủ IP Server, Port và Nickname!");
            return false;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            statusLabel.setStyle("-fx-text-fill: #E74C3C;");
            statusLabel.setText("Port không hợp lệ (phải từ 1 đến 65535)!");
            return false;
        }

        try {
            controller.setNickname(nick);
            controller.connectToServer(host, port);
            return true;
        } catch (Exception ex) {
            statusLabel.setStyle("-fx-text-fill: #E74C3C;");
            statusLabel.setText("Lỗi kết nối Server (" + host + ":" + port + "): " + ex.getMessage());
            return false;
        }
    }
}