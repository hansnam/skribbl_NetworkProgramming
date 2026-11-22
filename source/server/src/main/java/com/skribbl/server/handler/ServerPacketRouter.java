package com.skribbl.server.handler;

import com.skribbl.common.dto.*;
import com.skribbl.common.model.RoomType;
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

    private BiConsumer<GameRoom, ClientSession> startGameHandler;
    private BiConsumer<GameRoom, DrawStrokeDto> strokeRecordedHandler;
    private Consumer<ClientSession> clearCanvasHandler;
    private Consumer<ClientSession> undoCanvasHandler;
    private BiConsumer<ClientSession, FillCanvasDto> fillCanvasHandler;
    private BiConsumer<ClientSession, DrawReactionDto> reactionHandler;
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

    public void setUndoCanvasHandler(Consumer<ClientSession> undoCanvasHandler) {
        this.undoCanvasHandler = undoCanvasHandler;
    }

    public void setFillCanvasHandler(BiConsumer<ClientSession, FillCanvasDto> fillCanvasHandler) {
        this.fillCanvasHandler = fillCanvasHandler;
    }

    public void setReactionHandler(BiConsumer<ClientSession, DrawReactionDto> reactionHandler) {
        this.reactionHandler = reactionHandler;
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

    public void route(ClientSession session, NetworkMessage message) {
        if (message == null || message.type() == null) {
            return;
        }

        if (session.getPlayerId() == null && message.type() != MessageType.CONNECT_REQ) {
            session.sendError("UNAUTHENTICATED", "Send CONNECT_REQ first");
            return;
        }

        switch (message.type()) {
            case CONNECT_REQ -> handleConnectReq(session, message);
            case QUICK_JOIN -> handleQuickJoin(session);
            case CREATE_ROOM -> handleCreateRoom(session, message);
            case JOIN_ROOM -> handleJoinRoom(session, message);
            case LEAVE_ROOM -> handleLeaveRoom(session);
            case START_GAME -> handleStartGame(session);
            case DRAW_STROKE -> handleDrawStroke(session, message);
            case FILL_CANVAS -> handleFillCanvas(session, message);
            case UNDO_CANVAS -> handleUndoCanvas(session);
            case CLEAR_CANVAS -> handleClearCanvas(session, message);
            case DRAW_REACTION -> handleDrawReaction(session, message);
            case CHAT_SEND -> handleChatSend(session, message);
            case CHOOSE_WORD -> handleChooseWord(session, message);
            default -> System.out.printf("[PacketRouter] Unhandled or forward-only message type: %s from %s%n",
                    message.type(), session.getSessionId());
        }
    }

    private void handleConnectReq(ClientSession session, NetworkMessage message) {
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
        session.setAvatar(dto.avatar());

        System.out.printf("[PacketRouter] Registered player: %s (%s)%n", playerId, trimmed);
        session.send(MessageType.CONNECT_ACK, new ConnectAckDto(playerId, trimmed));
    }

    private void handleQuickJoin(ClientSession session) {
        if (session.getPlayerId() == null) {
            session.sendError("UNAUTHENTICATED", "Please connect first with CONNECT_REQ");
            return;
        }

        StringBuilder errorReason = new StringBuilder();
        GameRoom room = roomManager.quickJoin(session, errorReason);
        if (room == null) {
            session.sendError("QUICK_JOIN_FAILED", errorReason.toString());
        }
    }

    private void handleCreateRoom(ClientSession session, NetworkMessage message) {
        if (session.getPlayerId() == null) {
            session.sendError("UNAUTHENTICATED", "Please connect first with CONNECT_REQ");
            return;
        }

        CreateRoomReqDto dto = JsonCodec.decodePayload(message, CreateRoomReqDto.class);
        int maxRounds = (dto != null && dto.maxRounds() > 0) ? dto.maxRounds() : 3;
        int drawTimeSec = (dto != null && dto.drawTimeSec() > 0) ? dto.drawTimeSec() : 60;

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

    private void handleLeaveRoom(ClientSession session) {
        roomManager.leaveRoom(session, true);
    }

    private void handleStartGame(ClientSession session) {
        GameRoom room = roomManager.getRoom(session.getCurrentRoomCode());
        if (room == null) {
            session.sendError("NOT_IN_ROOM", "Join a room first");
            return;
        }
        if (room.getRoomType() == RoomType.PUBLIC) {
            session.sendError("PERMISSION_DENIED", "Public rooms start automatically when ready");
            return;
        }
        if (room.getHostId() == null || !room.getHostId().equals(session.getPlayerId())) {
            session.sendError("PERMISSION_DENIED", "Only room host can start the game");
            return;
        }
        if (startGameHandler == null) {
            session.sendError("GAME_NOT_READY", "Start handler is not connected");
            return;
        }
        startGameHandler.accept(room, session);
    }

    private void handleDrawStroke(ClientSession session, NetworkMessage message) {
        if (drawStrokeHandler == null) {
            session.sendError("GAME_NOT_READY", "Drawing handler is not connected");
            return;
        }
        DrawStrokeDto dto = JsonCodec.decodePayload(message, DrawStrokeDto.class);
        drawStrokeHandler.accept(session, dto);
    }

    private void handleFillCanvas(ClientSession session, NetworkMessage message) {
        if (fillCanvasHandler == null) {
            session.sendError("GAME_NOT_READY", "Fill handler is not connected");
            return;
        }
        FillCanvasDto dto = JsonCodec.decodePayload(message, FillCanvasDto.class);
        fillCanvasHandler.accept(session, dto);
    }

    private void handleUndoCanvas(ClientSession session) {
        if (undoCanvasHandler == null) {
            session.sendError("GAME_NOT_READY", "Undo handler is not connected");
            return;
        }
        undoCanvasHandler.accept(session);
    }

    private void handleClearCanvas(ClientSession session, NetworkMessage message) {
        if (clearCanvasHandler == null) {
            session.sendError("GAME_NOT_READY", "Clear handler is not connected");
            return;
        }
        clearCanvasHandler.accept(session);
    }

    private void handleDrawReaction(ClientSession session, NetworkMessage message) {
        if (reactionHandler == null) {
            return;
        }
        DrawReactionDto dto = JsonCodec.decodePayload(message, DrawReactionDto.class);
        reactionHandler.accept(session, dto);
    }

    private void handleChatSend(ClientSession session, NetworkMessage message) {
        if (chatGuessHandler == null) {
            session.sendError("GAME_NOT_READY", "Chat handler is not connected");
            return;
        }
        chatGuessHandler.accept(session, JsonCodec.decodePayload(message, ChatSendDto.class));
    }

    private void handleChooseWord(ClientSession session, NetworkMessage message) {
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
