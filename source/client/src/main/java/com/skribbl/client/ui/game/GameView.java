package com.skribbl.client.ui.game;

import com.skribbl.client.controller.ClientController;
import com.skribbl.client.ui.canvas.DrawingCanvas;
import com.skribbl.common.dto.RoomUpdateDto;
import com.skribbl.common.model.RoomStatus;
import com.skribbl.common.model.RoomType;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Objects;

/**
 * Skribbl.io-style main game screen:
 * - Top: Unified TopStatusBar (Timer, Round, Word Mask & Lengths, Room Code + Copy, Start, Mute, Leave).
 * - Left: PlayerListPanel (Avatars, Ranks, Scores, +TurnDeltas).
 * - Center: DrawingCanvas (with 26 colors, Brush/Eraser/Fill/Undo/Clear, and In-Canvas Overlays).
 * - Right: Color-coded Chat & Guess feed + Input Box.
 */
public class GameView extends BorderPane {
    private final TopStatusBar topStatusBar;
    private final PlayerListPanel playerListPanel;
    private final DrawingCanvas drawingCanvas;
    private final TextArea chatArea;
    private final VBox chatFeedBox;
    private final ScrollPane chatScrollPane;
    private TextField inputField;
    private Button sendBtn;
    private RoomUpdateDto room;
    private String myId;
    private boolean roomBusy;
    private boolean offline;

    public GameView(ClientController controller) {
        this.topStatusBar = new TopStatusBar();
        this.playerListPanel = new PlayerListPanel();
        this.drawingCanvas = new DrawingCanvas(controller);
        this.chatArea = new TextArea();
        this.chatFeedBox = new VBox(5);
        this.chatScrollPane = new ScrollPane(chatFeedBox);

        setMinSize(0, 0);
        setTop(topStatusBar);
        setLeft(playerListPanel);
        setCenter(drawingCanvas);
        setRight(createChatPanel(controller));

        installRoomControls(controller);
        setChatInputEnabled(false);
    }

    public void installRoomControls(ClientController controller) {
        setTop(topStatusBar);
        topStatusBar.getStartBtn().setOnAction(event -> controller.startGame());
        topStatusBar.getLeaveBtn().setOnAction(event -> controller.leaveRoom());
        refreshButtons();
    }

