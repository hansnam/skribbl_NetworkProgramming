package com.skribbl.client.ui.lobby;

import com.skribbl.client.controller.ClientController;
import com.skribbl.client.ui.avatar.AvatarView;
import com.skribbl.common.model.AvatarDto;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Sảnh chờ kiểu skribbl.io: hỗ trợ tùy biến Avatar (Màu, Mắt, Miệng, Ngẫu nhiên),
 * Chơi nhanh (Play! - QUICK_JOIN), Tạo phòng riêng (CREATE_ROOM), Vào phòng bằng mã (JOIN_ROOM)
 * và Vẽ thử ngoại tuyến.
 */
public class LobbyView extends VBox {
    private final TextField hostField;
    private final TextField portField;
    private final TextField nicknameField;
    private final TextField roomCodeField;
    private final Label statusLabel;
    private final ClientController controller;
    private final Spinner<Integer> rounds = new Spinner<>(1, 10, 3);
    private final Spinner<Integer> seconds = new Spinner<>(15, 180, 60, 5);
    private final Button createBtn;
    private final Button quickPlayBtn;
    private final Button joinBtn;
    private final Button offlineBtn;
    private final AvatarView avatarView;
    private int colorIdx = 0;
    private int eyesIdx = 0;
    private int mouthIdx = 0;
    private boolean busy;

