package com.skribbl.server.room;

import com.skribbl.common.dto.ChatKind;
import com.skribbl.common.dto.ChatMessageDto;
import com.skribbl.common.dto.RoomUpdateDto;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.common.protocol.NetworkMessage;
import com.skribbl.server.game.CanvasSyncService;
import com.skribbl.server.game.GameEngine;
import com.skribbl.server.net.ClientSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class GameRoom {

    private final String roomCode;
    private final int maxRounds;
    private final int drawTimeSec;
    private final Object roomLock = new Object();

    private final CanvasSyncService canvasSync = new CanvasSyncService();
    private volatile GameEngine gameEngine;

    private volatile String hostId;
    private volatile RoomStatus status = RoomStatus.WAITING;
    private volatile int currentRound = 0;
    private volatile String currentDrawerId = null;

    private final ConcurrentHashMap<String, ClientSession> memberSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlayerDto> players = new ConcurrentHashMap<>();

    public GameRoom(String roomCode, int maxRounds, int drawTimeSec) {
        this.roomCode = Objects.requireNonNull(roomCode, "roomCode cannot be null");
        this.maxRounds = maxRounds > 0 ? maxRounds : 3;
        this.drawTimeSec = drawTimeSec > 0 ? drawTimeSec : 60;
    }

    public boolean addPlayer(ClientSession session) {
        synchronized (roomLock) {
            String nickname = session.getNickname();
            if (nickname == null || nickname.isBlank()) {
                return false;
            }

            boolean duplicate = players.values().stream()
                    .anyMatch(p -> p.nickname().equalsIgnoreCase(nickname.trim()));
            if (duplicate) {
                return false;
            }

            String playerId = session.getPlayerId();
            boolean isFirst = players.isEmpty();
            if (isFirst) {
                this.hostId = playerId;
            }

            PlayerDto player = new PlayerDto(
                    playerId,
                    nickname.trim(),
                    0,
                    isFirst,
                    false,
                    false
            );

            players.put(playerId, player);
            memberSessions.put(playerId, session);
            session.setCurrentRoomCode(roomCode);

            broadcastRoomUpdate();
            broadcastSystemChat(nickname + " joined the room.");

            if (gameEngine != null && status != RoomStatus.WAITING) {
                gameEngine.onMemberJoined(session);
            }
            return true;
        }
    }

    public int removePlayer(String playerId) {
        synchronized (roomLock) {
            ClientSession session = memberSessions.remove(playerId);
            PlayerDto removed = players.remove(playerId);

            if (session != null) {
                session.setCurrentRoomCode(null);
            }

            if (removed == null) {
                return players.size();
            }

            broadcastSystemChat(removed.nickname() + " left the room.");

            if (Objects.equals(hostId, playerId)) {
                if (!players.isEmpty()) {
                    String nextHostId = players.keySet().iterator().next();
                    this.hostId = nextHostId;
                    PlayerDto nextHost = players.get(nextHostId);
                    if (nextHost != null) {
                        PlayerDto updated = nextHost.withHost(true);
                        players.put(nextHostId, updated);
                        broadcastSystemChat(updated.nickname() + " is now the room host.");
                    }
                } else {
                    this.hostId = null;
                }
            }

            if (gameEngine != null && status != RoomStatus.WAITING) {
                gameEngine.onMemberLeft(playerId);
            } else if (players.size() < 2 && status != RoomStatus.WAITING) {
                status = RoomStatus.WAITING;
                currentDrawerId = null;
                currentRound = 0;
                broadcastSystemChat("Not enough players to continue. Returned to lobby.");
            }

            if (!players.isEmpty()) {
                broadcastRoomUpdate();
            }

            return players.size();
        }
    }

    public void broadcast(NetworkMessage message) {
        for (ClientSession session : memberSessions.values()) {
            session.send(message);
        }
    }

    public void broadcastExcept(String excludePlayerId, NetworkMessage message) {
        for (ClientSession session : memberSessions.values()) {
            if (!Objects.equals(session.getPlayerId(), excludePlayerId)) {
                session.send(message);
            }
        }
    }

    public void unicast(String playerId, NetworkMessage message) {
        ClientSession session = memberSessions.get(playerId);
        if (session != null) {
            session.send(message);
        }
    }

    public void broadcastSystemChat(String text) {
        ChatMessageDto chat = new ChatMessageDto("System", text, ChatKind.SYSTEM);
        broadcast(NetworkMessage.of(MessageType.CHAT_MESSAGE, JsonCodec.toNode(chat)));
    }

    public void broadcastRoomUpdate() {
        RoomUpdateDto dto = createRoomUpdateDto();
        broadcast(NetworkMessage.of(MessageType.ROOM_UPDATE, JsonCodec.toNode(dto)));
    }

    public RoomUpdateDto createRoomUpdateDto() {
        List<PlayerDto> playerList = new ArrayList<>(players.values());
        return new RoomUpdateDto(
                roomCode,
                hostId,
                status,
                Collections.unmodifiableList(playerList),
                maxRounds,
                drawTimeSec,
                currentRound
        );
    }

    public boolean hasPlayerWithNickname(String nickname) {
        if (nickname == null) return false;
        return players.values().stream()
                .anyMatch(p -> p.nickname().equalsIgnoreCase(nickname.trim()));
    }

    public void updatePlayer(PlayerDto updated) {
        players.put(updated.id(), updated);
    }

    public void updatePlayerScore(String playerId, int additionalPoints) {
        players.computeIfPresent(playerId, (id, p) -> p.withScore(p.score() + additionalPoints));
    }

    public CanvasSyncService getCanvasSync() {
        return canvasSync;
    }

    public GameEngine getGameEngine() {
        return gameEngine;
    }

    public void setGameEngine(GameEngine gameEngine) {
        this.gameEngine = gameEngine;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
    }

    public RoomStatus getStatus() {
        return status;
    }

    public void setStatus(RoomStatus status) {
        this.status = status;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public int getDrawTimeSec() {
        return drawTimeSec;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public void setCurrentRound(int currentRound) {
        this.currentRound = currentRound;
    }

    public String getCurrentDrawerId() {
        return currentDrawerId;
    }

    public void setCurrentDrawerId(String currentDrawerId) {
        this.currentDrawerId = currentDrawerId;
        players.replaceAll((id, p) -> p.withDrawer(Objects.equals(id, currentDrawerId)));
    }

    public int getPlayerCount() {
        return players.size();
    }

    public Collection<ClientSession> getMemberSessions() {
        return memberSessions.values();
    }

    public ClientSession getSession(String playerId) {
        return memberSessions.get(playerId);
    }

    public PlayerDto getPlayer(String playerId) {
        return players.get(playerId);
    }

    public List<PlayerDto> getPlayers() {
        return new ArrayList<>(players.values());
    }
}