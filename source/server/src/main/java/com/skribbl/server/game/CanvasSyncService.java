package com.skribbl.server.game;

import com.skribbl.common.dto.CanvasSyncDto;
import com.skribbl.common.dto.DrawStrokeDto;
import com.skribbl.common.model.StrokeData;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.server.net.ClientSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Xác thực quyền vẽ, lưu lịch sử nét vẽ của lượt hiện tại và phát đồng bộ CANVAS_SYNC / DRAW_STROKE.
 */
public class CanvasSyncService {
    private final Map<String, ClientSession> roomSessions = new ConcurrentHashMap<>();
    private final List<StrokeData> strokeHistory = new ArrayList<>();
    private volatile String currentDrawerId;

    public void registerClient(ClientSession session) {
        if (session == null || session.getPlayerId() == null) return;
        roomSessions.put(session.getPlayerId(), session);
        // Đồng bộ ngay lịch sử nét vẽ hiện tại cho người chơi mới vào phòng giữa chừng
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

    public synchronized void clearHistory() {
        this.strokeHistory.clear();
    }

    /**
     * Xử lý gói DRAW_STROKE từ Client gửi lên.
     */
    public void handleDrawStroke(ClientSession sender, DrawStrokeDto dto) {
        if (sender == null || dto == null) return;
        String senderId = sender.getPlayerId();
        // Chống gian lận: chỉ người đang giữ lượt vẽ mới được phép vẽ
        if (currentDrawerId == null || !currentDrawerId.equals(senderId)) {
            return;
        }

        StrokeData stroke = dto.toStrokeData();
        synchronized (this) {
            strokeHistory.add(stroke);
        }

        // Broadcast nét vẽ tới toàn bộ người đoán trong phòng
        for (Map.Entry<String, ClientSession> entry : roomSessions.entrySet()) {
            if (!entry.getKey().equals(senderId)) {
                entry.getValue().send(MessageType.DRAW_STROKE, dto);
            }
        }
    }

    /**
     * Xử lý gói CLEAR_CANVAS từ người vẽ.
     */
    public void handleClearCanvas(ClientSession sender) {
        if (sender == null) return;
        String senderId = sender.getPlayerId();
        if (currentDrawerId == null || !currentDrawerId.equals(senderId)) {
            return;
        }

        clearHistory();
        for (Map.Entry<String, ClientSession> entry : roomSessions.entrySet()) {
            if (!entry.getKey().equals(senderId)) {
                entry.getValue().send(MessageType.CLEAR_CANVAS, null);
            }
        }
    }

    /**
     * Gửi toàn bộ lịch sử nét vẽ của lượt hiện tại cho một Client cụ thể (CANVAS_SYNC).
     */
    public void syncHistoryTo(ClientSession target) {
        if (target == null) return;
        List<StrokeData> snapshot;
        synchronized (this) {
            if (strokeHistory.isEmpty()) return;
            snapshot = new ArrayList<>(strokeHistory);
        }
        target.send(MessageType.CANVAS_SYNC, new CanvasSyncDto(snapshot));
    }
}