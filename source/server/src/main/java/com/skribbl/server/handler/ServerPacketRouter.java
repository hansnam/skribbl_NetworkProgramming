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

/**
 * Routes and handles incoming NDJSON packets from connected ClientSessions.
 * Provides extension hooks for GameEngine and CanvasSyncService.
 */
public class ServerPacketRouter {

    private final RoomManager roomManager;

    // Optional extension hooks for TV2 (GameEngine) and TV3 (CanvasSyncService)
    private BiConsumer<GameRoom, ClientSession> startGameHandler;
    private BiConsumer<GameRoom, DrawStrokeDto> strokeRecordedHandler;
    private Runnable clearCanvasHandler;
    private BiConsumer<ClientSession, ChatSendDto> chatGuessHandler;
    private BiConsumer<ClientSession, ChooseWordDto> wordChoiceHandler;

    public ServerPacketRouter(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    public void setStartGameHandler(BiConsumer<GameRoom, ClientSession> startGameHandler) {
        this.startGameHandler = startGameHandler;
    }

    public void setStrokeRecordedHandler(BiConsumer<GameRoom, DrawStrokeDto> strokeRecordedHandler) {
        this.strokeRecordedHandler = strokeRecordedHandler;
    }

    public void setClearCanvasHandler(Runnable clearCanvasHandler) {
        this.clearCanvasHandler = clearCanvasHandler;
    }

    public void setChatGuessHandler(BiConsumer<ClientSession, ChatSendDto> chatGuessHandler) {
        this.chatGuessHandler = chatGuessHandler;
    }

    public void setWordChoiceHandler(BiConsumer<ClientSession, ChooseWordDto> wordChoiceHandler) {
        this.wordChoiceHandler = wordChoiceHandler;
    }

    /**
     * Main packet dispatcher matching by MessageType.
     */
    public void route(ClientSession session, NetworkMessage message) {
        if (message == null || message.type() == null) {
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
        roomManager.leaveRoom(session);
    }

    private void handleStartGame(ClientSession session) {
        String roomCode = session.getCurrentRoomCode();
        if (roomCode == null) {
            session.sendError("NOT_IN_ROOM", "You are not in any room");
            return;
        }

        GameRoom room = roomManager.getRoom(roomCode);
        if (room == null) {
            session.sendError("ROOM_NOT_FOUND", "Room not found");
            return;
        }

        if (!room.getHostId().equals(session.getPlayerId())) {
            session.sendError("PERMISSION_DENIED", "Only room host can start the game");
            return;
        }

        if (room.getPlayerCount() < 2) {
            session.sendError("NOT_ENOUGH_PLAYERS", "Need at least 2 players to start game");
            return;
        }

        if (startGameHandler != null) {
            startGameHandler.accept(room, session);
        } else {
            // Default placeholder if GameEngine is not yet hooked up
            room.broadcastSystemChat("Game starting by Host " + session.getNickname() + "!");
        }
    }

    private void handleDrawStroke(ClientSession session, NetworkMessage message) {
        String roomCode = session.getCurrentRoomCode();
        if (roomCode == null) return;

        GameRoom room = roomManager.getRoom(roomCode);
        if (room == null) return;

        // Relay stroke to all other players in the room
        room.broadcastExcept(session.getPlayerId(), message);

        if (strokeRecordedHandler != null) {
            DrawStrokeDto dto = JsonCodec.decodePayload(message, DrawStrokeDto.class);
            if (dto != null) {
                strokeRecordedHandler.accept(room, dto);
            }
        }
    }

    private void handleClearCanvas(ClientSession session, NetworkMessage message) {
        String roomCode = session.getCurrentRoomCode();
        if (roomCode == null) return;

        GameRoom room = roomManager.getRoom(roomCode);
        if (room == null) return;

        room.broadcastExcept(session.getPlayerId(), message);

        if (clearCanvasHandler != null) {
            clearCanvasHandler.run();
        }
    }

    private void handleChatSend(ClientSession session, NetworkMessage message) {
        ChatSendDto dto = JsonCodec.decodePayload(message, ChatSendDto.class);
        if (dto == null || dto.text() == null || dto.text().isBlank()) {
            return;
        }

        if (chatGuessHandler != null) {
            chatGuessHandler.accept(session, dto);
            return;
        }

        // Default: broadcast chat to everyone in the room
        String roomCode = session.getCurrentRoomCode();
        if (roomCode == null) return;

        GameRoom room = roomManager.getRoom(roomCode);
        if (room == null) return;

        ChatMessageDto chat = new ChatMessageDto(session.getNickname(), dto.text().trim(), ChatKind.NORMAL);
        room.broadcast(NetworkMessage.of(MessageType.CHAT_MESSAGE, JsonCodec.toNode(chat)));
    }

    private void handleChooseWord(ClientSession session, NetworkMessage message) {
        ChooseWordDto dto = JsonCodec.decodePayload(message, ChooseWordDto.class);
        if (dto != null && wordChoiceHandler != null) {
            wordChoiceHandler.accept(session, dto);
        }
    }
}

