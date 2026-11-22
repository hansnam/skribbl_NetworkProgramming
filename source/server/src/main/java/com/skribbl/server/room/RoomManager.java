package com.skribbl.server.room;

import com.skribbl.common.model.RoomStatus;
import com.skribbl.common.model.RoomType;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.common.protocol.NetworkMessage;
import com.skribbl.server.game.RoomRuntime;
import com.skribbl.server.net.ClientSession;

import java.security.SecureRandom;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Manages all active game rooms concurrently in memory.
 * Provides room generation, quick join matchmaking, joining, leaving, and cleanup.
 */
public class RoomManager {

    private static final String CODE_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 5;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, GameRoom> rooms = new HashMap<>();
    private final Object registryLock = new Object();

    private Consumer<GameRoom> created = room -> {
        throw new IllegalStateException("Set room-created handler before accepting players");
    };
    private BiConsumer<GameRoom, ClientSession> joined = (room, player) -> {};
    private BiConsumer<GameRoom, String> left = (room, id) -> {};

    /**
     * Creates a new GameRoom with specified RoomType and capacity.
     */
    public GameRoom createRoom(ClientSession creator, RoomType roomType, int maxRounds, int drawTimeSec, int maxPlayers) {
        List<RoomRuntime> retired = new ArrayList<>();
        GameRoom room;

        synchronized (registryLock) {
            requireRegistered(creator);
            room = new GameRoom(generateUniqueRoomCode(), roomType, maxRounds, drawTimeSec, maxPlayers);
            created.accept(room);
            detachLocked(creator, false, retired);
            rooms.put(room.getRoomCode(), room);
            room.withStateLock(() -> {
                if (!room.addPlayer(creator)) {
                    throw new IllegalStateException("Cannot add creator to room");
                }
                room.broadcastRoomUpdate();
                joined.accept(room, creator);
                room.broadcastSystemChat(creator.getNickname() + " joined the room.");
            });
        }
        finishRetired(retired);
        return room;
    }

    public GameRoom createRoom(ClientSession host, int maxRounds, int drawTimeSec) {
        return createRoom(host, RoomType.PRIVATE, maxRounds, drawTimeSec, GameRoom.DEFAULT_MAX_PLAYERS);
    }

    /**
     * Matchmaking: Finds or creates a PUBLIC room for the player.
     * Priority:
     * 1. WAITING PUBLIC room with available space, no duplicate nickname.
     * 2. Most populated room first.
     * 3. Oldest room on tie.
     * 4. If no suitable room exists, create a new PUBLIC room.
     */
    public GameRoom quickJoin(ClientSession session, StringBuilder errorReason) {
        if (session == null || session.getPlayerId() == null) {
            errorReason.append("Please connect first with CONNECT_REQ");
            return null;
        }

        List<RoomRuntime> retired = new ArrayList<>();
        GameRoom joinedRoom = null;

        synchronized (registryLock) {
            requireRegistered(session);

            String currentCode = session.getCurrentRoomCode();
            if (currentCode != null) {
                GameRoom currentRoom = rooms.get(currentCode);
                if (currentRoom != null) {
                    if (currentRoom.getRoomType() == RoomType.PUBLIC) {
                        currentRoom.withStateLock(currentRoom::broadcastRoomUpdate);
                        return currentRoom;
                    } else {
                        errorReason.append("Must leave private room before quick joining");
                        return null;
                    }
                }
            }

            String nickname = session.getNickname();
            List<GameRoom> candidates = rooms.values().stream()
                    .filter(r -> r.getRoomType() == RoomType.PUBLIC)
                    .filter(r -> r.getStatus() == RoomStatus.WAITING)
                    .filter(r -> r.getPlayerCount() < r.getMaxPlayers())
                    .filter(r -> !r.hasPlayerWithNickname(nickname))
                    .sorted(Comparator
                            .comparingInt(GameRoom::getPlayerCount).reversed()
                            .thenComparingLong(GameRoom::getCreatedAtNano))
                    .toList();

            for (GameRoom candidate : candidates) {
                boolean added = candidate.withStateLock(() -> {
                    if (!candidate.getRuntime().active()
                            || candidate.getPlayerCount() >= candidate.getMaxPlayers()
                            || candidate.hasPlayerWithNickname(nickname)) {
                        return false;
                    }
                    if (!candidate.addPlayer(session)) {
                        return false;
                    }
                    candidate.broadcastRoomUpdate();
                    joined.accept(candidate, session);
                    candidate.broadcastSystemChat(session.getNickname() + " joined the room.");
                    return true;
                });
                if (added) {
                    joinedRoom = candidate;
                    System.out.printf("[RoomManager] Quick joined player %s to existing PUBLIC room %s (%d/%d)%n",
                            session.getNickname(), candidate.getRoomCode(), candidate.getPlayerCount(), candidate.getMaxPlayers());
                    break;
                }
            }
        }
        finishRetired(retired);

        if (joinedRoom != null) {
            return joinedRoom;
        }

        GameRoom newRoom = createRoom(session, RoomType.PUBLIC, 3, 60, GameRoom.DEFAULT_MAX_PLAYERS);
        System.out.printf("[RoomManager] Quick joined player %s to newly created PUBLIC room %s%n",
                session.getNickname(), newRoom.getRoomCode());
        return newRoom;
    }