    private VBox createChatPanel(ClientController controller) {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));
        panel.setPrefWidth(275);
        panel.setMinWidth(215);
        panel.setStyle("-fx-background-color: #F1F5F9; -fx-border-color: #CBD5E1; -fx-border-width: 0 0 0 1.5;");

        Label header = new Label("💬 Trò chuyện & Đoán từ");
        header.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 13.5));
        header.setStyle("-fx-text-fill: #1E293B;");

        chatFeedBox.setPadding(new Insets(6));
        chatScrollPane.setFitToWidth(true);
        chatScrollPane.setStyle("-fx-background-color: #FFFFFF; -fx-background: #FFFFFF; -fx-border-color: #CBD5E1; -fx-border-radius: 8; -fx-background-radius: 8;");
        VBox.setVgrow(chatScrollPane, Priority.ALWAYS);

        chatArea.setEditable(false);
        chatArea.setWrapText(true);

        inputField = new TextField();
        inputField.setPromptText("Nhập đáp án đoán từ tại đây...");
        inputField.setPrefHeight(34);

        sendBtn = new Button("Gửi");
        sendBtn.setPrefHeight(34);
        sendBtn.setStyle("-fx-background-color: #3B9DF8; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 7; -fx-cursor: hand;");

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

        // IMPORTANT: inputBox MUST remain the last child of panel (with inputField first, sendBtn last) for ClientFlowTest
        HBox inputBox = new HBox(6, inputField, sendBtn);
        HBox.setHgrow(inputField, Priority.ALWAYS);

        panel.getChildren().addAll(header, chatScrollPane, inputBox);
        return panel;
    }

    public void showRoom(RoomUpdateDto value, String playerId) {
        room = value;
        myId = playerId;
        offline = false;
        playerListPanel.updatePlayers(room.players());

        boolean isPublic = room.roomType() == RoomType.PUBLIC;
        String modeTag = isPublic ? "🌐 Chơi nhanh" : "🔒 Phòng " + room.roomCode();
        String statusText;
        if (room.status() == RoomStatus.WAITING && isPublic && room.startInSec() != null) {
            statusText = modeTag + " • Bắt đầu sau " + room.startInSec() + "s";
        } else {
            statusText = modeTag + " • " + room.status();
        }
        topStatusBar.setRoomMeta(room.roomCode(), !isPublic, statusText);
        refreshButtons();
    }

    public void setRoomBusy(boolean busy) {
        roomBusy = busy;
        refreshButtons();
    }

    private void refreshButtons() {
        boolean isPublic = room != null && room.roomType() == RoomType.PUBLIC;
        boolean canStart = room != null && !isPublic && Objects.equals(myId, room.hostId())
                && room.players().size() >= 2 && (room.status() == RoomStatus.WAITING
                || room.status() == RoomStatus.GAME_OVER);
        topStatusBar.getStartBtn().setVisible(!isPublic);
        topStatusBar.getStartBtn().setManaged(!isPublic);
        topStatusBar.getStartBtn().setDisable(!canStart || roomBusy);
        topStatusBar.getLeaveBtn().setDisable((room == null && !offline) || roomBusy);
    }

    public void setChatInputEnabled(boolean enabled) {
        inputField.setDisable(!enabled);
        sendBtn.setDisable(!enabled);
    }

    public void clearChat() {
        chatArea.clear();
        chatFeedBox.getChildren().clear();
        inputField.clear();
    }

    public void appendChatMessage(String message) {
        if (message == null)
            return;
        String clean = message.replace('\r', ' ').replace('\n', ' ');
        chatArea.appendText(clean + "\n");
        if (chatArea.getLength() > 20_000)
            chatArea.deleteText(0, chatArea.getLength() - 16_000);
        chatArea.positionCaret(chatArea.getLength());

        Label row = new Label(clean);
        row.setWrapText(true);
        row.setMaxWidth(Double.MAX_VALUE);
        row.setPadding(new Insets(5, 8, 5, 8));

        if (clean.contains("đã đoán đúng!")) {
            row.setStyle("-fx-background-color: #DCFCE7; -fx-text-fill: #15803D; -fx-font-weight: bold; -fx-background-radius: 6;");
        } else if (clean.startsWith("[Gần đúng]")) {
            row.setStyle("-fx-background-color: #FEF9C3; -fx-text-fill: #B45309; -fx-font-weight: bold; -fx-background-radius: 6;");
        } else if (clean.startsWith("[Lỗi]")) {
            row.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #B91C1C; -fx-font-weight: bold; -fx-background-radius: 6;");
        } else if (clean.startsWith("[Hệ thống]")) {
            row.setStyle("-fx-background-color: #EFF6FF; -fx-text-fill: #1D4ED8; -fx-font-weight: bold; -fx-background-radius: 6;");
        } else {
            boolean alt = chatFeedBox.getChildren().size() % 2 == 1;
            row.setStyle("-fx-background-color: " + (alt ? "#F8FAFC" : "#FFFFFF") + "; -fx-text-fill: #0F172A; -fx-background-radius: 5;");
        }

        chatFeedBox.getChildren().add(row);
        if (chatFeedBox.getChildren().size() > 250) {
            chatFeedBox.getChildren().remove(0, 50);
        }
        Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
    }

    public void showRoomError(String text) {
        topStatusBar.setRoleHintText("⚠ " + text);
    }

    public void showRoleHint(String text) {
        topStatusBar.setRoleHintText(text);
    }

    public void showOffline() {
        offline = true;
        topStatusBar.setRoomMeta("", false, "🎨 Vẽ thử ngoại tuyến");
        topStatusBar.updateTurnInfo("Chế độ Tập vẽ Tự do", "SKRIBBL", 99);
        topStatusBar.setRoleHintText("Bút (B) • Tẩy (E) • Đổ màu (F) • Hoàn tác (U) • Xóa (C)");
        drawingCanvas.hideOverlay();
        drawingCanvas.setDrawingEnabled(true);
        drawingCanvas.setReactionBarVisible(false);
        refreshButtons();
    }

    public void clearRoom() {
        room = null;
        myId = null;
        offline = false;
        roomBusy = false;
        drawingCanvas.hideOverlay();
        drawingCanvas.setDrawingEnabled(false);
        drawingCanvas.setReactionBarVisible(false);
        drawingCanvas.clearCanvasLocal();
        playerListPanel.clearTurnDeltas();
        playerListPanel.updatePlayers(null);
        clearChat();
        showRoleHint("");
        topStatusBar.updateTurnInfo("Chưa ở phòng", "", 0);
        topStatusBar.setRoomMeta("", false, "Chưa ở phòng");
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
