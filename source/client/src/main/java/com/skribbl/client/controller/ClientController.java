package com.skribbl.client.controller;

import com.skribbl.client.net.SocketClient;
import com.skribbl.client.ui.game.GameOverDialog;
import com.skribbl.client.ui.game.GameView;
import com.skribbl.client.ui.game.WordChoiceDialog;
import com.skribbl.client.ui.lobby.LobbyView;
import com.skribbl.common.dto.*;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.common.protocol.NetworkMessage;
import javafx.application.Platform;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Điều phối phiên và UI của một client. Socket/reader/writer nằm trong package net;
 * bố cục/canvas/bảng điểm nằm trong các view. Không giữ socket I/O hoặc layout ở đây.
 *
 * Cách đọc: (2) tạo/join từ sảnh → (4) nhận ACK/snapshot → (3) thao tác trong phòng.
 * Dialog ở (5); phản hồi UI ở (6); mọi đường mất kết nối kết thúc ở cleanup (7).
 * State bên dưới chỉ đổi trên FX thread; reader/connector chỉ dùng epoch nguyên tử.
 */
public class ClientController {
    private enum Pending { NONE, CONNECT, CREATE, JOIN, START, LEAVE }

    // Tài nguyên nền: connector chỉ mở kết nối; timeout chỉ hẹn việc về FX thread.
    private final SocketClient socketClient = new SocketClient();
    private final ExecutorService connector = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timeout = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong connectionEpoch = new AtomicLong();

    // Danh tính/địa chỉ của phiên; membership và luật UI luôn theo snapshot server.
    private String nickname;
    private String playerId;
    private String connectedHost;
    private int connectedPort;
    private String currentRoomCode;
    private RoomStatus state = RoomStatus.WAITING;
    private List<PlayerDto> players = List.of();
    private boolean canDraw;
    private boolean offline;
    private boolean shutdown;

    // Chỉ một thao tác chờ. Request tạo/join được giữ tới CONNECT_ACK, không phải tới khi mở socket.
    private Pending pending = Pending.NONE;
    private long pendingVersion;
    private Runnable pendingRoomRequest;

    // Dùng lại view của nhóm; mỗi loại dialog chỉ có một instance đang hoạt động.
    private GameView gameView;
    private LobbyView lobbyView;
    private Consumer<RoomUpdateDto> onRoomJoinedCallback;
    private Runnable onRoomLeftCallback;
    private GameOverDialog resultDialog;
    private WordChoiceDialog choiceDialog;
    private String choiceTurnId;

    // ── 1. Khởi tạo và gắn view/callback ──

    /**
     * Tạo controller; UI được gắn qua setters trước khi nhận packet.
     */
    public ClientController(String nickname) {
        this.nickname = nickname;
    }

    /**
     * ClientMain gắn màn hình chơi dùng chung với sảnh.
     */
    public void setGameView(GameView gameView) {
        this.gameView = gameView;
    }

    /**
     * Gắn đúng instance sảnh để báo lỗi và khóa/mở controls.
     */
    public void setLobbyView(LobbyView view) {
        lobbyView = view;
    }

    /**
     * Callback đổi Scene khi snapshot xác nhận client vừa vào phòng.
     */
    public void setOnRoomJoinedCallback(Consumer<RoomUpdateDto> callback) {
        this.onRoomJoinedCallback = callback;
    }

    /**
     * Callback về sảnh sau LEAVE ACK hoặc mất kết nối.
     */
    public void setOnRoomLeftCallback(Runnable callback) {
        this.onRoomLeftCallback = callback;
    }

    /**
     * Tên đã đăng ký hoặc đang nhập ở sảnh.
     */
    public String getNickname() {
        return nickname;
    }

    /**
     * ID do CONNECT_ACK cấp; giữ lại khi rời phòng nhưng còn kết nối.
     */
    public String getPlayerId() {
        return playerId;
    }

    /**
     * Mã phòng đang tham gia; null nếu đang ở sảnh.
     */
    public String getCurrentRoomCode() {
        return currentRoomCode;
    }