    /**
     * Tham gia vào phòng chơi PRIVATE bằng mã code 5 ký tự.
     */
    public boolean joinRoom(String roomCode, ClientSession session, StringBuilder errorReason) {
        if (roomCode == null || roomCode.trim().length() != CODE_LENGTH) {
            errorReason.append("Room code must be exactly 5 characters.");
            return false;
        }

        String normalizedCode = roomCode.strip().toUpperCase(Locale.ROOT);
        List<RoomRuntime> retired = new ArrayList<>();
        synchronized (registryLock) {
            requireRegistered(session);
            GameRoom room = rooms.get(normalizedCode);
            if (room == null) {
                errorReason.append("Room not found");
                return false;
            }

            if (room.getRoomType() == RoomType.PUBLIC) {
                errorReason.append("Cannot join a public room by code");
                return false;
            }

            boolean sameRoom = room.withStateLock(() -> room.getSession(session.getPlayerId()) == session);
            if (sameRoom) {
                room.withStateLock(room::broadcastRoomUpdate);
                return true;
            }

            if (room.getPlayerCount() >= room.getMaxPlayers()) {
                errorReason.append("Room ").append(normalizedCode).append(" is full (max ")
                        .append(room.getMaxPlayers()).append(" players)");
                return false;
            }

            boolean valid = room.withStateLock(() -> room.getRuntime().active()
                    && !room.hasPlayerWithNickname(session.getNickname()));
            if (!valid) {
                errorReason.append("Room unavailable or nickname already taken");
                return false;
            }

            detachLocked(session, false, retired);
            room.withStateLock(() -> {
                if (!room.addPlayer(session)) {
                    throw new IllegalStateException("Validated join failed");
                }
                room.broadcastRoomUpdate();
                joined.accept(room, session);
                room.broadcastSystemChat(session.getNickname() + " joined the room.");
            });
        }
        finishRetired(retired);
        return true;
    }

    public void leaveRoom(ClientSession session) {
        leaveRoom(session, false);
    }

    public void leaveRoom(ClientSession session, boolean acknowledge) {
        if (session == null) {
            return;
        }
        List<RoomRuntime> retired = new ArrayList<>();
        boolean hadRoom;
        synchronized (registryLock) {
            hadRoom = detachLocked(session, acknowledge, retired);
        }

        if (!hadRoom && acknowledge) {
            session.send(NetworkMessage.of(MessageType.LEAVE_ROOM));
        }
        finishRetired(retired);
    }

    private boolean detachLocked(ClientSession session, boolean acknowledge, List<RoomRuntime> retired) {
        String code = session.getCurrentRoomCode();
        GameRoom room = code == null ? null : rooms.get(code);

        if (room == null) {
            session.setCurrentRoomCode(null);
            return false;
        }
        boolean removed = room.withStateLock(() -> {
            if (room.getSession(session.getPlayerId()) != session) {
                return false;
            }
            room.removePlayer(session.getPlayerId());
            left.accept(room, session.getPlayerId());

            if (room.getPlayerCount() > 0) {
                room.broadcastRoomUpdate();
            }
            if (acknowledge) {
                room.sendTo(session, NetworkMessage.of(MessageType.LEAVE_ROOM));
            }
            if (room.getPlayerCount() == 0) {
                room.getRuntime().disposed = true;
            }
            return true;
        });

        if (removed && room.getRuntime().disposed) {
            rooms.remove(code, room);
            retired.add(room.getRuntime());
        }
        return removed;
    }

    private void finishRetired(List<RoomRuntime> retired) {
        for (RoomRuntime runtime : retired) {
            runtime.outbound.closeAndAwait();
        }
    }

    private void requireRegistered(ClientSession session) {
        if (session == null || session.getPlayerId() == null || !session.isConnected()) {
            throw new IllegalStateException("Register a connected session first");
        }
    }

    public void handleDisconnect(ClientSession session) {
        leaveRoom(session);
    }

    private String generateUniqueRoomCode() {
        while (true) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                int index = random.nextInt(CODE_CHARACTERS.length());
                sb.append(CODE_CHARACTERS.charAt(index));
            }
            String code = sb.toString();
            if (!rooms.containsKey(code)) {
                return code;
            }
        }
    }

    public GameRoom getRoom(String roomCode) {
        synchronized (registryLock) {
            return roomCode == null ? null : rooms.get(roomCode.strip().toUpperCase(Locale.ROOT));
        }
    }

    public int getRoomCount() {
        synchronized (registryLock) {
            return rooms.size();
        }
    }

    public Collection<GameRoom> getAllRooms() {
        synchronized (registryLock) {
            return List.copyOf(rooms.values());
        }
    }

    public void setRoomCreatedHandler(Consumer<GameRoom> handler) {
        created = handler;
    }

    public void setJoinedHandler(BiConsumer<GameRoom, ClientSession> handler) {
        joined = handler;
    }

    public void setLeftHandler(BiConsumer<GameRoom, String> handler) {
        left = handler;
    }

    public void closeRoom(GameRoom room) {
        List<ClientSession> members = new ArrayList<>();
        synchronized (registryLock) {
            if (rooms.get(room.getRoomCode()) != room)
                return;

            room.withStateLock(() -> {
                room.getRuntime().disposed = true;
                members.addAll(room.getMemberSessions());

                for (ClientSession member : members) {
                    room.removePlayer(member.getPlayerId());
                }
            });
            rooms.remove(room.getRoomCode(), room);
        }

        for (ClientSession member : members) {
            member.close();
        }
        room.getRuntime().outbound.stopNow();
        room.getRuntime().outbound.closeAndAwait();
    }

    public void closeAll() {
        for (GameRoom room : getAllRooms()) {
            closeRoom(room);
        }
    }
}
