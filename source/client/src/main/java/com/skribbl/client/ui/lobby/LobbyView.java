package com.skribbl.client.ui.lobby;

import com.skribbl.client.controller.ClientController;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Locale;
import java.util.UUID;

/**
 * Sảnh chờ do TV2 bổ sung trên bố cục của TV3.
 * View kiểm tra dữ liệu và hiển thị trạng thái; ClientController phụ trách kết nối,
 * chờ CONNECT_ACK và gửi yêu cầu phòng theo phần tích hợp của TV3 ở P08.
 * Các handler và method cập nhật control chạy trên JavaFX Application Thread.
 */
public class LobbyView extends VBox {
    // Dùng lại các ô trên card hiện có, không dựng một màn hình kết nối khác.
    private final TextField hostField;
    private final TextField nicknameField;
    private final TextField roomCodeField;
    private final Label statusLabel;
    private final ClientController controller;
    // Chỉ cấu hình phòng riêng khi tạo; server vẫn kiểm tra giới hạn lần cuối.
    private final Spinner<Integer> rounds = new Spinner<>(1, 10, 3);
    private final Spinner<Integer> seconds = new Spinner<>(15, 180, 60, 5);
    private final Button createBtn;
    private final Button joinBtn;
    private final Button offlineBtn;
    // Controller đặt true khi chờ phản hồi; view không tự coi click là vào phòng thành công.
    private boolean busy;

    /**
     * Giữ card, màu sắc và kích thước giao diện TV3; chỉ nối các handler của P06.
     *
     * @param controller bộ điều khiển dùng chung với GameView, không mở socket riêng ở view
     * @param onOfflineMode callback do ClientMain cung cấp để mở chế độ vẽ thử
     */
    public LobbyView(ClientController controller, Runnable onOfflineMode) {
        this.controller = controller;
        setSpacing(15);
        setPadding(new Insets(30));
        setAlignment(Pos.CENTER);
        setStyle("-fx-background-color: #2C3E50;");

        Label title = new Label("SKRIBBL MULTIPLAYER");
        title.setFont(Font.font("System", FontWeight.BOLD, 28));
        title.setStyle("-fx-text-fill: #F1C40F;");

        VBox card = new VBox(12);
        card.setMaxWidth(380);
        card.setPadding(new Insets(25));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 10;");

        Label hostLbl = new Label("Địa chỉ IP Server:");
        hostLbl.setFont(Font.font("System", FontWeight.BOLD, 13));
        hostField = new TextField("127.0.0.1");

        Label nameLbl = new Label("Tên hiển thị (Nickname):");
        nameLbl.setFont(Font.font("System", FontWeight.BOLD, 13));
        nicknameField = new TextField("Player_" + UUID.randomUUID().toString().substring(0, 4));

        createBtn = new Button("Tạo phòng mới");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setPrefHeight(38);
        createBtn.setStyle("-fx-background-color: #2ECC71; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");

        Separator sep = new Separator();

        Label joinLbl = new Label("Hoặc nhập mã phòng để tham gia:");
        joinLbl.setFont(Font.font("System", FontWeight.BOLD, 13));

        roomCodeField = new TextField();
        roomCodeField.setPromptText("Mã 5 ký tự, ví dụ ABCD2");

        joinBtn = new Button("Vào phòng");
        joinBtn.setPrefHeight(36);
        joinBtn.setStyle("-fx-background-color: #3498DB; -fx-text-fill: white; -fx-font-weight: bold;");

        HBox joinBox = new HBox(8, roomCodeField, joinBtn);
        roomCodeField.setPrefWidth(230);
        joinBtn.setPrefWidth(100);

        offlineBtn = new Button("Vẽ thử ngoại tuyến (Không cần Server)");
        offlineBtn.setMaxWidth(Double.MAX_VALUE);
        offlineBtn.setStyle("-fx-background-color: #95A5A6; -fx-text-fill: white;");

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #E74C3C; -fx-font-size: 12px;");
        statusLabel.setWrapText(true);

        // Enter và nút Vào phòng đi chung một đường validation, tránh gửi yêu cầu hai cách khác nhau.
        createBtn.setOnAction(e -> create());
        joinBtn.setOnAction(e -> join());
        roomCodeField.setOnAction(e -> join());
        offlineBtn.setOnAction(e -> {
            // Không đổi màn hình trong lúc request mạng còn chờ kết quả.
            if (!busy) {
                onOfflineMode.run();
            }
        });

        card.getChildren().addAll(
                hostLbl, hostField,
                nameLbl, nicknameField,
                new Label("Số vòng / giây mỗi lượt"), new HBox(8, rounds, seconds),
                createBtn, sep,
                joinLbl, joinBox,
                offlineBtn, statusLabel
        );

        getChildren().addAll(title, card);
    }

