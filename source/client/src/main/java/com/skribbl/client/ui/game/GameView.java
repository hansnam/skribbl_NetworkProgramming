package com.skribbl.client.ui.game;

import com.skribbl.client.controller.ClientController;
import com.skribbl.client.ui.canvas.DrawingCanvas;
import com.skribbl.common.dto.RoomUpdateDto;
import com.skribbl.common.model.RoomStatus;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Objects;

public class GameView extends BorderPane {
    private final TopStatusBar topStatusBar;
    private final PlayerListPanel playerListPanel;
    private final DrawingCanvas drawingCanvas;
    private final TextArea chatArea;
    private TextField inputField;
    private Button sendBtn;
    private final Button startBtn = new Button("Bắt đầu / Chơi lại");
    private final Button leaveBtn = new Button("Rời phòng");
    private final Label roomStatus = new Label("Chưa ở phòng");
    private final Label roleHint = new Label();
    private RoomUpdateDto room;
    private String myId;
    private boolean roomBusy;
    private boolean offline;

    /** Tạo màn hình chơi gồm trạng thái lượt, danh sách người chơi, bảng vẽ, chat và nút quản lý phòng. */
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

        installRoomControls(controller);
        setChatInputEnabled(false);
    }

    /** Dùng lại TopStatusBar. Handler chỉ gửi yêu cầu; chờ phản hồi server để đổi state. */
    public void installRoomControls(ClientController controller) {
        setTop(null);
        setTop(new VBox(6, new HBox(8, roomStatus, startBtn, leaveBtn), roleHint, topStatusBar));
        startBtn.setOnAction(event -> controller.startGame());
        leaveBtn.setOnAction(event -> controller.leaveRoom());
        refreshButtons();
    }

    /** Enter/Gửi cùng một handler; validate trước gửi, không echo đáp án đúng. */
    private VBox createChatPanel(ClientController controller) {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));
        panel.setPrefWidth(260);
        panel.setMinWidth(200);
        panel.setStyle("-fx-background-color: #ECF0F1; -fx-border-color: #BDC3C7; -fx-border-width: 0 0 0 1;");

        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        VBox.setVgrow(chatArea, Priority.ALWAYS);

        inputField = new TextField();
        inputField.setPromptText("Nhập từ đoán hoặc lệnh (/create)...");

        sendBtn = new Button("Gửi");
        Runnable sendAction = () -> {
            if (inputField.isDisabled())
                return;

            String text = inputField.getText().strip();
            if (text.isEmpty())
                return;
            if (text.length() > 200) {
                appendChatMessage("[Hệ thống] Tối đa 200 ký tự.");
                return;
            }
            controller.sendChat(text);
            inputField.clear();
        };

        sendBtn.setOnAction(e -> sendAction.run());
        inputField.setOnAction(e -> sendAction.run());

        HBox inputBox = new HBox(5, inputField, sendBtn);
        HBox.setHgrow(inputField, Priority.ALWAYS);

        panel.getChildren().addAll(new Label("Chat & Đoán từ:"), chatArea, inputBox);
        return panel;
    }

    /** Controller gọi trên FX với mọi ROOM_UPDATE; bảng/controls đọc cùng snapshot. */
    public void showRoom(RoomUpdateDto value, String playerId) {
        room = value;
        myId = playerId;
        offline = false;
        playerListPanel.updatePlayers(room.players());
        roomStatus.setText("Phòng " + room.roomCode() + " — " + room.status());
        refreshButtons();
    }

    /** Khóa/mở Start và Rời trong lúc request chờ phản hồi. */
    public void setRoomBusy(boolean busy) {
        roomBusy = busy;
        refreshButtons();
    }

    /** Tính quyền Start/Rời từ snapshot và busy; không tự khởi động ván. */
    private void refreshButtons() {
        boolean canStart = room != null && Objects.equals(myId, room.hostId())
                && room.players().size() >= 2 && (room.status() == RoomStatus.WAITING
                || room.status() == RoomStatus.GAME_OVER);
        startBtn.setDisable(!canStart || roomBusy);
        leaveBtn.setDisable((room == null && !offline) || roomBusy);
    }

    /** Controller cấp quyền nhập theo phase/drawer/hasGuessed. */
    public void setChatInputEnabled(boolean enabled) {
        inputField.setDisable(!enabled);
        sendBtn.setDisable(!enabled);
    }

    /** Dọn cả lịch sử và ô nhập khi rời/đổi phòng. */
    public void clearChat() {
        chatArea.clear();
        inputField.clear();
    }

    /** Plain text: bỏ CR/LF giả, giới hạn lịch sử và cuộn tới tin cuối. */
    public void appendChatMessage(String message) {
        if (message == null)
            return;
        chatArea.appendText(message.replace('\r', ' ').replace('\n', ' ') + "\n");
        if (chatArea.getLength() > 20_000)
            chatArea.deleteText(0, chatArea.getLength() - 16_000);
        chatArea.positionCaret(chatArea.getLength());
    }

    /** Thông báo thao tác phòng; không đổi snapshot membership. */
    public void showRoomError(String text) {
        roomStatus.setText(text);
    }

    /** Hiện lời nhắc riêng, không ghi đè timer/mask của TopStatusBar. */
    public void showRoleHint(String text) {
        roleHint.setText(text);
    }

    /** Chỉ mở canvas local; chưa ở phòng mạng và chưa có quyền game thật. */
    public void showOffline() {
        offline = true;
        roomStatus.setText("Vẽ thử ngoại tuyến");
        topStatusBar.updateTurnInfo("Vẽ thử ngoại tuyến", "", 0);
        drawingCanvas.setDrawingEnabled(true); refreshButtons();
    }

    /** Dọn view sau LEAVE ACK/disconnect, không tạo canvas/view thứ hai. */
    public void clearRoom() {
        room = null;
        myId = null;
        offline = false;
        roomBusy = false;
        drawingCanvas.setDrawingEnabled(false);
        drawingCanvas.clearCanvasLocal();
        playerListPanel.updatePlayers(null);
        clearChat();
        showRoleHint("");
        topStatusBar.updateTurnInfo("Chưa ở phòng", "", 0);
        roomStatus.setText("Chưa ở phòng");
        refreshButtons();
        setChatInputEnabled(false);
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
