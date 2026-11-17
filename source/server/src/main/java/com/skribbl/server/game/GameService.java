package com.skribbl.server.game;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.skribbl.common.dto.ChatSendDto;
import com.skribbl.common.dto.ChooseWordDto;
import com.skribbl.common.dto.DrawStrokeDto;
import com.skribbl.server.net.ClientSession;
import com.skribbl.server.room.GameRoom;
import com.skribbl.server.room.RoomManager;

public class GameService {
    private final RoomManager rooms;
    private final WordRepository words;
    private final ExecutorService tasks;
    private final LongSupplier now;

    /**
     * Khởi tạo GameService.
     *
     * @param rooms Quản lý danh sách phòng (RoomManager).
     * @param words Kho từ vựng (WordRepository).
     * @param tasks Thread pool xử lý các tác vụ nền (ExecutorService).
     * @param now   Hàm cung cấp thời gian nano hiện tại (LongSupplier).
     */
    public GameService(RoomManager rooms, WordRepository words, ExecutorService tasks, LongSupplier now) {
        this.rooms = rooms;
        this.words = words;
        this.tasks = tasks;
        this.now = now;
    }

    /**
     * Hook được RoomManager gọi ngay khi một phòng (GameRoom) mới được tạo ra.
     * Tạo RoomRuntime và gắn vào GameRoom trước khi thêm bất kỳ người chơi nào.
     *
     * @param room Đối tượng GameRoom mới tạo.
     */
    public void onCreated(GameRoom room) {
        RoomRuntime runtime = new RoomRuntime(room, words, now, () -> {
            try {
                tasks.execute(() -> rooms.closeRoom(room));
            } catch (RejectedExecutionException ex) {
                // Shutdown sẽ closeAll; không close socket inline trong roomLock.
                System.err.println("Cleanup executor is stopping: " + room.getRoomCode());
            }
        });
        room.attachRuntime(runtime);
    }

    /**
     * Hàm tiện ích nội bộ: Tìm phòng của người gửi, lấy khóa roomLock an toàn,
     * kiểm tra lại tính hợp lệ của người chơi rồi mới thực thi hành động (action).
     *
     * @param sender Phiên kết nối mạng của client gửi yêu cầu (ClientSession).
     * @param action Hành động logic game cần thực thi với RoomRuntime của phòng đó.
     */
    public void withMember(ClientSession sender, Consumer<RoomRuntime> action) {
        if (sender == null || !sender.isConnected() || sender.getPlayerId() == null)
            return;
        GameRoom room = rooms.getRoom(sender.getCurrentRoomCode());
        if (room == null) {
            sender.sendError("NOT_IN_ROOM", "Join a room first");
            return;
        }

        room.withStateLock(() -> {
            RoomRuntime runtime = room.getRuntime();
            if (!runtime.active() || room.getSession(sender.getPlayerId()) != sender
                || !sender.isConnected())
                return;

            action.accept(runtime);
        });
    }

    public void onStart(ClientSession sender) {
        withMember(sender, room -> room.engine.onStart(sender));
    }
    public void onChoose(ClientSession sender, ChooseWordDto dto) {
        withMember(sender, room -> room.engine.onChoose(sender, dto));
    }
    public void onChat(ClientSession sender, ChatSendDto dto) {
        withMember(sender, room -> room.engine.onChat(sender, dto));
    }

    public void onJoined(GameRoom room, ClientSession member) {
        room.getRuntime().engine.onMemberJoined(member);
        room.getRuntime().canvas.registerClient(member);
    }

    public void onLeft(GameRoom room, String id) {
        room.getRuntime().engine.onMemberLeft(id);
    }

    /**
     * Xử lý khi nhận gói tin nét vẽ DRAW_STROKE từ người vẽ.
     * Kiểm tra quyền vẽ (chỉ người đang vẽ trong lượt mới được vẽ) trước khi phát tiếp.
     *
     * @param sender Người gửi nét vẽ (ClientSession).
     * @param dto    Thông tin tọa độ, màu sắc, kích thước nét vẽ (DrawStrokeDto).
     */
    public void onDrawStroke(ClientSession sender, DrawStrokeDto dto) {
        withMember(sender, room -> {
            if (!room.engine.canDraw(sender)) {
                room.room.sendErrorTo(sender, "DRAW_NOT_ALLOWED", "Only the current drawer may draw");
                return;
            }
            if (!room.canvas.handleDrawStroke(sender, dto)) {
                room.room.sendErrorTo(sender, "INVALID_STROKE", "Invalid stroke or history full");
            }
        });
    }

    /**
     * Xử lý khi nhận gói tin CLEAR_CANVAS từ client.
     * @param sender Người yêu cầu xóa bảng vẽ (ClientSession).
     */
    public void onClearCanvas(ClientSession sender) {
        withMember(sender, room -> {
            if (!room.engine.canDraw(sender)) {
                room.room.sendErrorTo(sender, "DRAW_NOT_ALLOWED", "Only the current drawer may clear");
                return;
            }
            room.canvas.handleClearCanvas(sender);
        });
    }

    /**
     * Lấy danh sách snapshot tất cả các RoomRuntime hiện có của server.
     * @return List<RoomRuntime>: Danh sách runtime của các phòng.
     */
    public List<RoomRuntime> snapshot() {
        List<RoomRuntime> result = new ArrayList<>();
        for (GameRoom room : rooms.getAllRooms())
            result.add(room.getRuntime());
        return result;
    }

    /**
     * Nhịp đếm giờ (tick) cho một runtime phòng cụ thể.
     * @param runtime RoomRuntime cần tick logic.
     */
    public void tick(RoomRuntime runtime) {
        runtime.room.withStateLock(() -> {
            if (runtime.active()) {
                runtime.engine.tick();
            }
        });
    }
}
