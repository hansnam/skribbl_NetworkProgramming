package com.skribbl.server.room;

import com.skribbl.server.net.ClientSession;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all active game rooms concurrently in memory.
 * Provides room generation, joining, leaving, and cleanup.
 */
public class RoomManager {

    private static final String CODE_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 5;
    private final SecureRandom random = new SecureRandom();

    private final ConcurrentHashMap<String, GameRoom> rooms = new ConcurrentHashMap<>();

    /**
     * Creates a new GameRoom and assigns the host.
     *
     * @param host ClientSession of the room creator
     * @param maxRounds Max rounds configuration
     * @param drawTimeSec Turn draw time configuration
     * @return Newly created GameRoom
     */
    public GameRoom createRoom(ClientSession host, int maxRounds, int drawTimeSec) {
        // If host was previously in another room, leave it first
        leaveRoom(host);

        String roomCode = generateUniqueRoomCode();
        GameRoom room = new GameRoom(roomCode, maxRounds, drawTimeSec);
        rooms.put(roomCode, room);

        boolean added = room.addPlayer(host);
        if (!added) {
            rooms.remove(roomCode);
            throw new IllegalStateException("Failed to add host to freshly created room");
        }

        System.out.printf("[RoomManager] Created Room %s by host %s (Nick: %s)%n",
                roomCode, host.getPlayerId(), host.getNickname());
        return room;
    }

    /**
     * Attempts to join an existing room.
     *
     * @param roomCode 5-character room code
     * @param session Joining player's session
     * @param errorReason Output holder for descriptive failure message
     * @return true if joined successfully, false otherwise
     */
    public boolean joinRoom(String roomCode, ClientSession session, StringBuilder errorReason) {
        if (roomCode == null || roomCode.trim().length() != CODE_LENGTH) {
            errorReason.append("Room code must be exactly 5 characters.");
            return false;
        }

        String normalizedCode = roomCode.trim().toUpperCase(Locale.ROOT);
        GameRoom room = rooms.get(normalizedCode);
        if (room == null) {
            errorReason.append("Room not found: " + normalizedCode);
            return false;
        }

        // Leave any prior room
        leaveRoom(session);

        if (room.hasPlayerWithNickname(session.getNickname())) {
            errorReason.append("Nickname '" + session.getNickname() + "' is already taken in room " + normalizedCode);
            return false;
        }

        boolean success = room.addPlayer(session);
        if (!success) {
            errorReason.append("Failed to join room " + normalizedCode);
            return false;
        }

        System.out.printf("[RoomManager] Player %s (%s) joined Room %s%n",
                session.getPlayerId(), session.getNickname(), normalizedCode);
        return true;
    }

    /**
     * Removes a player session from their current room and cleans up empty rooms.
     */
    public void leaveRoom(ClientSession session) {
        if (session == null) {
            return;
        }

        String roomCode = session.getCurrentRoomCode();
        if (roomCode == null) {
            return;
        }

        GameRoom room = rooms.get(roomCode);
        if (room != null) {
            int remaining = room.removePlayer(session.getPlayerId());
            System.out.printf("[RoomManager] Player %s left Room %s (Remaining: %d)%n",
                    session.getPlayerId(), roomCode, remaining);
            if (remaining <= 0) {
                rooms.remove(roomCode);
                System.out.printf("[RoomManager] Room %s is now empty and removed.%n", roomCode);
            }
        }
        session.setCurrentRoomCode(null);
    }

    /**
     * Handles unexpected client disconnects.
     */
    public void handleDisconnect(ClientSession session) {
        leaveRoom(session);
    }

    /**
     * Generates a collision-free 5-character uppercase room code.
     */
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
        if (roomCode == null) return null;
        return rooms.get(roomCode.trim().toUpperCase(Locale.ROOT));
    }

    public int getRoomCount() {
        return rooms.size();
    }

    public Collection<GameRoom> getAllRooms() {
        return rooms.values();
    }
}
