package com.skribbl.server.game;

import com.skribbl.common.dto.CanvasSyncDto;
import com.skribbl.common.dto.DrawStrokeDto;
import com.skribbl.common.model.StrokeData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class CanvasSyncService {
    private static final int MAX_STROKES_PER_TURN = 5000;

    private final List<StrokeData> strokeHistory = new ArrayList<>();
    private String currentDrawerId;

    public synchronized void startNewTurn(String drawerId) {
        this.currentDrawerId = drawerId;
        this.strokeHistory.clear();
    }

    public synchronized void endTurn() {
        this.currentDrawerId = null;
        this.strokeHistory.clear();
    }

    public synchronized boolean isAuthorizedDrawer(String senderId) {
        return currentDrawerId != null && Objects.equals(currentDrawerId, senderId);
    }

    public synchronized boolean recordStroke(String senderId, DrawStrokeDto stroke) {
        if (stroke == null || !isAuthorizedDrawer(senderId)) {
            return false;
        }
        if (strokeHistory.size() < MAX_STROKES_PER_TURN) {
            strokeHistory.add(toStrokeData(stroke));
        }
        return true;
    }

    public synchronized boolean recordStroke(String senderId, StrokeData stroke) {
        if (stroke == null || !isAuthorizedDrawer(senderId)) {
            return false;
        }
        if (strokeHistory.size() < MAX_STROKES_PER_TURN) {
            strokeHistory.add(stroke);
        }
        return true;
    }

    public synchronized boolean clearCanvas(String senderId) {
        if (!isAuthorizedDrawer(senderId)) {
            return false;
        }
        strokeHistory.clear();
        return true;
    }

    public synchronized CanvasSyncDto createSyncSnapshot() {
        return new CanvasSyncDto(List.copyOf(strokeHistory));
    }

    public synchronized List<StrokeData> getStrokeHistory() {
        return Collections.unmodifiableList(new ArrayList<>(strokeHistory));
    }

    public synchronized int getStrokeCount() {
        return strokeHistory.size();
    }

    private StrokeData toStrokeData(DrawStrokeDto dto) {
        return new StrokeData(
                dto.x1(),
                dto.y1(),
                dto.x2(),
                dto.y2(),
                dto.color(),
                dto.thickness(),
                dto.eraser()
        );
    }
}