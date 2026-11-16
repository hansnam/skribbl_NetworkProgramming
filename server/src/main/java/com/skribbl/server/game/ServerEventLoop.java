package com.skribbl.server.game;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class ServerEventLoop implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ServerEventLoop.class.getName());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("server-events").factory()
    );

    public boolean execute(Runnable event) {
        Objects.requireNonNull(event, "event");
        try {
            worker.execute(() -> {
                try {
                    event.run();
                } catch (RuntimeException ex) {
                    // Ghi nhận RuntimeException rồi xử lý event tiếp theo.
                    // Không rollback thay đổi trạng thái mà event đã thực hiện.
                    LOG.log(System.Logger.Level.ERROR, "Server event failed", ex);
                }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            return false; // Server đang shutdown.
        }
    }

    @Override 
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(3, TimeUnit.SECONDS)) {
                int dropped = worker.shutdownNow().size();
                if (dropped > 0)
                    LOG.log(System.Logger.Level.WARNING, "Dropped pending event: " + dropped);

                if (!worker.awaitTermination(3, TimeUnit.SECONDS))
                    LOG.log(System.Logger.Level.WARNING, "Server event worker has not terminated");
            }
        } catch (InterruptedException ex) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
