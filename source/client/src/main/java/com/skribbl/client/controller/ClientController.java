package com.skribbl.client.controller;

import com.skribbl.client.net.SocketClient;
import com.skribbl.client.ui.audio.SoundManager;
import com.skribbl.client.ui.game.GameOverDialog;
import com.skribbl.client.ui.game.GameView;
import com.skribbl.client.ui.game.WordChoiceDialog;
import com.skribbl.client.ui.lobby.LobbyView;
import com.skribbl.common.dto.*;
import com.skribbl.common.model.AvatarDto;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;
import com.skribbl.common.model.RoomType;
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
 * Điều phối phiên và UI của một client.
 * Hỗ trợ Chơi nhanh (QUICK_JOIN), Phòng riêng (CREATE/JOIN), Avatar, Flood Fill, Undo,
 * In-Canvas Overlays và Hiệu ứng Âm thanh (SoundManager).
 */
public class ClientController {
    private enum Pending { NONE, CONNECT, CREATE, JOIN, START, LEAVE }

    private final SocketClient socketClient = new SocketClient();
    private final ExecutorService connector = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timeout = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong connectionEpoch = new AtomicLong();

    private String nickname;
    private AvatarDto avatar = AvatarDto.DEFAULT;
    private AvatarDto registeredAvatar;
    private String playerId;
    private String connectedHost;
    private int connectedPort;
    private String currentRoomCode;
    private RoomType currentRoomType = RoomType.PRIVATE;
    private RoomStatus state = RoomStatus.WAITING;
    private List<PlayerDto> players = List.of();
    private boolean canDraw;
    private boolean canGuess;
    private boolean offline;
    private boolean shutdown;

    private Pending pending = Pending.NONE;
    private long pendingVersion;
    private Runnable pendingRoomRequest;

    private GameView gameView;
    private LobbyView lobbyView;
    private Consumer<RoomUpdateDto> onRoomJoinedCallback;
    private Runnable onRoomLeftCallback;
    private GameOverDialog resultDialog;
    private WordChoiceDialog choiceDialog;
    private String choiceTurnId;

    public ClientController(String nickname) {
        this.nickname = nickname;
    }

    public void setGameView(GameView gameView) {
        this.gameView = gameView;
    }

    public void setLobbyView(LobbyView view) {
        lobbyView = view;
    }

    public void setOnRoomJoinedCallback(Consumer<RoomUpdateDto> callback) {
        this.onRoomJoinedCallback = callback;
    }

    public void setOnRoomLeftCallback(Runnable callback) {
        this.onRoomLeftCallback = callback;
    }

    public String getNickname() {
        return nickname;
    }

    public boolean isRegistered() {
        return playerId != null;
    }

    public AvatarDto getAvatar() {
        return avatar;
    }

    public void setAvatar(AvatarDto avatar) {
        this.avatar = avatar != null ? avatar : AvatarDto.DEFAULT;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getCurrentRoomCode() {
        return currentRoomCode;
    }

    public void setNickname(String value) {
        if (playerId == null && value != null && !value.isBlank()) {
            nickname = value.strip();
        }
    }

    public boolean isHost() {
        PlayerDto me = findMe();
        return me != null && me.isHost();
    }

    // ── 2. Kết nối và request từ sảnh ──

    public void requestCreateRoom(String host, int port, String name, int rounds, int seconds) {
        requestRoom(host, port, name, Pending.CREATE,
                () -> sendPacket(MessageType.CREATE_ROOM, new CreateRoomReqDto(rounds, seconds)));
    }

    public void requestQuickJoin(String host, int port, String name) {
        requestRoom(host, port, name, Pending.JOIN,
                () -> sendPacket(MessageType.QUICK_JOIN, null));
    }

    public void requestJoinRoom(String host, int port, String name, String code) {
        requestRoom(host, port, name, Pending.JOIN,
                () -> sendPacket(MessageType.JOIN_ROOM, new JoinRoomReqDto(code)));
    }

    public void connectToServer(String host, int port) throws IOException {
        requestRoom(host, port, nickname, Pending.CONNECT, () -> {});
    }

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

        if (playerId != null && socketClient.isConnected()) {
            if (!host.equalsIgnoreCase(connectedHost) || port != connectedPort || !name.equals(nickname)) {
                showError("Phiên hiện tại giữ host/nickname cũ. Ngắt kết nối trước khi đổi.");
                return;
            }
            if (Objects.equals(avatar, registeredAvatar)) {
                beginPending(kind);
                request.run();
                if (kind == Pending.CONNECT) {
                    finishPending();
                }
                return;
            }
        }

        nickname = name.strip();
        connectedHost = host;
        connectedPort = port;
        long epoch = connectionEpoch.incrementAndGet();
        socketClient.disconnect();
        playerId = null;
        pendingRoomRequest = request;
        beginPending(kind);
        String registeredName = nickname;
        AvatarDto selectedAvatar = avatar;
        registeredAvatar = selectedAvatar;

        connector.execute(() -> {
            try {
                socketClient.connect(host, port, this, epoch);
                if (isCurrentEpoch(epoch) && !socketClient.send(MessageType.CONNECT_REQ,
                        new ConnectReqDto(registeredName, selectedAvatar))) {
                    onConnectionLost("Không gửi được CONNECT_REQ", epoch);
                }
            } catch (IOException | RuntimeException ex) {
                onConnectionLost(ex.getMessage(), epoch);
            }
        });
    }

