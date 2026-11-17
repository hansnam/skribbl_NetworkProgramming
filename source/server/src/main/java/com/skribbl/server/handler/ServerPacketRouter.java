package com.skribbl.server.handler;

import com.skribbl.common.dto.*;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.common.protocol.NetworkMessage;
import com.skribbl.server.net.ClientSession;
import com.skribbl.server.room.GameRoom;
import com.skribbl.server.room.RoomManager;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Routes and handles incoming NDJSON packets from connected ClientSessions.
 * Provides extension hooks for GameEngine and CanvasSyncService.
 */
public class ServerPacketRouter {

    private final RoomManager roomManager;

    // Optional extension hooks for TV2 (GameEngine) and TV3 (CanvasSyncService)
    private BiConsumer<GameRoom, ClientSession> startGameHandler;
    private BiConsumer<GameRoom, DrawStrokeDto> strokeRecordedHandler;
    private Consumer<ClientSession> clearCanvasHandler;
    private BiConsumer<ClientSession, ChatSendDto> chatGuessHandler;
    private BiConsumer<ClientSession, ChooseWordDto> wordChoiceHandler;
    private BiConsumer<ClientSession, DrawStrokeDto> drawStrokeHandler;

    public ServerPacketRouter(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    public void setStartGameHandler(BiConsumer<GameRoom, ClientSession> startGameHandler) {
        this.startGameHandler = startGameHandler;
    }

    public void setStrokeRecordedHandler(BiConsumer<GameRoom, DrawStrokeDto> strokeRecordedHandler) {
        this.strokeRecordedHandler = strokeRecordedHandler;
    }

    public void setClearCanvasHandler(Consumer<ClientSession> clearCanvasHandler) {
        this.clearCanvasHandler = clearCanvasHandler;
    }

    public void setDrawStrokeHandler(BiConsumer<ClientSession, DrawStrokeDto> handler) {
        drawStrokeHandler = handler;
    }

    public void setChatGuessHandler(BiConsumer<ClientSession, ChatSendDto> chatGuessHandler) {
        this.chatGuessHandler = chatGuessHandler;
    }

    public void setWordChoiceHandler(BiConsumer<ClientSession, ChooseWordDto> wordChoiceHandler) {
        this.wordChoiceHandler = wordChoiceHandler;
    }

    /**
     * Định tuyến gói tin: Đọc loại gói tin NetworkMessage.type() và phân phối tới hàm xử lý tương ứng.
     *
     * @param session Phiên kết nối mạng của client (ClientSession).
     * @param message Gói tin nhận được (NetworkMessage).
     */
    public void route(ClientSession session, NetworkMessage message) {
        if (message == null || message.type() == null) {
            return;
        }

        // Bắt buộc client phải gửi CONNECT_REQ thành công trước khi gửi các gói tin khác
        if (session.getPlayerId() == null && message.type() != MessageType.CONNECT_REQ) {
            session.sendError("UNAUTHENTICATED", "Send CONNECT_REQ first");
            return;
        }

        switch (message.type()) {
            case CONNECT_REQ -> handleConnectReq(session, message);
            case CREATE_ROOM -> handleCreateRoom(session, message);
            case JOIN_ROOM -> handleJoinRoom(session, message);
            case LEAVE_ROOM -> handleLeaveRoom(session);
            case START_GAME -> handleStartGame(session);
            case DRAW_STROKE -> handleDrawStroke(session, message);
            case CLEAR_CANVAS -> handleClearCanvas(session, message);
            case CHAT_SEND -> handleChatSend(session, message);
            case CHOOSE_WORD -> handleChooseWord(session, message);
            default -> System.out.printf("[PacketRouter] Unhandled or forward-only message type: %s from %s%n",
                    message.type(), session.getSessionId());
        }
    }

    private void handleConnectReq(ClientSession session, NetworkMessage message) {
        // Nếu session đã có playerId (đã đăng ký rồi) thì không cho đăng ký lại
        if (session.getPlayerId() != null) {
            session.sendError("ALREADY_CONNECTED", "Nickname already registered");
            return;
        }

        ConnectReqDto dto = JsonCodec.decodePayload(message, ConnectReqDto.class);
        if (dto == null || dto.nickname() == null || dto.nickname().trim().isEmpty()) {
            session.sendError("INVALID_NICKNAME", "Nickname cannot be empty");
            return;
        }

        String trimmed = dto.nickname().trim();
        if (trimmed.length() > 20) {
            trimmed = trimmed.substring(0, 20);
        }

        String playerId = "P-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        session.setPlayerId(playerId);
        session.setNickname(trimmed);

        System.out.printf("[PacketRouter] Registered player: %s (%s)%n", playerId, trimmed);
        session.send(MessageType.CONNECT_ACK, new ConnectAckDto(playerId, trimmed));
    }

    private void handleCreateRoom(ClientSession session, NetworkMessage message) {
        if (session.getPlayerId() == null) {
            session.sendError("UNAUTHENTICATED", "Please connect first with CONNECT_REQ");
            return;
        }

        CreateRoomReqDto dto = JsonCodec.decodePayload(message, CreateRoomReqDto.class);
        int maxRounds = (dto != null && dto.maxRounds() > 0) ? dto.maxRounds() : 3;
        int drawTimeSec = (dto != null && dto.drawTimeSec() > 0) ? dto.drawTimeSec() : 60;

        // Kiểm tra tính hợp lệ của cấu hình phòng (số vòng 1..10, thời gian vẽ 15..180 giây)
        if (maxRounds > 10 || drawTimeSec < 15 || drawTimeSec > 180) {
            session.sendError("INVALID_ROOM_SETTINGS", "Rounds 1..10, seconds 15..180");
            return;
        }

        try {
            roomManager.createRoom(session, maxRounds, drawTimeSec);
        } catch (Exception e) {
            session.sendError("CREATE_ROOM_FAILED", e.getMessage());
        }
    }

    private void handleJoinRoom(ClientSession session, NetworkMessage message) {
        if (session.getPlayerId() == null) {
            session.sendError("UNAUTHENTICATED", "Please connect first with CONNECT_REQ");
            return;
        }

        JoinRoomReqDto dto = JsonCodec.decodePayload(message, JoinRoomReqDto.class);
        if (dto == null || dto.roomCode() == null) {
            session.sendError("INVALID_ROOM_CODE", "Room code is required");
            return;
        }

        StringBuilder errorReason = new StringBuilder();
        boolean joined = roomManager.joinRoom(dto.roomCode(), session, errorReason);
        if (!joined) {
            session.sendError("JOIN_FAILED", errorReason.toString());
        }
    }

    /**
     * Xử lý khi client chủ động gửi gói tin LEAVE_ROOM (bấm nút "Rời phòng").
     * Tham số true yêu cầu RoomManager gửi lại gói tin LEAVE_ROOM làm ACK xác nhận cho client.
     *
     * @param session Phiên mạng của người chơi bấm rời phòng (ClientSession).
     */
    private void handleLeaveRoom(ClientSession session) {
        roomManager.leaveRoom(session, true);
    }

    /**
     * Xử lý khi client gửi gói tin START_GAME (bấm nút "Bắt đầu ván").
     *
     * @param session Phiên mạng của người bấm nút Bắt đầu (ClientSession).
     */
    private void handleStartGame(ClientSession session) {
        GameRoom room = roomManager.getRoom(session.getCurrentRoomCode());
        if (room == null) {
            session.sendError("NOT_IN_ROOM", "Join a room first");
            return;
        }
        if (startGameHandler == null) {
            session.sendError("GAME_NOT_READY", "Start handler is not connected");
            return;
        }
        startGameHandler.accept(room, session);
    }

    /**
     * Xử lý khi nhận gói tin nét vẽ DRAW_STROKE từ client:
     * Giải mã payload thành DrawStrokeDto và chuyển cho drawStrokeHandler (GameService).
     *
     * @param session Phiên mạng của người vẽ (ClientSession).
     * @param message Gói tin chứa payload nét vẽ (NetworkMessage).
     */
    private void handleDrawStroke(ClientSession session, NetworkMessage message) {
        if (drawStrokeHandler == null) {
            session.sendError("GAME_NOT_READY", "Drawing handler is not connected");
            return;
        }
        DrawStrokeDto dto = JsonCodec.decodePayload(message, DrawStrokeDto.class);
        drawStrokeHandler.accept(session, dto);
    }

    /**
     * Xử lý khi nhận gói tin CLEAR_CANVAS từ client.
     *
     * @param session Phiên mạng của người gửi yêu cầu xóa bảng (ClientSession).
     * @param message Gói tin yêu cầu (NetworkMessage).
     */
    private void handleClearCanvas(ClientSession session, NetworkMessage message) {
        if (clearCanvasHandler == null) {
            session.sendError("GAME_NOT_READY", "Clear handler is not connected");
            return;
        }
        clearCanvasHandler.accept(session);
    }

    /**
     * Xử lý khi nhận gói tin CHAT_SEND (tin nhắn chat hoặc đoán từ) từ client.
     *
     * @param session Phiên mạng của người gửi tin (ClientSession).
     * @param message Gói tin chứa nội dung chat (NetworkMessage).
     */
    private void handleChatSend(ClientSession session, NetworkMessage message) {
        if (chatGuessHandler == null) {
            session.sendError("GAME_NOT_READY", "Chat handler is not connected");
            return;
        }
        chatGuessHandler.accept(session, JsonCodec.decodePayload(message, ChatSendDto.class));
    }

    /**
     * Xử lý khi người vẽ chọn từ khóa (CHOOSE_WORD).
     * Kiểm tra choiceIndex phải là số nguyên hợp lệ trước khi gọi handler.
     *
     * @param session Phiên mạng của người vẽ (ClientSession).
     * @param message Gói tin chứa DTO chọn từ (NetworkMessage).
     */
    private void handleChooseWord(ClientSession session, NetworkMessage message) {
        // Kiểm tra tính hợp lệ của choiceIndex trong payload JSON
        if (message.payload() == null || !message.payload().hasNonNull("choiceIndex")
                || !message.payload().get("choiceIndex").isIntegralNumber()
                || !message.payload().get("choiceIndex").canConvertToInt()) {
            session.sendError("BAD_PAYLOAD", "choiceIndex must be an integer");
            return;
        }

        if (wordChoiceHandler == null) {
            session.sendError("GAME_NOT_READY", "Choice handler is not connected");
            return;
        }
        wordChoiceHandler.accept(session, JsonCodec.decodePayload(message, ChooseWordDto.class));
    }
}

