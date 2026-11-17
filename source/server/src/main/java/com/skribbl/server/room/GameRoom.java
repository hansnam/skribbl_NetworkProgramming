package com.skribbl.server.room;

import com.skribbl.common.dto.ChatMessageDto;
import com.skribbl.common.dto.ChatKind;
import com.skribbl.common.dto.RoomUpdateDto;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.common.protocol.NetworkMessage;
import com.skribbl.server.net.ClientSession;
import com.skribbl.common.dto.ErrorMsgDto;
import com.skribbl.server.game.RoomOutboundDispatcher.Delivery;
import com.skribbl.server.game.RoomRuntime;

import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents an isolated multi-player game room instance.
 * Thread-safe management of room members, host election, and messaging.
 */
public class GameRoom {

    private final String roomCode;
    private final int maxRounds;
    private final int drawTimeSec;
    private final Object roomLock = new Object();

    private volatile String hostId;
    private volatile RoomStatus status = RoomStatus.WAITING;
    private volatile int currentRound = 0;
    private volatile String currentDrawerId = null;
    private RoomRuntime runtime;
    private List<Delivery> currentBatch;

    private final ConcurrentHashMap<String, ClientSession> memberSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlayerDto> players = new ConcurrentHashMap<>();

    public GameRoom(String roomCode, int maxRounds, int drawTimeSec) {
        this.roomCode = Objects.requireNonNull(roomCode, "roomCode cannot be null");
        this.maxRounds = maxRounds > 0 ? maxRounds : 3;
        this.drawTimeSec = drawTimeSec > 0 ? drawTimeSec : 60;
    }

    /**
     * Adds a player session to this room.
     *
     * @param session ClientSession of joining player
     * @return true if added, false if duplicate nickname or invalid
     */
    public boolean addPlayer(ClientSession session) {
        return withStateLock(() -> {
            String nickname = session.getNickname();
            if (nickname == null || nickname.isBlank() || session.getPlayerId() == null
                    || hasPlayerWithNickname(nickname)) {
                return false;
            }

            boolean first = players.isEmpty();
            if (first)
                hostId = session.getPlayerId();

            PlayerDto player = new PlayerDto(
                session.getPlayerId(), 
                nickname.strip(), 
                0, 
                first, 
                false, 
                false
            );

            players.put(player.id(), player);
            memberSessions.put(player.id(), session);
            session.setCurrentRoomCode(roomCode);
            return true;
        });
    }

    /**
     * Removes a player from the room and handles host reassignment if necessary.
     *
     * @param playerId ID of the player to remove
     * @return remaining number of players in the room
     */
    public int removePlayer(String playerId) {
        return withStateLock(() -> {
            ClientSession session = memberSessions.remove(playerId);
            PlayerDto removed = players.remove(playerId);
            
            if (session != null) {
                session.setCurrentRoomCode(null);
            }
            if (removed == null)
                return players.size();

            broadcastSystemChat(removed.nickname() + " left the room.");
            if (Objects.equals(hostId, playerId)) {
                hostId = null;
                if (!players.isEmpty()) {
                    hostId = new TreeSet<>(players.keySet()).first();
                    players.put(hostId, players.get(hostId).withHost(true));
                }
            }
            return players.size();
        });
    }

    /**
     * Broadcasts a network message to every connected player in this room.
     */
    public void broadcast(NetworkMessage message) {
        for (ClientSession session : memberSessions.values()) {
            sendTo(session, message);
        }
    }

    /**
     * Broadcasts a message to all players except one (e.g. drawing stroke echoes).
     */
    public void broadcastExcept(String excludePlayerId, NetworkMessage message) {
        for (ClientSession session : memberSessions.values()) {
            if (!Objects.equals(session.getPlayerId(), excludePlayerId)) {
                sendTo(session, message);;
            }
        }
    }

    /**
     * Unicast sends a message to a specific player in the room.
     */
    public void unicast(String playerId, NetworkMessage message) {
        ClientSession session = memberSessions.get(playerId);
        if (session != null) {
            sendTo(memberSessions.get(playerId), message);
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
        return new RoomUpdateDto(
                roomCode,
                hostId,
                status,
                List.copyOf(players.values()),
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

    public void attachRuntime(RoomRuntime value) {
        synchronized (roomLock) {
            if (runtime != null) {
                throw new IllegalStateException("Runtime already attached");
            }
            runtime = Objects.requireNonNull(value);
        }
    }

    public void withStateLock(Runnable action) {
        withStateLock(() -> {
            action.run();
            return null;
        });
    }

    public <T> T withStateLock(Supplier<T> action) {
        synchronized (roomLock) {
            boolean outermost = currentBatch == null;
            if (outermost) {
                currentBatch = new ArrayList<>();
            }
            try {
                return action.get();
            } catch (RuntimeException ex) {
                if (runtime != null) {
                    runtime.fail();
                }
                throw ex;
            } finally {
                if (outermost) {
                    List<Delivery> completed = currentBatch;
                    currentBatch = null;
                    if (!completed.isEmpty()) {
                        if (runtime == null) {
                            throw new IllegalStateException("Attach runtime before adding players");
                        }
                        if (!runtime.outbound.offer(completed)) {
                            runtime.fail();
                        }
                    }
                }
            }
        }
    }

    public void sendTo(ClientSession recipent, NetworkMessage message) {
        if (!Thread.holdsLock(roomLock) || currentBatch == null) {
            throw new IllegalStateException("Send through withStateLock");
        }
        if (recipent != null) {
            currentBatch.add(new Delivery(recipent, message));
        }
    }

    public void sendErrorTo(ClientSession recipient, String code, String text) {
        sendTo(recipient, NetworkMessage.of(MessageType.ERROR_MSG,
                JsonCodec.toNode(new ErrorMsgDto(code, text))));
    }

    

    // Getters and Setters
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
        // Update isDrawer in player records
        players.replaceAll((id, p) -> p.withDrawer(Objects.equals(id, currentDrawerId)));
    }

    public int getPlayerCount() {
        return players.size();
    }

    public Collection<ClientSession> getMemberSessions() {
        return List.copyOf(memberSessions.values());
    }

    public ClientSession getSession(String playerId) {
        return memberSessions.get(playerId);
    }

    public PlayerDto getPlayer(String playerId) {
        return players.get(playerId);
    }

    public List<PlayerDto> getPlayers() {
        return List.copyOf(players.values());
    }

    public RoomRuntime getRuntime() { 
        return runtime; 
    }

}