    private void beginPending(Pending kind) {
        pending = kind;
        long version = ++pendingVersion;
        long epoch = connectionEpoch.get();
        setBusy(true);

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

    private void finishPending() {
        pending = Pending.NONE;
        pendingVersion++;
        setBusy(false);
    }

    private void setBusy(boolean busy) {
        if (lobbyView != null)
            lobbyView.setBusy(busy);
        if (gameView != null)
            gameView.setRoomBusy(busy);
    }

    private void sendPacket(MessageType type, Object payload) {
        if (!socketClient.send(type, payload)) {
            onConnectionLost("Không gửi được dữ liệu; kết nối đã đóng hoặc hàng gửi đầy.", connectionEpoch.get());
        }
    }

    // ── 3. Thao tác trong phòng và chế độ vẽ thử ──

    public void createRoom(int rounds, int seconds) {
        if (playerId == null || currentRoomCode != null || pending != Pending.NONE)
            return;
        beginPending(Pending.CREATE);
        sendPacket(MessageType.CREATE_ROOM, new CreateRoomReqDto(rounds, seconds));
    }

    public void joinRoom(String code) {
        if (playerId == null || currentRoomCode != null || pending != Pending.NONE)
            return;
        beginPending(Pending.JOIN);
        sendPacket(MessageType.JOIN_ROOM, new JoinRoomReqDto(code.strip().toUpperCase(Locale.ROOT)));
    }

    public void startGame() {
        if (pending != Pending.NONE || currentRoomCode == null || !isHost()
                || players.size() < 2 || (state != RoomStatus.WAITING && state != RoomStatus.GAME_OVER))
            return;
        beginPending(Pending.START);
        sendPacket(MessageType.START_GAME, null);
    }

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

    private void handleSlashCommand(String text) {
        String[] args = text.split("\\s+");
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "/start" -> startGame();
            case "/leave" -> leaveRoom();
            case "/create", "/join" -> showError("Rời phòng, dùng nút Tạo/Vào phòng trên sảnh.");
            default -> showError("Lệnh hỗ trợ trong phòng: /start, /leave.");
        }
    }

    public void sendDrawStroke(DrawStrokeDto dto) {
        if (offline)
            return;
        if (canDraw)
            sendPacket(MessageType.DRAW_STROKE, dto);
    }

    public void sendFillCanvas(FillCanvasDto dto) {
        if (offline)
            return;
        if (canDraw)
            sendPacket(MessageType.FILL_CANVAS, dto);
    }

    public void sendUndoCanvas() {
        if (offline) {
            if (gameView != null) {
                gameView.getDrawingCanvas().undoLocalOffline();
            }
            return;
        }
        if (canDraw)
            sendPacket(MessageType.UNDO_CANVAS, null);
    }

    public void sendClearCanvas() {
        if (offline) {
            gameView.getDrawingCanvas().clearCanvasLocal();
            return;
        }
        if (canDraw)
            sendPacket(MessageType.CLEAR_CANVAS, null);
    }

    public void sendReaction(boolean like) {
        if (offline || currentRoomCode == null || canDraw || state != RoomStatus.DRAWING)
            return;
        sendPacket(MessageType.DRAW_REACTION, new DrawReactionDto(nickname, like));
    }

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

    // ── 4. Nhận packet ──

    public void handleIncomingMessage(NetworkMessage message, long epoch) {
        if (message == null || message.type() == null || !isCurrentEpoch(epoch))
            return;

        if (message.type() == MessageType.PING) {
            if (!socketClient.send(MessageType.PONG, null))
                onConnectionLost("Không gửi được PONG", epoch);
            return;
        }

        Platform.runLater(() -> {
            if (!isCurrentEpoch(epoch))
                return;

            try {
                applyIncomingOnFxThread(message);
            } catch (RuntimeException ex) {
                System.err.println("Packet handler failed: " + ex);
                onConnectionLost("Dữ liệu server không hợp lệ: " + ex.getMessage(), epoch);
            }
        });
    }

