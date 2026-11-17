package com.skribbl.server.room;

import com.skribbl.common.protocol.MessageType;
import com.skribbl.common.protocol.NetworkMessage;
import com.skribbl.server.game.RoomRuntime;
import com.skribbl.server.net.ClientSession;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Manages all active game rooms concurrently in memory.
 * Provides room generation, joining, leaving, and cleanup.
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
     * Tạo một phòng chơi mới:
     * 1. Đảm bảo host đã kết nối và có danh tính hợp lệ.
     * 2. Khởi tạo GameRoom với mã ngẫu nhiên 5 ký tự.
     * 3. Kích hoạt hook created để GameService gắn RoomRuntime (chứa engine, canvas, outbox) vào phòng.
     * 4. Tách host khỏi phòng cũ (nếu có).
     * 5. Đăng ký phòng vào danh sách rooms và thêm host vào phòng dưới khóa roomLock.
     *
     * @param host        Phiên mạng của người tạo phòng (ClientSession).
     * @param maxRounds   Số vòng chơi của trận đấu.
     * @param drawTimeSec Số giây vẽ của mỗi lượt.
     * @return GameRoom: Đối tượng phòng vừa được tạo thành công.
     * @throws IllegalStateException Nếu không thể thêm host vào phòng.
     */
    public GameRoom createRoom(ClientSession host, int maxRounds, int drawTimeSec) {
        List<RoomRuntime> retired = new ArrayList<>();
        GameRoom room;

        synchronized (registryLock) {
            requireRegistered(host);
            room = new GameRoom(generateUniqueRoomCode(), maxRounds, drawTimeSec);
            created.accept(room);
            detachLocked(host, false, retired);
            rooms.put(room.getRoomCode(), room);
            room.withStateLock(() -> {
                if (!room.addPlayer(host)) {
                    throw new IllegalStateException("Cannot add host");
                }
                // Xác nhận membership trước khi hook gửi trạng thái lượt/canvas.
                room.broadcastRoomUpdate();
                joined.accept(room, host);
                room.broadcastSystemChat(host.getNickname() + " joined the room.");
            });
        }
        finishRetired(retired);
        return room;
    }

    /**
     * Tham gia vào phòng chơi bằng mã code 5 ký tự.
     *
     * @param roomCode        Mã phòng gồm 5 chữ cái.
     * @param session     Phiên mạng của người chơi xin vào (ClientSession).
     * @param errorReason Bộ đệm StringBuilder để ghi nhận lý do lỗi nếu không vào được.
     * @return boolean: true nếu vào thành công; false nếu thất bại (lỗi ghi vào errorReason).
     */
    public boolean joinRoom(String roomCode, ClientSession session, StringBuilder errorReason) {
        if (roomCode == null || roomCode.trim().length() != CODE_LENGTH) {
            errorReason.append("Room code must be exactly 5 characters.");
            return false;
        }

        List<RoomRuntime> retired = new ArrayList<>();
        synchronized (registryLock) {
            requireRegistered(session);
            GameRoom room = rooms.get(roomCode.strip().toUpperCase(Locale.ROOT));
            if (room == null) {
                errorReason.append("Room not found");
                return false;
            }

            // Nếu người chơi đã ở trong phòng này rồi -> chỉ gửi lại snapshot sảnh hiện tại
            boolean sameRoom = room.withStateLock(() -> room.getSession(session.getPlayerId()) == session);
            if (sameRoom) {
                room.withStateLock(room::broadcastRoomUpdate);
                return true;
            }

            // Kiểm tra phòng còn active và không bị trùng nickname
            boolean valid = room.withStateLock(() -> room.getRuntime().active()
                && !room.hasPlayerWithNickname(session.getNickname()));
            if (!valid) {
                errorReason.append("Room unavailable or nickname already taken");
                return false;
            }

            // Tách người chơi khỏi phòng cũ trước khi vào phòng mới
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

    /**
     * Rời phòng chơi hiện tại mà không cần gửi gói tin xác nhận ACK (dùng khi mất kết nối).
     * @param session Phiên mạng của người chơi rời phòng (ClientSession).
     */
    public void leaveRoom(ClientSession session) {
        leaveRoom(session, false);
    }

    /**
     * Rời phòng chơi hiện tại, có tùy chọn gửi gói tin xác nhận LEAVE_ROOM (ACK) về cho client.
     *
     * @param session     Phiên mạng của người chơi rời phòng.
     * @param acknowledge true nếu cần gửi gói tin ACK LEAVE_ROOM về cho client (khi client chủ động bấm nút).
     */
    public void leaveRoom(ClientSession session, boolean acknowledge) {
        if (session == null) {
            return;
        }
        List<RoomRuntime> retired = new ArrayList<>();
        boolean hadRoom;
        synchronized (registryLock) {
            hadRoom = detachLocked(session, acknowledge, retired);
        }

        // Nếu người chơi thực tế chưa ở phòng nào mà bấm rời -> vẫn gửi ACK để client reset UI
        if (!hadRoom && acknowledge) {
            session.send(NetworkMessage.of(MessageType.LEAVE_ROOM));
        }
        finishRetired(retired);
    }

    /**
     * Tách một ClientSession ra khỏi phòng hiện tại của họ dưới sự bảo vệ của registryLock.
     * Nếu sau khi rời, phòng không còn ai (0 người) -> đánh dấu phòng là disposed và thêm vào retired.
     *
     * @param session     Phiên mạng của người chơi cần tách.
     * @param acknowledge true nếu cần gửi gói tin LEAVE_ROOM xác nhận cho người chơi này.
     * @param retired     Danh sách chứa các RoomRuntime bị hủy cần dọn dẹp sau khi thoát synchronized.
     * @return boolean: true nếu người chơi thực sự có trong phòng và đã được tách; false nếu không ở phòng nào.
     */
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
            left.accept(room, session.getPlayerId()); // Báo cho GameEngine cập nhật

            if (room.getPlayerCount() > 0) {
                room.broadcastRoomUpdate(); // Cập nhật danh sách mới cho những người còn lại
            }
            if (acknowledge) {
                // Gửi gói tin xác nhận LEAVE_ROOM cho chính người vừa rời
                room.sendTo(session, NetworkMessage.of(MessageType.LEAVE_ROOM));
            }
            if (room.getPlayerCount() == 0) {
                room.getRuntime().disposed = true;
            }
            return true;
        });

        // Nếu phòng đã bị dọn -> xóa khỏi danh sách quản lý rooms của server
        if (removed && room.getRuntime().disposed) {
            rooms.remove(code, room);
            retired.add(room.getRuntime());
        }
        return removed;
    }

    /**
     * Đợi các RoomRuntime đã bị dọn dẹp gửi nốt các gói tin trong hàng đợi rồi đóng luồng gửi.
     * Luôn được gọi ngoài khối synchronized (registryLock).
     *
     * @param retired Danh sách các RoomRuntime đã dừng hoạt động.
     */
    private void finishRetired(List<RoomRuntime> retired) {
        for (RoomRuntime runtime : retired) {
            runtime.outbound.closeAndAwait();
        }
    }

    /**
     * Kiểm tra xác thực phiên mạng: Client bắt buộc phải có playerId và đang kết nối.
     * @param session Phiên mạng cần kiểm tra.
     * @throws IllegalStateException Nếu session null, chưa có ID hoặc đã ngắt kết nối.
     */
    private void requireRegistered(ClientSession session) {
        if (session == null || session.getPlayerId() == null || !session.isConnected()) {
            throw new IllegalStateException("Register a connected session first");
        }
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

    /**
     * Đóng cưỡng chế một phòng game (dùng khi phòng bị lỗi hoặc server tắt):
     * Đánh dấu disposed, ngắt kết nối các thành viên và đóng hàng đợi outbound.
     *
     * @param room Phòng cần đóng (GameRoom).
     */
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

        // Đóng kết nối của các thành viên bên ngoài khóa
        for (ClientSession member : members) {
            member.close();
        }
        room.getRuntime().outbound.stopNow();
        room.getRuntime().outbound.closeAndAwait();
    }

    /**
     * Đóng tất cả các phòng đang có trên server khi tắt hệ thống.
     */
    public void closeAll() {
        for (GameRoom room : getAllRooms()) {
            closeRoom(room);
        }
    }
}

