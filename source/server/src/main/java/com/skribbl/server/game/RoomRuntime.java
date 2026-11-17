package com.skribbl.server.game;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import com.skribbl.server.room.GameRoom;

public class RoomRuntime {
    public final GameRoom room;
    public final GameEngine engine;
    public final CanvasSyncService canvas;
    public final RoomOutboundDispatcher outbound;
    public final AtomicBoolean tickPending = new AtomicBoolean();
    public final AtomicBoolean failed = new AtomicBoolean();
    public volatile boolean disposed;
    private final Runnable cleanup;

    public RoomRuntime(GameRoom room, WordRepository words, LongSupplier now, Runnable cleanup) {
        this.room = room;
        this.cleanup = cleanup;
        canvas = new CanvasSyncService(room);
        engine = new GameEngine(room, words, new Random(), now, ignored -> canvas.reset());
        outbound = new RoomOutboundDispatcher(room.getRoomCode(), this::fail);
    }

    public boolean active() {
        return !disposed && !failed.get();
    }
    
    public void fail() {
        if (failed.compareAndSet(false, true)) {
            cleanup.run();
        }
    }
}