    private void applyIncomingOnFxThread(NetworkMessage message) {
        requireFx();
        switch (message.type()) {
            case CONNECT_ACK -> {
                ConnectAckDto ack = JsonCodec.decodePayload(message, ConnectAckDto.class);
                if (ack == null || ack.playerId() == null || ack.playerId().isBlank()) {
                    throw new IllegalArgumentException("CONNECT_ACK has no playerId");
                }

                playerId = ack.playerId();
                if (ack.nickname() != null)
                    nickname = ack.nickname();

                Runnable request = pendingRoomRequest;
                pendingRoomRequest = null;

                if (request != null)
                    request.run();
                if (pending == Pending.CONNECT)
                    finishPending();
            }

            case ROOM_UPDATE -> applyRoom(JsonCodec.decodePayload(message, RoomUpdateDto.class));

            case LEAVE_ROOM -> {
                if (currentRoomCode == null) {
                    return;
                }
                finishPending();
                clearRoom();
                notifyLeft();
            }

            case CHAT_MESSAGE -> {
                ChatMessageDto chat = JsonCodec.decodePayload(message, ChatMessageDto.class);
                String prefix;
                if (chat.kind() == ChatKind.NORMAL) {
                    prefix = chat.sender() + ": ";
                } else if (chat.kind() == ChatKind.CLOSE_GUESS) {
                    prefix = "[Gần đúng] ";
                    SoundManager.playCloseGuess();
                } else {
                    prefix = "[Hệ thống] ";
                }
                append(prefix + chat.text());
            }

            case WORD_CHOICES -> showWordChoicePopup(JsonCodec.decodePayload(message, WordChoicesDto.class));

            case TURN_START -> {
                closeChoice();
                if (gameView != null) {
                    gameView.getDrawingCanvas().hideOverlay();
                    gameView.getPlayerListPanel().clearTurnDeltas();
                    gameView.getPlayerListPanel().updatePlayers(players);
                }
                state = RoomStatus.DRAWING;
                TurnStartDto turn = JsonCodec.decodePayload(message, TurnStartDto.class);
                canDraw = Objects.equals(playerId, turn.drawerId());
                canGuess = turn.canGuess();
                gameView.getDrawingCanvas().clearCanvasLocal();
                gameView.getDrawingCanvas().setDrawingEnabled(canDraw);
                gameView.getDrawingCanvas().setReactionBarVisible(!canDraw);
                gameView.getTopStatusBar().updateTurnInfo("Vòng " + turn.round() + "/" + turn.maxRounds()
                        + " • Người vẽ: " + turn.drawerNickname(), turn.wordDisplay(), turn.drawTimeSec());
                updateRoleBanner(canGuess);
                refreshChat();
                SoundManager.playTurnStart();
            }

            case TIMER_UPDATE -> {
                TimerUpdateDto timer = JsonCodec.decodePayload(message, TimerUpdateDto.class);
                gameView.getTopStatusBar().updateTimerAndMask(timer.remainingSec(), timer.wordMask(), canDraw);
                if (timer.remainingSec() > 0 && timer.remainingSec() <= 10) {
                    SoundManager.playTickLow();
                }
                if (timer.remainingSec() == 0) {
                    canDraw = false;
                    canGuess = false;
                    gameView.getDrawingCanvas().setDrawingEnabled(false);
                    gameView.getDrawingCanvas().setReactionBarVisible(false);
                }
            }

            case DRAW_STROKE -> gameView.getDrawingCanvas().renderRemoteStroke(
                    JsonCodec.decodePayload(message, DrawStrokeDto.class));

            case FILL_CANVAS -> gameView.getDrawingCanvas().renderRemoteFill(
                    JsonCodec.decodePayload(message, FillCanvasDto.class));

            case CLEAR_CANVAS -> gameView.getDrawingCanvas().clearCanvasLocal();

            case CANVAS_SYNC -> gameView.getDrawingCanvas().renderSyncHistory(
                    JsonCodec.decodePayload(message, CanvasSyncDto.class).strokes());

            case DRAW_REACTION -> {
                DrawReactionDto reaction = JsonCodec.decodePayload(message, DrawReactionDto.class);
                if (reaction != null && gameView != null) {
                    String icon = reaction.like() ? "👍" : "👎";
                    String verb = reaction.like() ? "thích" : "chê";
                    gameView.getDrawingCanvas().showReactionToast(icon + " " + reaction.sender() + " đã " + verb + " bức vẽ!");
                    SoundManager.playReaction(reaction.like());
                }
            }

            case PLAYER_GUESSED -> {
                PlayerGuessedDto dto = JsonCodec.decodePayload(message, PlayerGuessedDto.class);
                players = dto.players();
                gameView.getPlayerListPanel().recordTurnDelta(dto.playerId(), dto.awardedPoints());
                PlayerDto drawer = players.stream().filter(PlayerDto::isDrawer).findFirst().orElse(null);
                if (drawer != null) {
                    gameView.getPlayerListPanel().recordTurnDelta(drawer.id(), dto.drawerBonus());
                }
                gameView.getPlayerListPanel().updatePlayers(players);
                updateRoleBanner(canGuess);
                refreshChat();
                append("[Hệ thống] 🎉 " + dto.nickname() + " đã đoán đúng! +" + dto.awardedPoints());
                SoundManager.playCorrectGuess();
            }

            case TURN_END -> {
                closeChoice();
                state = RoomStatus.TURN_REVEAL;
                canDraw = false;
                canGuess = false;
                TurnEndDto dto = JsonCodec.decodePayload(message, TurnEndDto.class);
                players = dto.players();
                gameView.getPlayerListPanel().setTurnDeltas(dto.turnScores());
                gameView.getPlayerListPanel().updatePlayers(players);
                gameView.getDrawingCanvas().setDrawingEnabled(false);
                gameView.getDrawingCanvas().setReactionBarVisible(false);
                gameView.getDrawingCanvas().showTurnRevealOverlay(dto);
                refreshChat();
                if (dto.word() == null || dto.word().isEmpty()) {
                    append("[Hệ thống] Lượt hủy trước khi chọn từ.");
                } else {
                    append("[Hệ thống] Đáp án: " + dto.word() + " — " + dto.reason());
                }
                SoundManager.playTurnEnd();
            }

            case GAME_OVER -> {
                closeChoice();
                closeResult();
                state = RoomStatus.GAME_OVER;
                canDraw = false;
                canGuess = false;
                GameOverDto dto = JsonCodec.decodePayload(message, GameOverDto.class);
                players = dto.players();
                gameView.getPlayerListPanel().updatePlayers(players);
                gameView.getDrawingCanvas().setDrawingEnabled(false);
                gameView.getDrawingCanvas().setReactionBarVisible(false);
                refreshChat();
                Runnable playAgainAction = (currentRoomType == RoomType.PRIVATE && isHost()) ? this::startGame : null;
                gameView.getDrawingCanvas().showGameOverOverlay(dto, playAgainAction);
                SoundManager.playGameOver();
            }

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

    private void updateRoleBanner(boolean canGuessFlag) {
        if (gameView == null) return;
        PlayerDto me = findMe();
        if (canDraw) {
            gameView.showRoleHint("✏️ Bạn đang là Người vẽ! Không viết chữ cái đáp án lên bảng.");
        } else if (me != null && me.hasGuessed()) {
            gameView.showRoleHint("✅ Bạn đã đoán đúng! Chờ các người chơi khác hoàn thành lượt.");
        } else if (!canGuessFlag) {
            gameView.showRoleHint("👀 Bạn vào giữa lượt vẽ — được xem và sẽ bắt đầu đoán từ lượt kế tiếp.");
        } else {
            gameView.showRoleHint("💬 Nhập đáp án tiếng Anh vào ô Chat bên phải để đoán từ khóa!");
        }
    }

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
            SoundManager.playJoin();
        }

        currentRoomCode = room.roomCode();
        currentRoomType = room.roomType() != null ? room.roomType() : RoomType.PRIVATE;
        state = room.status();
        players = room.players();
        if (pending == Pending.CREATE || pending == Pending.JOIN
                || (pending == Pending.START && state != RoomStatus.WAITING && state != RoomStatus.GAME_OVER)) {
            finishPending();
        }
        if (state != RoomStatus.DRAWING) {
            canDraw = false;
            canGuess = false;
            gameView.getDrawingCanvas().setDrawingEnabled(false);
            gameView.getDrawingCanvas().setReactionBarVisible(false);
        }

        if (state != RoomStatus.WORD_SELECTION)
            closeChoice();
        if (state != RoomStatus.GAME_OVER)
            closeResult();
        if (state == RoomStatus.WAITING) {
            gameView.getDrawingCanvas().hideOverlay();
            if (currentRoomType == RoomType.PUBLIC) {
                if (room.startInSec() != null) {
                    gameView.showRoleHint("⏳ Đủ người chơi! Ván đấu tự động bắt đầu sau " + room.startInSec() + " giây...");
                } else {
                    gameView.showRoleHint("🌐 Đang chờ thêm người chơi tham gia Chơi nhanh (" + players.size() + "/" + room.maxPlayers() + ")...");
                }
            } else {
                gameView.showRoleHint(isHost()
                        ? "👑 Bạn là Chủ phòng! Sao chép mã phòng gửi bạn bè và bấm '🚀 Bắt đầu' khi đủ >= 2 người."
                        : "⏳ Đang chờ Chủ phòng bắt đầu ván đấu (" + players.size() + "/" + room.maxPlayers() + ")...");
            }
        } else if (state == RoomStatus.WORD_SELECTION) {
            PlayerDto drawer = players.stream().filter(PlayerDto::isDrawer).findFirst().orElse(null);
            String drawerName = drawer != null ? drawer.nickname() : "Người vẽ";
            if (!Objects.equals(playerId, drawer != null ? drawer.id() : null)) {
                gameView.getDrawingCanvas().hideOverlay();
                gameView.showRoleHint("⏳ " + drawerName + " đang chọn từ khóa để vẽ...");
            }
        }

        gameView.showRoom(room, playerId);
        refreshChat();
        if (changed && onRoomJoinedCallback != null)
            onRoomJoinedCallback.accept(room);
    }

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