    public LobbyView(ClientController controller, Runnable onOfflineMode) {
        this.controller = controller;
        setSpacing(14);
        setPadding(new Insets(18));
        setAlignment(Pos.CENTER);
        setStyle("-fx-background-color: linear-gradient(to bottom right, #1B3B82, #152C62);");

        // Child 0: Header Banner + Avatar Customizer Panel
        VBox headerAndAvatarBox = new VBox(10);
        headerAndAvatarBox.setAlignment(Pos.CENTER);

        Label title = new Label("skribbl.io");
        title.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 34));
        title.setStyle("-fx-text-fill: #FACC15; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 6, 0.3, 0, 2);");

        Label subtitle = new Label("FREE MULTIPLAYER DRAWING & GUESSING GAME");
        subtitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        subtitle.setStyle("-fx-text-fill: #BFDBFE;");

        avatarView = new AvatarView(AvatarDto.DEFAULT, 76);
        HBox avatarEditor = createAvatarCustomizer();
        headerAndAvatarBox.getChildren().addAll(title, subtitle, avatarEditor);

        // Child 1: Main Action Card (Indices 0..11 preserved for ClientFlowTest compatibility)
        VBox card = new VBox(9);
        card.setMaxWidth(420);
        card.setPadding(new Insets(18, 22, 18, 22));
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("skribbl-card");
        card.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 14; -fx-border-color: #CBD5E1; -fx-border-radius: 14; -fx-border-width: 2;");

        // Index 0: hostLbl
        Label hostLbl = new Label("🌐 Địa chỉ Server / Cổng TCP:");
        hostLbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));

        // Index 1: serverBox (hostField at 0, portField at 1)
        hostField = new TextField("127.0.0.1");
        hostField.setPromptText("IP hoặc tên miền");
        portField = new TextField("8888");
        portField.setPrefWidth(85);
        portField.setMaxWidth(85);
        HBox serverBox = new HBox(8, hostField, portField);
        HBox.setHgrow(hostField, Priority.ALWAYS);

        // Index 2: nameLbl
        Label nameLbl = new Label("👤 Tên hiển thị (Nickname):");
        nameLbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));

        // Index 3: nicknameField
        nicknameField = new TextField("Player_" + UUID.randomUUID().toString().substring(0, 4));

        // Index 4: rounds/seconds label
        Label configLbl = new Label("⚙ Cấu hình Phòng riêng (Số vòng / Giây mỗi lượt):");
        configLbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));

        // Index 5: rounds & seconds box
        rounds.setPrefWidth(110);
        seconds.setPrefWidth(110);
        HBox configBox = new HBox(8, new Label("Vòng:"), rounds, new Label("Giây:"), seconds);
        configBox.setAlignment(Pos.CENTER_LEFT);

        // Index 6: createBtn (Create Private Room)
        createBtn = new Button("🏠 Tạo Phòng Riêng (Host)");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setPrefHeight(36);
        createBtn.getStyleClass().add("btn-create");
        createBtn.setStyle("-fx-background-color: #3B9DF8; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px; -fx-background-radius: 8;");

        // Index 7: quickPlayBtn (Play! Public Matchmaking)
        quickPlayBtn = new Button("⚡ CHƠI NHANH (Play!)");
        quickPlayBtn.setMaxWidth(Double.MAX_VALUE);
        quickPlayBtn.setPrefHeight(40);
        quickPlayBtn.getStyleClass().add("btn-play");
        quickPlayBtn.setStyle("-fx-background-color: #53D13A; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 15px; -fx-background-radius: 8;");

        // Index 8: joinLbl
        Label joinLbl = new Label("🔑 Hoặc nhập mã phòng riêng để tham gia:");
        joinLbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));

        // Index 9: joinBox (roomCodeField first, joinBtn last)
        roomCodeField = new TextField();
        roomCodeField.setPromptText("Mã 5 ký tự, ví dụ ABCD2");
        joinBtn = new Button("Vào phòng");
        joinBtn.setPrefHeight(34);
        joinBtn.getStyleClass().add("btn-join");
        joinBtn.setStyle("-fx-background-color: #6366F1; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8;");

        HBox joinBox = new HBox(8, roomCodeField, joinBtn);
        HBox.setHgrow(roomCodeField, Priority.ALWAYS);
        joinBtn.setPrefWidth(105);

        // Index 10: offlineBtn
        offlineBtn = new Button("🎨 Vẽ thử ngoại tuyến (Tập vẽ không cần Server)");
        offlineBtn.setMaxWidth(Double.MAX_VALUE);
        offlineBtn.setStyle("-fx-background-color: #64748B; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 7;");

        // Index 11 (getLast()): statusLabel
        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #DC2626; -fx-font-weight: bold; -fx-font-size: 12px;");
        statusLabel.setWrapText(true);

        createBtn.setOnAction(e -> create());
        quickPlayBtn.setOnAction(e -> quickPlay());
        joinBtn.setOnAction(e -> join());
        roomCodeField.setOnAction(e -> join());
        offlineBtn.setOnAction(e -> {
            if (!busy) {
                onOfflineMode.run();
            }
        });

        card.getChildren().addAll(
                hostLbl, serverBox,
                nameLbl, nicknameField,
                configLbl, configBox,
                createBtn, quickPlayBtn,
                joinLbl, joinBox,
                offlineBtn, statusLabel
        );

        // Child 2: How-to-Play Info Strip
        HBox howToPlayStrip = createHowToPlayStrip();

        getChildren().addAll(headerAndAvatarBox, card, howToPlayStrip);
    }

    private HBox createAvatarCustomizer() {
        HBox box = new HBox(12);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(8, 16, 8, 16));
        box.setMaxWidth(420);
        box.setStyle("-fx-background-color: rgba(255,255,255,0.14); -fx-background-radius: 12; -fx-border-color: rgba(255,255,255,0.28); -fx-border-radius: 12;");

        VBox leftArrows = new VBox(4);
        leftArrows.setAlignment(Pos.CENTER);
        Button prevColor = smallNavBtn("◀ Màu", () -> updateAvatar(colorIdx - 1, eyesIdx, mouthIdx));
        Button prevEyes = smallNavBtn("◀ Mắt", () -> updateAvatar(colorIdx, eyesIdx - 1, mouthIdx));
        Button prevMouth = smallNavBtn("◀ Miệng", () -> updateAvatar(colorIdx, eyesIdx, mouthIdx - 1));
        leftArrows.getChildren().addAll(prevColor, prevEyes, prevMouth);

        VBox rightArrows = new VBox(4);
        rightArrows.setAlignment(Pos.CENTER);
        Button nextColor = smallNavBtn("Màu ▶", () -> updateAvatar(colorIdx + 1, eyesIdx, mouthIdx));
        Button nextEyes = smallNavBtn("Mắt ▶", () -> updateAvatar(colorIdx, eyesIdx + 1, mouthIdx));
        Button nextMouth = smallNavBtn("Miệng ▶", () -> updateAvatar(colorIdx, eyesIdx, mouthIdx + 1));
        rightArrows.getChildren().addAll(nextColor, nextEyes, nextMouth);

        Button randomBtn = new Button("🎲");
        randomBtn.setTooltip(new Tooltip("Tạo nhân vật ngẫu nhiên"));
        randomBtn.setStyle("-fx-background-color: #FACC15; -fx-text-fill: #1E293B; -fx-font-weight: bold; -fx-background-radius: 8; -fx-cursor: hand;");
        randomBtn.setOnAction(e -> {
            Random r = new Random();
            updateAvatar(r.nextInt(AvatarDto.COLOR_COUNT), r.nextInt(AvatarDto.EYES_COUNT), r.nextInt(AvatarDto.MOUTH_COUNT));
        });

        box.getChildren().addAll(leftArrows, avatarView, rightArrows, randomBtn);
        return box;
    }

    private Button smallNavBtn(String text, Runnable action) {
        Button b = new Button(text);
        b.setPrefWidth(72);
        b.setStyle("-fx-background-color: #FFFFFF; -fx-text-fill: #1E293B; -fx-font-size: 11px; -fx-font-weight: bold; -fx-background-radius: 6; -fx-padding: 2 6 2 6; -fx-cursor: hand;");
        b.setOnAction(e -> action.run());
        return b;
    }

    private void updateAvatar(int c, int e, int m) {
        colorIdx = Math.floorMod(c, AvatarDto.COLOR_COUNT);
        eyesIdx = Math.floorMod(e, AvatarDto.EYES_COUNT);
        mouthIdx = Math.floorMod(m, AvatarDto.MOUTH_COUNT);
        AvatarDto dto = new AvatarDto(colorIdx, eyesIdx, mouthIdx);
        avatarView.setAvatar(dto);
        controller.setAvatar(dto);
    }

    private HBox createHowToPlayStrip() {
        HBox strip = new HBox(10);
        strip.setAlignment(Pos.CENTER);
        strip.setMaxWidth(420);
        Label tip = new Label("💡 Mẹo: Dùng phím B (Bút), E (Tẩy), F (Đổ màu), U (Hoàn tác), C (Xóa bảng) khi tới lượt vẽ!");
        tip.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 11.5px;");
        tip.setWrapText(true);
        strip.getChildren().add(tip);
        return strip;
    }

    private boolean validIdentity() {
        String name = nicknameField.getText().strip();
        if (hostField.getText().isBlank() || name.isEmpty() || name.length() > 20) {
            showError("Nhập host và nickname từ 1 đến 20 ký tự.");
            return false;
        }
        try {
            int port = Integer.parseInt(portField.getText().strip());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            showError("Cổng TCP phải là số từ 1 đến 65535.");
            return false;
        }
        return true;
    }

    private void create() {
        if (busy || !validIdentity()) {
            return;
        }
        controller.requestCreateRoom(hostField.getText().strip(), Integer.parseInt(portField.getText().strip()),
                nicknameField.getText().strip(), rounds.getValue(), seconds.getValue());
    }

    private void quickPlay() {
        if (busy || !validIdentity()) {
            return;
        }
        controller.requestQuickJoin(hostField.getText().strip(), Integer.parseInt(portField.getText().strip()),
                nicknameField.getText().strip());
    }

    private void join() {
        if (busy || !validIdentity()) {
            return;
        }
        String code = roomCodeField.getText().strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z2-9]{5}")) {
            showError("Mã phòng cần đúng 5 ký tự.");
            return;
        }
        controller.requestJoinRoom(hostField.getText().strip(), Integer.parseInt(portField.getText().strip()),
                nicknameField.getText().strip(), code);
    }

    public void setBusy(boolean value) {
        busy = value;
        hostField.setDisable(value);
        portField.setDisable(value);
        nicknameField.setDisable(value);
        roomCodeField.setDisable(value);
        rounds.setDisable(value);
        seconds.setDisable(value);
        createBtn.setDisable(value);
        quickPlayBtn.setDisable(value);
        joinBtn.setDisable(value);
        offlineBtn.setDisable(value);
        if (value) {
            statusLabel.setText("Đang đợi server...");
        }
    }

    public void showError(String text) {
        setBusy(false);
        statusLabel.setText(text);
    }
}
