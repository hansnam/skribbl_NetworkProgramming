package com.skribbl.server.game;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.skribbl.common.protocol.NetworkMessage;
import com.skribbl.server.net.ClientSession;

public class RoomOutboundDispatcher {
    public record Delivery(ClientSession recipent, NetworkMessage message) {};
    public final ArrayBlockingQueue<List<Delivery>> queue = new ArrayBlockingQueue<>(256);
    private final Runnable onFailure;
    private final Thread drainer;
    private volatile boolean accepting = true;

    public RoomOutboundDispatcher(String roomCode, Runnable onFailure) {
        this.onFailure = onFailure;
        drainer = Thread.ofVirtual().name("room-out-" + roomCode).start(this::drain);
    }

    public boolean offer(List<Delivery> batch) {
        return accepting && queue.offer(List.copyOf(batch));
    }

    public void drain() {
        try {
            while (accepting || !queue.isEmpty()) {
                List<Delivery> batch = queue.poll(100, TimeUnit.MILLISECONDS);
                if (batch == null)
                    continue;

                for (Delivery delivery : batch) {
                    delivery.recipent().send(delivery.message());
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            System.err.println("Room send failed: " + ex);
            onFailure.run();
        }
    }

    // Chỉ gọi sau khi đã thả registryLock/roomLock.
    public void closeAndAwait() {
        accepting = false;
        try {
            drainer.join(3000);
            if (drainer.isAlive()) {
                queue.clear();
                drainer.interrupt();
            }
        } catch (InterruptedException ex) {
            drainer.interrupt();
            Thread.currentThread().interrupt();
        }
    }

    public void stopNow() {
        accepting = false;
        queue.clear();
        drainer.interrupt();
    }
}