    /**
     * Chỉ đổi tên trước khi đăng ký; không đổi danh tính giữa phiên.
     */
    public void setNickname(String value) {
        if (playerId == null && value != null && !value.isBlank()) {
            nickname = value.strip();
        }
    }

    /**
     * Lấy quyền host từ snapshot, không giữ thêm một cờ dễ lệch dữ liệu.
     */
    public boolean isHost() {
        PlayerDto me = findMe();
        return me != null && me.isHost();
    }


    // ── 2. Kết nối và request từ sảnh: entry → điều phối → pending → gửi ──

    /**
     * Click Tạo phòng → requestRoom → chờ CONNECT_ACK → gửi CREATE_ROOM.
     */
    public void requestCreateRoom(String host, int port, String name, int rounds, int seconds) {
        requestRoom(host, port, name, Pending.CREATE,
                () -> sendPacket(MessageType.CREATE_ROOM, new CreateRoomReqDto(rounds, seconds)));
    }

    /**
     * Click Vào phòng → requestRoom → chờ CONNECT_ACK → gửi JOIN_ROOM.
     */
    public void requestJoinRoom(String host, int port, String name, String code) {
        requestRoom(host, port, name, Pending.JOIN,
                () -> sendPacket(MessageType.JOIN_ROOM, new JoinRoomReqDto(code)));
    }

    /**
     * API tương thích cho caller cũ; vẫn đi qua luồng kết nối nền/chờ ACK chung.
     */
    public void connectToServer(String host, int port) throws IOException {
        requestRoom(host, port, nickname, Pending.CONNECT, () -> {});
    }

    /**
     * FX thread: kiểm tra yêu cầu, dùng lại phiên đã đăng ký hoặc mở kết nối nền.
     * Lưu đúng một request để CONNECT_ACK thực thi; không gửi CREATE/JOIN trước ACK.
     */
    private void requestRoom(String host, int port, String name, Pending kind, Runnable request) {
        requireFx();
        if (shutdown || pending != Pending.NONE)
            return;
        if (currentRoomCode != null || offline) {
            showError("Rời phòng/chế độ offline trước.");
            return;
        }
        if (host == null || host.isBlank() || name == null || name.isBlank()
                || name.length() > 20 || port < 1 || port > 65535) {
            showError("Host, port hoặc nickname không hợp lệ.");
            return;
        }

        // Nếu đã kết nối và đăng ký danh tính với server trên cùng host/port/nickname: tái sử dụng kết nối
        if (playerId != null && socketClient.isConnected()) {
            if (!host.equalsIgnoreCase(connectedHost) || port != connectedPort || !name.equals(nickname)) {
                showError("Phiên hiện tại giữ host/nickname cũ. Ngắt kết nối trước khi đổi.");
                return;
            }
            beginPending(kind);
            request.run();
            if (kind == Pending.CONNECT)
                finishPending();
            return;
        }

        // Thiết lập phiên kết nối mới: tăng epoch để vô hiệu hóa các gói tin từ phiên cũ
        nickname = name.strip();
        connectedHost = host;
        connectedPort = port;
        long epoch = connectionEpoch.incrementAndGet();
        socketClient.disconnect();
        playerId = null;
        pendingRoomRequest = request;
        beginPending(kind);
        String registeredName = nickname;

        // Chạy tác vụ mở socket connect trên luồng nền (connector) để không block giao diện JavaFX
        connector.execute(() -> {
            try {
                socketClient.connect(host, port, this, epoch);
                if (isCurrentEpoch(epoch) && !socketClient.send(MessageType.CONNECT_REQ,
                        new ConnectReqDto(registeredName))) {
                    onConnectionLost("Không gửi được CONNECT_REQ", epoch);
                }
            } catch (IOException | RuntimeException ex) {
                onConnectionLost(ex.getMessage(), epoch);
            }
        });
    }