    private PlayerDto findMe() {
        for (PlayerDto player : players) {
            if (Objects.equals(player.id(), playerId)) {
                return player;
            }
        }
        return null;
    }

    // ── 5. Overlay chọn từ và kết quả ──

    private void showWordChoicePopup(WordChoicesDto dto) {
        closeChoice();
        if (dto == null || dto.choices() == null || dto.choices().size() != 3 || state != RoomStatus.WORD_SELECTION) {
            return;
        }

        choiceTurnId = dto.turnId();
        long epoch = connectionEpoch.get();

        if (gameView != null) {
            gameView.showRoleHint("🎨 Hãy chọn 1 trong 3 từ khóa trên bảng vẽ!");
            gameView.getDrawingCanvas().showWordChoicesOverlay(dto.choices(), dto.chooseSeconds(), index -> {
                if (index < 0 || index >= 3 || !Objects.equals(choiceTurnId, dto.turnId())
                        || !isCurrentEpoch(epoch) || state != RoomStatus.WORD_SELECTION) {
                    return;
                }
                choiceTurnId = null;
                sendPacket(MessageType.CHOOSE_WORD, new ChooseWordDto(dto.turnId(), index));
            });
        }
    }

    private void closeChoice() {
        WordChoiceDialog previous = choiceDialog;
        choiceDialog = null;
        choiceTurnId = null;
        if (previous != null) {
            previous.closeAndStop();
        }
    }

