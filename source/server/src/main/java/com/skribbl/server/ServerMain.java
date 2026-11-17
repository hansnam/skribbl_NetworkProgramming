package com.skribbl.server;

import com.skribbl.server.game.*;
import com.skribbl.server.handler.ServerPacketRouter;
import com.skribbl.server.net.TcpServer;
import com.skribbl.server.room.RoomManager;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main application entry point for the Skribbl Multiplayer Server.
 */
public class ServerMain {

    public static final int DEFAULT_PORT = 8888;

    /**
     * Hàm khởi động chính của Server Skribbl:
     * Khởi tạo toàn bộ các dịch vụ, kết nối các callback/hook và duy trì server chạy.
     *
     * @param args Tham số dòng lệnh: args[0] là cổng port (mặc định DEFAULT_PORT nếu không truyền).
     * @throws Exception Nếu xảy ra lỗi khởi động server.
     */
    public static void main(String[] args) throws Exception{
        int port = args.length == 0 ? DEFAULT_PORT : Integer.parseInt(args[0]);
        if (port < 1 || port > 65535) { throw new IllegalArgumentException("Port must be 1..65535"); }

        // 1. Nạp kho từ vựng tiếng Anh từ file CSV
        WordRepository words = new WordRepository(WordRepository.class.getResourceAsStream("/words_en.csv"));

        // 2. Tạo ThreadPool sử dụng Virtual Threads (Java 21) cho các tác vụ nền
        ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();

        // 3. Khởi tạo quản lý danh sách phòng
        RoomManager rooms = new RoomManager();

        // 4. Khởi tạo GameService điều phối giữa mạng và logic game
        GameService games = new GameService(rooms, words, tasks, System::nanoTime);

        // 5. Nối các hook vòng đời giữa RoomManager và GameService
        // (tạo runtime khi tạo phòng; báo engine/canvas khi có người vào/rời phòng)
        rooms.setRoomCreatedHandler(games::onCreated);
        rooms.setJoinedHandler(games::onJoined);
        rooms.setLeftHandler(games::onLeft);

        // 6. Khởi tạo bộ định tuyến gói tin ServerPacketRouter và nối các handler xử lý
        ServerPacketRouter router = new ServerPacketRouter(rooms);
        router.setStartGameHandler((room, sender) -> games.onStart(sender));
        router.setWordChoiceHandler(games::onChoose);
        router.setChatGuessHandler(games::onChat);
        router.setDrawStrokeHandler(games::onDrawStroke);
        router.setClearCanvasHandler(games::onClearCanvas);

        // 7. Khởi tạo TcpServer và thiết lập listener tiếp nhận kết nối / ngắt kết nối
        TcpServer server = new TcpServer(port);
        server.setHandlers((session, message) -> {
            // Tiếp nhận gói tin và chuyển tiếp qua router
            if (session.isConnected()) {
                router.route(session, message);
            }
        }, session -> {
            // Khi client ngắt kết nối: đẩy tác vụ xử lý disconnect vào thread pool nền
            try {
                tasks.execute(() -> rooms.handleDisconnect(session));
            } catch (RejectedExecutionException _) {}
        });

        // 8. Khởi động đồng hồ đếm giờ nhịp tim (1 giây/lần)
        TurnScheduler clock = new TurnScheduler(games, tasks);

        // 9. Cơ chế ShutdownHook đảm bảo đóng socket và dọn dẹp tài nguyên an toàn khi tắt server
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean closing = new AtomicBoolean();

        Runnable stop = () -> {
            if (!closing.compareAndSet(false, true))
                return;
            clock.close();
            server.stop();
            rooms.closeAll();
            tasks.shutdown();

            try {
                if (!tasks.awaitTermination(3, TimeUnit.SECONDS))
                    tasks.shutdownNow();
            } catch (InterruptedException e) {
                tasks.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                stopped.countDown();
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(stop, "server-shutdown"));

        // 10. Bắt đầu lắng nghe kết nối socket và giữ tiến trình server chạy
        try {
            server.start();
            stopped.await();
        } finally {
            stop.run();
        }
    }
}

