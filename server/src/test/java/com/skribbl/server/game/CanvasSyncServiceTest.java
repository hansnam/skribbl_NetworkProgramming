package com.skribbl.server.game;

import com.skribbl.common.dto.CanvasSyncDto;
import com.skribbl.common.dto.DrawStrokeDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CanvasSyncServiceTest {
    private CanvasSyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new CanvasSyncService();
    }

    @Test
    void shouldAllowAuthorizedDrawerToRecordAndSyncStrokes() {
        syncService.startNewTurn("player-1");
        DrawStrokeDto stroke = new DrawStrokeDto(10, 20, 30, 40, "#000000", 4, false);

        boolean accepted = syncService.recordStroke("player-1", stroke);
        CanvasSyncDto snapshot = syncService.createSyncSnapshot();

        assertTrue(accepted);
        assertEquals(1, syncService.getStrokeCount());
        assertEquals(1, snapshot.strokes().size());
        assertEquals(stroke.toStrokeData(), snapshot.strokes().get(0));
    }

    @Test
    void shouldRejectStrokesFromNonDrawerPlayer() {
        syncService.startNewTurn("player-1");
        DrawStrokeDto cheatStroke = new DrawStrokeDto(5, 5, 15, 15, "#FF0000", 6, false);

        boolean accepted = syncService.recordStroke("player-2", cheatStroke);

        assertFalse(accepted);
        assertEquals(0, syncService.getStrokeCount());
    }

    @Test
    void shouldClearCanvasOnlyWhenRequestedByCurrentDrawer() {
        syncService.startNewTurn("player-1");
        syncService.recordStroke("player-1", new DrawStrokeDto(0, 0, 10, 10, "#000000", 3, false));

        assertFalse(syncService.clearCanvas("player-2"));
        assertEquals(1, syncService.getStrokeCount());

        assertTrue(syncService.clearCanvas("player-1"));
        assertEquals(0, syncService.getStrokeCount());
    }
}