    private void closeResult() {
        if (resultDialog != null) {
            resultDialog.close();
            resultDialog = null;
        }
    }

    // ── 6. Phản hồi UI và đổi màn hình ──

    private void append(String text) {
        if (gameView != null) {
            gameView.appendChatMessage(text);
        }
    }

    private void showError(String text) {
        if (currentRoomCode == null && lobbyView != null) {
            lobbyView.showError(text);
        } else if (gameView != null) {
            gameView.showRoomError(text);
            append("[Lỗi] " + text);
        }
    }

    private void notifyLeft() {
        if (onRoomLeftCallback != null) {
            onRoomLeftCallback.run();
        }
    }

    // ── 7. Mất kết nối, cleanup và các kiểm tra luồng/phiên ──

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

    private void clearRoom() {
        closeChoice();
        closeResult();
        currentRoomCode = null;
        currentRoomType = RoomType.PRIVATE;
        state = RoomStatus.WAITING;
        players = List.of();
        canDraw = false;
        canGuess  = false;
        if (gameView != null) {
            gameView.clearRoom();
        }
    }

    public void shutdown() {
        Runnable task = () -> {
            if (shutdown) {
                return;
            }
            shutdown = true;
            connectionEpoch.incrementAndGet();
            pendingRoomRequest = null;
            offline = false;
            finishPending();
            clearRoom();
            playerId = null;
            socketClient.disconnect();
            connector.shutdownNow();
            timeout.shutdownNow();
        };
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    public boolean isCurrentEpoch(long epoch) {
        return !shutdown && epoch == connectionEpoch.get();
    }

    private static void requireFx() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Must run on JavaFX Application Thread");
        }
    }
}
