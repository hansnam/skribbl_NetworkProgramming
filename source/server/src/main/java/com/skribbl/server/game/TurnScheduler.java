package com.skribbl.server.game;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TurnScheduler implements AutoCloseable {
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("turn-clock").factory()
    );
    private final AtomicBoolean tickPending = new AtomicBoolean();

    // Thread pool để giao các tác vụ tick phòng chạy ngầm bất đồng bộ
    private final ExecutorService tasks;
    // Tham chiếu tới GameService để gọi hàm tick cho từng phòng
    private final GameService games;

    /**
     * Khởi tạo TurnScheduler: Đặt lịch gọi phương thức dispatch() đều đặn mỗi 1 giây.
     *
     * @param games Dịch vụ GameService.
     * @param tasks Thread pool thực thi công việc tick.
     */
    public TurnScheduler(GameService games, ExecutorService tasks) {
        this.games = games;
        this.tasks = tasks;
        clock.scheduleAtFixedRate(this::dispatch, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Được đồng hồ clock gọi mỗi giây:
     * Quét qua danh sách tất cả các phòng đang hoạt động,
     * và đẩy tác vụ games.tick(runtime) vào thread pool tasks để chạy bất đồng bộ.
     * Sử dụng tickPending.compareAndSet(false, true) để đảm bảo nếu phòng đang xử lý tick cũ
     * thì không giao thêm tick mới, tránh nghẽn luồng.
     */
    private void dispatch() {
        try {
            for (RoomRuntime runtime : games.snapshot()) {
                // Nếu phòng không active hoặc đang có tick chờ chạy -> bỏ qua nhịp này
                if (!runtime.active() || !runtime.tickPending.compareAndSet(false, true))
                    continue;

                try {
                    // Đẩy tác vụ tick phòng vào ExecutorService
                    tasks.execute(() -> {
                        try {
                            games.tick(runtime); // Xử lý kiểm tra hạn giờ vẽ/chọn từ dưới khóa phòng
                        } catch (RuntimeException ex) {
                            runtime.fail();
                            System.err.println("Tick failed: " + ex);
                        } finally {
                            // Luôn mở lại cờ tickPending trong khối finally dù thành công hay lỗi
                            runtime.tickPending.set(false);
                        }
                    });
                } catch (RejectedExecutionException ex) {
                    runtime.tickPending.set(false);
                }
            }
        } catch (RuntimeException ex) {
            System.err.println("Tick dispatch failed: " + ex);
        }
    }

        @Override
    public void close() {
        clock.shutdownNow();
    }
}
