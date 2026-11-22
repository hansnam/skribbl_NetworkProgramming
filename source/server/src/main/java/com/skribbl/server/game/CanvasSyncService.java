package com.skribbl.server.game;

import com.skribbl.common.dto.CanvasSyncDto;
import com.skribbl.common.dto.DrawStrokeDto;
import com.skribbl.common.dto.FillCanvasDto;
import com.skribbl.common.model.StrokeData;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.common.protocol.NetworkMessage;
import com.skribbl.server.net.ClientSession;
import com.skribbl.server.room.GameRoom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Xác thực quyền vẽ, lưu lịch sử nét vẽ & đổ màu của lượt hiện tại và phát đồng bộ
 * DRAW_STROKE / FILL_CANVAS / UNDO_CANVAS / CLEAR_CANVAS / CANVAS_SYNC.
 */
public class CanvasSyncService {
    private final GameRoom room;

    public CanvasSyncService() {
        this.room = null;
    }

    public CanvasSyncService(GameRoom room) {
        this.room = Objects.requireNonNull(room);
    }

    public void reset() {
        clearHistory();
        if (room != null) {
            room.broadcast(NetworkMessage.of(MessageType.CLEAR_CANVAS));
        }
    }

    private final Map<String, ClientSession> roomSessions = new ConcurrentHashMap<>();
    private final List<StrokeData> strokeHistory = new ArrayList<>();
    private volatile String currentDrawerId;

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

    public void clearHistory() {
        strokeHistory.clear();
    }

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
        ));
        return true;
    }

    /**
     * Handles flood fill (Paint Bucket) operation at (x, y) and stores it in chronological strokeHistory.
     */
    public boolean handleFillCanvas(ClientSession sender, FillCanvasDto dto) {
        if (dto == null || !point(dto.x(), dto.y()) || dto.color() == null
                || !dto.color().matches("#[0-9a-fA-F]{6}") || strokeHistory.size() >= 20_000) {
            return false;
        }

        String groupId = dto.strokeGroupId() != null && !dto.strokeGroupId().isBlank()
                ? dto.strokeGroupId()
                : "fill-" + strokeHistory.size();
        strokeHistory.add(new StrokeData(dto.x(), dto.y(), dto.x(), dto.y(), dto.color(), 1, false, groupId, true));
        assert room != null;
        room.broadcastExcept(sender.getPlayerId(), NetworkMessage.of(
                MessageType.FILL_CANVAS,
                JsonCodec.toNode(new FillCanvasDto(dto.x(), dto.y(), dto.color(), groupId))
        ));
        return true;
    }

    /**
     * Undoes the most recent stroke group (or single stroke/fill if ungrouped) and broadcasts updated CANVAS_SYNC.
     */
    public void handleUndoCanvas(ClientSession sender) {
        if (strokeHistory.isEmpty()) {
            return;
        }
        int lastIdx = strokeHistory.size() - 1;
        String lastGroup = strokeHistory.get(lastIdx).strokeGroupId();
        if (lastGroup == null || lastGroup.isBlank()) {
            strokeHistory.remove(lastIdx);
        } else {
            while (!strokeHistory.isEmpty()
                    && lastGroup.equals(strokeHistory.get(strokeHistory.size() - 1).strokeGroupId())) {
                strokeHistory.remove(strokeHistory.size() - 1);
            }
        }
        assert room != null;
        room.broadcast(NetworkMessage.of(MessageType.CANVAS_SYNC,
                JsonCodec.toNode(new CanvasSyncDto(List.copyOf(strokeHistory)))));
    }

    private boolean point(double x, double y) {
        return Double.isFinite(x) && Double.isFinite(y)
                && x >= 0 && x <= 960 && y >= 0 && y <= 600;
    }

    public void handleClearCanvas(ClientSession sender) {
        reset();
    }

    public void syncHistoryTo(ClientSession target) {
        assert room != null;
        room.sendTo(target, NetworkMessage.of(MessageType.CANVAS_SYNC,
                JsonCodec.toNode(new CanvasSyncDto(List.copyOf(strokeHistory)))));
    }
}
