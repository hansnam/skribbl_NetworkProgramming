package com.skribbl.server.game;

import com.skribbl.common.dto.CanvasSyncDto;
import com.skribbl.common.dto.DrawStrokeDto;
import com.skribbl.common.model.StrokeData;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.server.net.ClientSession;
import com.skribbl.server.room.GameRoom;
import com.skribbl.common.protocol.NetworkMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Xác thực quyền vẽ, lưu lịch sử nét vẽ của lượt hiện tại và phát đồng bộ CANVAS_SYNC / DRAW_STROKE.
 */
public class CanvasSyncService {
    private final GameRoom room;

    public CanvasSyncService() {
        this.room = null;
    }

    public CanvasSyncService(GameRoom room) {
        this.room = java.util.Objects.requireNonNull(room);
    }

    // Engine gọi khi bắt đầu lượt hoặc trở về sảnh: xóa lịch sử và báo các client xóa bảng.
    public void reset() {
        clearHistory();
        if (room != null) {
            room.broadcast(NetworkMessage.of(MessageType.CLEAR_CANVAS));
        }
    }

    private final Map<String, ClientSession> roomSessions = new ConcurrentHashMap<>();
    private final List<StrokeData> strokeHistory = new ArrayList<>();
    private volatile String currentDrawerId;

    /**
     * Đăng ký client mới vào dịch vụ canvas và đồng bộ toàn bộ lịch sử vẽ cho họ.
     * @param session Phiên kết nối mạng của người chơi mới (ClientSession).
     */
    public void registerClient(ClientSession session) {
        syncHistoryTo(session);
    }

    public void removeClient(String playerId) {
        if (playerId != null) {
            roomSessions.remove(playerId);
        }
    }

    public synchronized void setCurrentDrawer(String drawerId) {
        this.currentDrawerId = drawerId;
        this.strokeHistory.clear();
    }

    /**
     * Xóa sạch danh sách các nét vẽ đã lưu trong bộ nhớ strokeHistory.
     */
    public void clearHistory() {
        strokeHistory.clear();
    }

    /**
     * Xử lý và lưu trữ một nét vẽ từ người chơi gửi lên:
     * - Kiểm tra tọa độ điểm đầu/cuối có nằm trong khung canvas (960x600).
     * - Kiểm tra độ dày nét (1..40) và định dạng màu hex (#RRGGBB).
     * - Kiểm tra giới hạn tối đa 20.000 nét vẽ để chống tràn bộ nhớ.
     * - Lưu vào strokeHistory và broadcast gói tin DRAW_STROKE cho tất cả người chơi khác (trừ người vẽ).
     *
     * @param sender Phiên mạng của người vẽ (ClientSession).
     * @param dto    Thông tin nét vẽ (DrawStrokeDto).
     * @return boolean:
     *         - true: Nét vẽ hợp lệ, đã lưu và broadcast thành công.
     *         - false: Nét vẽ không hợp lệ hoặc bộ nhớ nét vẽ đã đầy (>= 20.000 nét).
     */
    public boolean handleDrawStroke(ClientSession sender, DrawStrokeDto dto) {
        if (dto == null || !point(dto.x1(), dto.y1()) || !point(dto.x2(), dto.y2())
                || dto.thickness() < 1 || dto.thickness() > 40 || dto.color() == null
                || !dto.color().matches("#[0-9a-fA-F]{6}") || strokeHistory.size() >= 20_000) {
            return false;
        }

        strokeHistory.add(dto.toStrokeData());
        assert room != null;
        room.broadcastExcept(sender.getPlayerId(), NetworkMessage.of(
                MessageType.DRAW_STROKE,
                JsonCodec.toNode(dto)
            )
        );
        return true;
    }

    /**
     * Kiểm tra một tọa độ (x, y) có hợp lệ trên mặt phẳng canvas hay không.
     * Canvas có kích thước chuẩn 960x600 pixel.
     *
     * @param x Tọa độ trục hoành (double).
     * @param y Tọa độ trục tung (double).
     * @return boolean: true nếu x thuộc [0..960] và y thuộc [0..600] và là số hữu hạn; false nếu ngoài phạm vi.
     */
    private boolean point (double x, double y) {
        return Double.isFinite(x) && Double.isFinite(y)
                && x >= 0 && x <= 960 && y >= 0  && y <= 600;
    }

    /**
     * Xử lý khi người vẽ yêu cầu xóa trắng bảng vẽ (CLEAR_CANVAS).
     * @param sender Phiên mạng của người gửi yêu cầu (ClientSession).
     */
    public void handleClearCanvas(ClientSession sender) {
        reset();
    }

    /**
     * Gửi toàn bộ các nét vẽ hiện có trong strokeHistory (CANVAS_SYNC) cho một người chơi cụ thể.
     * @param target Phiên mạng của người nhận (ClientSession).
     */
    public void syncHistoryTo(ClientSession target) {
        assert room != null;
        room.sendTo(target, NetworkMessage.of(MessageType.CANVAS_SYNC,
                JsonCodec.toNode(new CanvasSyncDto(List.copyOf(strokeHistory)))));
    }
}
