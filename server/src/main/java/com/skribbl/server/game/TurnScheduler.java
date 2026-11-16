package com.skribbl.server.game;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TurnScheduler implements AutoCloseable {
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("turn-clock").factory()
    );
    private final AtomicBoolean tickPending = new AtomicBoolean();

    public TurnScheduler(ServerEventLoop events, Runnable tickAllRooms) {
        clock.scheduleAtFixedRate(() -> {
            // Nếu loop bận, không dồn hàng trăm tick cũ vào hàng đợi.
            if (!tickPending.compareAndSet(false, true)) return;
            
            boolean accepted = events.execute(() -> {
                try {
                    tickAllRooms.run();
                } finally {
                    tickPending.set(false);
                }
            });
            if (!accepted) tickPending.set(false);
        }, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        clock.shutdownNow();
    }
}