    /**
     * Kiểm tra tính hợp lệ của thông tin kết nối và danh tính người chơi (Host và Nickname).
     * Được gọi trước khi thực hiện Tạo phòng hoặc Vào phòng.
     *
     * @return boolean:
     *         - true: Thông tin hợp lệ (host không rỗng, nickname từ 1 đến 20 ký tự).
     *         - false: Không hợp lệ, hiển thị thông báo lỗi lên statusLabel.
     */
    private boolean validIdentity() {
        String name = nicknameField.getText().strip();
        if (hostField.getText().isBlank() || name.isEmpty() || name.length() > 20) {
            showError("Nhập host và nickname từ 1 đến 20 ký tự.");
            return false;
        }
        return true;
    }

    /**
     * Xử lý khi người dùng bấm nút "Tạo phòng mới":
     * Chạy trên JavaFX Application Thread, chuyển yêu cầu requestCreateRoom cho ClientController
     * để xử lý kết nối socket nền và gửi gói tin CREATE_ROOM.
     * API requestCreateRoom do TV3 triển khai ở P08: nếu chưa đăng ký thì chờ
     * CONNECT_ACK trước khi tạo phòng; nếu đã đăng ký thì dùng lại kết nối.
     */
    private void create() {
        // Nếu đang chờ phản hồi từ server (busy) hoặc thông tin nhập chưa hợp lệ thì bỏ qua
        if (busy || !validIdentity()) {
            return;
        }
        // Không gọi connectToServer ở đây. Controller P08 quản lý busy và phản hồi lỗi.
        controller.requestCreateRoom(hostField.getText().strip(), 8888,
                nicknameField.getText().strip(), rounds.getValue(), seconds.getValue());
    }

    /**
     * Xử lý khi người dùng bấm nút "Vào phòng" hoặc nhấn Enter ở ô nhập mã phòng:
     * Chuẩn hóa mã phòng (viết hoa, cắt khoảng trắng) và kiểm tra đúng định dạng 5 ký tự [A-Z2-9]{5}.
     * View không kiểm tra phòng tồn tại: server trả snapshot hoặc ERROR_MSG mới biết kết quả.
     */
    private void join() {
        if (busy || !validIdentity()) {
            return;
        }
        // Chuẩn hóa mã phòng thành chữ in hoa theo chuẩn server
        String code = roomCodeField.getText().strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z2-9]{5}")) {
            showError("Mã phòng cần đúng 5 ký tự.");
            return;
        }
        // API P08 do TV3 ghép; chỉ ROOM_UPDATE xác nhận membership mới chuyển sang GameView.
        controller.requestJoinRoom(hostField.getText().strip(), 8888,
                nicknameField.getText().strip(), code);
    }

    /**
     * Thiết lập trạng thái bận cho giao diện sảnh chờ (LobbyView).
     * Được ClientController gọi trên JavaFX Thread để vô hiệu hóa (disable) tất cả các ô nhập liệu
     * và nút bấm, ngăn người dùng click liên tục nhiều lần trong khi đang chờ phản hồi từ server.
     *
     * @param value true: Khóa tất cả các điều khiển và hiện "Đang đợi server..."; false: Mở khóa lại.
     * Controller P08 phải mở lại khi có kết quả, ERROR_MSG, timeout hoặc mất kết nối.
     */
    public void setBusy(boolean value) {
        // Lưu cờ cho handler Enter; chỉ disable nút không chặn được mọi đường gọi join().
        busy = value;
        hostField.setDisable(value);
        nicknameField.setDisable(value);
        roomCodeField.setDisable(value);
        rounds.setDisable(value);
        seconds.setDisable(value);
        createBtn.setDisable(value);
        joinBtn.setDisable(value);
        offlineBtn.setDisable(value);
        if (value) {
            statusLabel.setText("Đang đợi server...");
        }
    }

    /**
     * Hiển thị thông báo lỗi lên sảnh chờ và mở khóa lại các nút bấm (setBusy = false).
     *
     * @param text Chuỗi thông báo lỗi cần hiển thị lên statusLabel.
     */
    public void showError(String text) {
        setBusy(false);
        statusLabel.setText(text);
    }
}