    /**
     * Khóa controls và hẹn timeout 10 giây. Version/epoch vô hiệu hóa timer của request cũ.
     */
    private void beginPending(Pending kind) {
        pending = kind;
        long version = ++pendingVersion;
        long epoch = connectionEpoch.get();
        setBusy(true);

        // Hẹn giờ quá hạn 10 giây nếu server không phản hồi
        timeout.schedule(() -> Platform.runLater(() -> {
            if (version != pendingVersion || !isCurrentEpoch(epoch) || pending == Pending.NONE)
                return;
            pendingRoomRequest = null;
            if (playerId == null) {
                onConnectionLost("Hết thời gian chờ đăng ký", epoch);
                return;
            }
            finishPending();
            showError("Chưa nhận phản hồi sau 10 giây; trạng thái phòng vẫn theo server.");
        }), 10, TimeUnit.SECONDS);
    }

    /**
     * Kết thúc chờ; tăng version để timer cũ không mở khóa một request mới.
     */
    private void finishPending() {
        pending = Pending.NONE;
        pendingVersion++;
        setBusy(false);
    }

    /**
     * FX thread: đồng bộ trạng thái chờ lên cả sảnh và controls phòng.
     */
    private void setBusy(boolean busy) {
        if (lobbyView != null)
            lobbyView.setBusy(busy);
        if (gameView != null)
            gameView.setRoomBusy(busy);
    }

    /**
     * Chỉ enqueue vào SocketClient; thất bại thì đưa lỗi về onConnectionLost.
     */
    private void sendPacket(MessageType type, Object payload) {
        if (!socketClient.send(type, payload)) {
            onConnectionLost("Không gửi được dữ liệu; kết nối đã đóng hoặc hàng gửi đầy.", connectionEpoch.get());
        }
    }


    // ── 3. Thao tác trong phòng và chế độ vẽ thử ──

    /**
     * Tạo phòng trên phiên đã đăng ký; không dùng lúc đang ở phòng hoặc đang chờ.
     */
    public void createRoom(int rounds, int seconds) {
        if (playerId == null || currentRoomCode != null || pending != Pending.NONE)
            return;
        beginPending(Pending.CREATE);
        sendPacket(MessageType.CREATE_ROOM, new CreateRoomReqDto(rounds, seconds));
    }

    /**
     * Join bằng mã trên phiên đã đăng ký; chỉ snapshot server xác nhận thành công.
     */
    public void joinRoom(String code) {
        if (playerId == null || currentRoomCode != null || pending != Pending.NONE)
            return;
        beginPending(Pending.JOIN);
        sendPacket(MessageType.JOIN_ROOM, new JoinRoomReqDto(code.strip().toUpperCase(Locale.ROOT)));
    }

    /**
     * Host đủ người và đúng phase mới được yêu cầu start; server kiểm tra lần cuối.
     */
    public void startGame() {
        if (pending != Pending.NONE || currentRoomCode == null || !isHost()
                || players.size() < 2 || (state != RoomStatus.WAITING && state != RoomStatus.GAME_OVER))
            return;
        beginPending(Pending.START);
        sendPacket(MessageType.START_GAME, null);
    }

    /**
     * Online: gửi LEAVE và chờ ACK mới clearRoom/notifyLeft; offline dọn ngay.
     */
    public void leaveRoom() {
        if (offline) {
            offline = false;
            clearRoom();
            notifyLeft();
            return;
        }
        if (currentRoomCode == null || pending != Pending.NONE)
            return;

        beginPending(Pending.LEAVE);
        sendPacket(MessageType.LEAVE_ROOM, null);
    }

    /**
     * Chuẩn hóa nội dung, xử lý slash command hoặc gửi CHAT_SEND; không tự echo đáp án.
     */
    public void sendChat(String text) {
        if (text == null || text.isBlank() || text.strip().length() > 200 || currentRoomCode == null)
            return;
        String content = text.strip();
        if (content.startsWith("/")) {
            handleSlashCommand(content);
            return;
        }

        sendPacket(MessageType.CHAT_SEND, new ChatSendDto(content));
    }

    /**
     * Các lệnh chat dùng chung startGame/leaveRoom như nút bấm.
     */
    private void handleSlashCommand(String text) {
        String[] args = text.split("\\s+");
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "/start" -> startGame();
            case "/leave" -> leaveRoom();
            case "/create", "/join" -> showError("Rời phòng, dùng nút Tạo/Vào phòng trên sảnh.");
            default -> showError("Lệnh hỗ trợ trong phòng: /start, /leave.");
        }
    }

    /**
     * Online chỉ gửi khi được vẽ; offline canvas đã vẽ local.
     */
    public void sendDrawStroke(DrawStrokeDto dto) {
        if (offline)
            return;  // Canvas đã vẽ local ở chế độ thử.
        if (canDraw)
            sendPacket(MessageType.DRAW_STROKE, dto);
    }

    /**
     * Online chờ CLEAR_CANVAS từ server; offline xóa local ngay.
     */
    public void sendClearCanvas() {
        if (offline) {
            gameView.getDrawingCanvas().clearCanvasLocal();
            return;
        }
        if (canDraw)
            sendPacket(MessageType.CLEAR_CANVAS, null);
    }

    /**
     * Chỉ bật vẽ thử khi không có phiên online/request chờ.
     * @return true nếu đã vào offline để ClientMain đổi Scene; false nếu bị từ chối.
     */
    public boolean enterOfflineMode() {
        requireFx();
        if (shutdown || pending != Pending.NONE || socketClient.isConnected()) {
            showError("Ngắt kết nối online trước khi chuyển sang vẽ thử.");
            return false;
        }
        connectionEpoch.incrementAndGet();
        socketClient.disconnect();
        playerId = null;
        pendingRoomRequest = null;
        finishPending();
        clearRoom();
        offline = true;
        if (gameView != null)
            gameView.showOffline();
        return true;
    }


    // ── 4. Nhận packet: reader → FX switch → snapshot → quyền chat ──

    /**
     * Reader gọi: bỏ phiên cũ, xử lý heartbeat, rồi chuyển packet nghiệp vụ sang FX.
     * Kiểm tra lại epoch trong runLater vì phiên có thể đổi trong lúc task chờ.
     */
    public void handleIncomingMessage(NetworkMessage message, long epoch) {
        if (message == null || message.type() == null || !isCurrentEpoch(epoch))
            return;

        // Heartbeat PING-PONG phản hồi ngay trên socket writer
        if (message.type() == MessageType.PING) {
            if (!socketClient.send(MessageType.PONG, null))
                onConnectionLost("Không gửi được PONG", epoch);
            return;
        }

        // Chuyển toàn bộ cập nhật giao diện và trạng thái sang JavaFX Application Thread
        Platform.runLater(() -> {
            if (!isCurrentEpoch(epoch))
                return;

            try {
                applyIncomingOnFxThread(message);
            }
            catch (RuntimeException ex) {
                System.err.println("Packet handler failed: " + ex);
                onConnectionLost("Dữ liệu server không hợp lệ: " + ex.getMessage(), epoch);
            }
        });
    }

    /**
     * Một switch áp dụng mỗi packet đúng một lần trên FX thread.
     * Không đặt runLater lồng nhau trong các nhánh, không cộng điểm lần nữa ở client.
     */
    private void applyIncomingOnFxThread(NetworkMessage message) {
        requireFx();
        switch (message.type()) {
            // Server xác nhận đăng ký danh tính thành công và cấp playerId
            case CONNECT_ACK -> {
                ConnectAckDto ack = JsonCodec.decodePayload(message, ConnectAckDto.class);
                if (ack == null || ack.playerId() == null || ack.playerId().isBlank()) {
                    throw new IllegalArgumentException("CONNECT_ACK has no playerId");
                }

                playerId = ack.playerId();

                if (ack.nickname() != null)
                    nickname = ack.nickname();

                // Thực thi yêu cầu tạo hoặc vào phòng đang chờ sau khi kết nối sẵn sàng
                Runnable request = pendingRoomRequest;
                pendingRoomRequest = null;

                if (request != null)
                    request.run();
                if (pending == Pending.CONNECT)
                    finishPending();
            }

            // Server gửi snapshot thông tin phòng mới nhất (danh sách người chơi, trạng thái)
            case ROOM_UPDATE -> applyRoom(JsonCodec.decodePayload(message, RoomUpdateDto.class));
            // Server xác nhận đã rời phòng thành công
            case LEAVE_ROOM -> {
                if (currentRoomCode == null) { return; }
                finishPending();
                clearRoom();
                notifyLeft();
            }

            // Tin nhắn trò chuyện hoặc thông báo đoán đúng/gần đúng
            case CHAT_MESSAGE -> {
                ChatMessageDto chat = JsonCodec.decodePayload(message, ChatMessageDto.class);
                String prefix;

                if (chat.kind() == ChatKind.NORMAL) {
                    prefix = chat.sender() + ": ";
                }
                else if (chat.kind() == ChatKind.CLOSE_GUESS) {
                    prefix = "[Gần đúng] ";
                }
                else {
                    prefix = "[Hệ thống] ";
                }
                append(prefix + chat.text());
            }

            // Nhận danh sách 3 từ khóa để người vẽ lựa chọn
            case WORD_CHOICES -> showWordChoicePopup(JsonCodec.decodePayload(message, WordChoicesDto.class));
            // Lượt vẽ chính thức bắt đầu
            case TURN_START -> {
                closeChoice();
                state = RoomStatus.DRAWING;
                TurnStartDto turn = JsonCodec.decodePayload(message, TurnStartDto.class);
                canDraw = Objects.equals(playerId, turn.drawerId());
                gameView.getDrawingCanvas().clearCanvasLocal();
                gameView.getDrawingCanvas().setDrawingEnabled(canDraw);
                gameView.getTopStatusBar().updateTurnInfo("Vòng " + turn.round() + "/" + turn.maxRounds()
                        + " — Người vẽ: " + turn.drawerNickname(), turn.wordDisplay(), turn.drawTimeSec());
                refreshChat();
            }

            // Cập nhật đồng hồ đếm ngược và gợi ý ký tự mở dần
            case TIMER_UPDATE -> {
                TimerUpdateDto timer = JsonCodec.decodePayload(message, TimerUpdateDto.class);
                gameView.getTopStatusBar().updateTimerAndMask(timer.remainingSec(), timer.wordMask(), canDraw);
                if (timer.remainingSec() == 0) {
                    canDraw = false;
                    gameView.getDrawingCanvas().setDrawingEnabled(false);
                }
            }

            // Nhận nét vẽ từ xa của người vẽ và hiển thị lên canvas
            case DRAW_STROKE -> gameView.getDrawingCanvas().renderRemoteStroke(
                    JsonCodec.decodePayload(message, DrawStrokeDto.class));
            // Xóa sạch canvas theo thông điệp từ server
            case CLEAR_CANVAS -> gameView.getDrawingCanvas().clearCanvasLocal();
            // Nhận toàn bộ lịch sử nét vẽ khi tham gia giữa chừng
            case CANVAS_SYNC -> gameView.getDrawingCanvas().renderSyncHistory(
                    JsonCodec.decodePayload(message, CanvasSyncDto.class).strokes());
            // Thông báo một người chơi đã đoán chính xác từ khóa
            case PLAYER_GUESSED -> {
                PlayerGuessedDto dto = JsonCodec.decodePayload(message, PlayerGuessedDto.class);
                players = dto.players();
                gameView.getPlayerListPanel().updatePlayers(players);
                refreshChat();
                append("[Hệ thống] " + dto.nickname() + " đã đoán đúng! +" + dto.awardedPoints());
            }

            // Kết thúc một lượt vẽ, lật mở đáp án và lý do kết thúc
            case TURN_END -> {
                closeChoice();
                state = RoomStatus.TURN_REVEAL;
                canDraw = false;
                TurnEndDto dto = JsonCodec.decodePayload(message, TurnEndDto.class);
                players = dto.players();
                gameView.getPlayerListPanel().updatePlayers(players);
                gameView.getDrawingCanvas().setDrawingEnabled(false);
                refreshChat();
                if (dto.word() == null || dto.word().isEmpty()) {
                    append("[Hệ thống] Lượt hủy trước khi chọn từ.");
                }
                else {
                    append("[Hệ thống] Đáp án: " + dto.word() + " — " + dto.reason());
                }
            }
            // Kết thúc toàn bộ ván đấu, hiển thị bảng vinh danh người chiến thắng
            case GAME_OVER -> {
                closeChoice();
                closeResult();
                state = RoomStatus.GAME_OVER;
                canDraw = false;
                GameOverDto dto = JsonCodec.decodePayload(message, GameOverDto.class);
                players = dto.players();
                gameView.getPlayerListPanel().updatePlayers(players);
                gameView.getDrawingCanvas().setDrawingEnabled(false);
                refreshChat();
                resultDialog = new GameOverDialog(gameView.getScene().getWindow(), dto);
                resultDialog.show();
            }
            // Thông báo lỗi từ server (sai luật, spam chat, phòng đầy...)
            case ERROR_MSG -> {
                ErrorMsgDto error = JsonCodec.decodePayload(message, ErrorMsgDto.class);
                if (!"CHAT_RATE_LIMITED".equals(error.code())) {
                    finishPending();
                    pendingRoomRequest = null;
                }
                showError(error.code() + ": " + error.message());
            }
            default -> { }
        }
    }

    /**
     * Áp dụng snapshot/membership; gọi callback vào phòng chỉ khi mã phòng thay đổi.
     * Snapshot thường không được tự kết thúc pending LEAVE.
     */
    private void applyRoom(RoomUpdateDto room) {
        if (room == null || room.roomCode() == null || room.players() == null || playerId == null) {
            throw new IllegalArgumentException("Invalid ROOM_UPDATE");
        }

        boolean member = false;
        for (PlayerDto player : room.players()) {
            if (playerId.equals(player.id())) {
                member = true;
                break;
            }
        }
        if (!member) {
            throw new IllegalArgumentException("Snapshot does not contain this player");
        }

        boolean changed = !Objects.equals(currentRoomCode, room.roomCode());
        if (changed) {
            closeChoice();
            closeResult();
            gameView.clearRoom();
        }

        currentRoomCode = room.roomCode();
        state = room.status();
        players = room.players();
        if (pending == Pending.CREATE || pending == Pending.JOIN
                || (pending == Pending.START && state != RoomStatus.WAITING && state != RoomStatus.GAME_OVER)) {
            finishPending();
        }
        if (state != RoomStatus.DRAWING) {
            canDraw = false;
            gameView.getDrawingCanvas().setDrawingEnabled(false);
        }

        if (state != RoomStatus.WORD_SELECTION)
            closeChoice();
        if (state != RoomStatus.GAME_OVER)
            closeResult();

        gameView.showRoom(room, playerId);
        refreshChat();
        if (changed && onRoomJoinedCallback != null)
            onRoomJoinedCallback.accept(room);
    }

    /**
     * Drawer/người đã đoán đúng bị khóa chat khi DRAWING; spectator vẫn chat thường.
     */
    private void refreshChat() {
        PlayerDto me = findMe();
        boolean enabled = currentRoomCode != null && me != null;
        if (enabled && state == RoomStatus.DRAWING && (me.isDrawer() || me.hasGuessed())) {
            enabled = false;
        }
        if (gameView != null) {
            gameView.setChatInputEnabled(enabled);
        }
    }

    /**
     * Tìm chính mình trong snapshot đang dùng cho quyền host và quyền chat.
     */
    private PlayerDto findMe() {
        for (PlayerDto player : players) {
            if (Objects.equals(player.id(), playerId)) {
                return player;
            }
        }
        return null;
    }


    // ── 5. Dialog chọn từ và kết quả ──

    /**
     * Chỉ gửi index khi chọn thật, đúng dialog/token/epoch. Hết giờ không gửi mặc định.
     */
    private void showWordChoicePopup(WordChoicesDto dto) {
        closeChoice();
        if (dto == null || dto.choices() == null || dto.choices().size() != 3 || state != RoomStatus.WORD_SELECTION) {
            return;
        }

        WordChoiceDialog dialog = new WordChoiceDialog(dto.choices(), dto.chooseSeconds());
        choiceDialog = dialog;
        choiceTurnId = dto.turnId();
        long epoch = connectionEpoch.get();

        dialog.resultProperty().addListener((observable, oldValue, index) -> {
            if (index == null || index < 0 || index >= 3 || choiceDialog != dialog
                    || !Objects.equals(choiceTurnId, dto.turnId()) || !isCurrentEpoch(epoch)
                    || state != RoomStatus.WORD_SELECTION) { return; }
            choiceTurnId = null; choiceDialog = null;
            sendPacket(MessageType.CHOOSE_WORD, new ChooseWordDto(dto.turnId(), index));
        });

        dialog.show();
    }

    /**
     * Xóa reference/token trước khi đóng để callback dialog cũ không gửi thêm lựa chọn.
     */
    private void closeChoice() {
        WordChoiceDialog previous = choiceDialog;
        choiceDialog = null;
        choiceTurnId = null;
        if (previous != null) {
            previous.closeAndStop();
        }
    }

    /**
     * Đóng cửa sổ kết quả; không thay membership hoặc điểm.
     */
    private void closeResult() {
        if (resultDialog != null) {
            resultDialog.close();
            resultDialog = null;
        }
    }


    // ── 6. Phản hồi UI và đổi màn hình ──

    /**
     * FX thread: thêm một tin vào chat đang có.
     */
    private void append(String text) {
        if (gameView != null) {
            gameView.appendChatMessage(text);
        }
    }

    /**
     * Báo ở sảnh nếu chưa vào phòng; trong phòng dùng nhãn lỗi và chat.
     */
    private void showError(String text) {
        if (currentRoomCode == null && lobbyView != null) {
            lobbyView.showError(text);
        }
        else if (gameView != null) {
            gameView.showRoomError(text);
            append("[Lỗi] " + text);
        }
    }

    /**
     * ClientMain dùng callback này để hiện lại đúng instance LobbyView cũ.
     */
    private void notifyLeft() {
        if (onRoomLeftCallback != null) {
            onRoomLeftCallback.run();
        }
    }


    // ── 7. Mất kết nối, cleanup và các kiểm tra luồng/phiên ──

    /**
     * Reader/writer/connector báo lỗi kèm epoch; chỉ phiên còn hiện hành được dọn UI.
     */
    public void onConnectionLost(String reason, long epoch) {
        Platform.runLater(() -> {
            if (!isCurrentEpoch(epoch)) {
                return;
            }
            connectionEpoch.incrementAndGet();
            socketClient.disconnect(epoch);
            playerId = null;
            pendingRoomRequest = null;
            offline = false;
            finishPending();
            clearRoom();
            notifyLeft();
            showError(reason);
        });
    }

    /**
     * FX thread: dọn dialog và state/UI phòng; không xóa playerId của kết nối vẫn còn sống.
     */
    private void clearRoom() {
        closeChoice();
        closeResult();
        currentRoomCode = null;
        state = RoomStatus.WAITING;
        players = List.of();
        canDraw = false;
        if (gameView != null) {
            gameView.clearRoom();
        }
    }

    /**
     * Dừng lặp an toàn khi đóng Stage: vô hiệu phiên cũ, đóng socket/dialog và hai executor.
     */
    public void shutdown() {
        if (shutdown)
            return;
        shutdown = true;
        connectionEpoch.incrementAndGet();
        socketClient.disconnect();
        closeChoice();
        closeResult();
        connector.shutdownNow();
        timeout.shutdownNow();
    }

    /**
     * Reader/connector dùng kiểm tra AtomicLong, không đọc state UI từ luồng nền.
     */
    public boolean isCurrentEpoch(long epoch) {
        return connectionEpoch.get() == epoch;
    }

    /**
     * Phát hiện caller gọi luồng nghiệp vụ/UI từ ngoài JavaFX Application Thread.
     */
    private void requireFx() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Use FX thread");
        }
    }

}
