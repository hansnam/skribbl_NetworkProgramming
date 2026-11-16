package com.skribbl.server;

import com.skribbl.server.game.ServerEventLoop;
import com.skribbl.server.game.TurnScheduler;
import com.skribbl.server.game.WordRepository;
import com.skribbl.server.handler.ServerPacketRouter;
import com.skribbl.server.net.TcpServer;
import com.skribbl.server.room.GameRoom;
import com.skribbl.server.room.RoomManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ServerMain {

    public static final int DEFAULT_PORT = 8888;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.printf("Invalid port '%s'. Falling back to default %d%n", args[0], DEFAULT_PORT);
            }
        }

        System.out.println("=================================================");
        System.out.println("        SKRIBBL MULTIPLAYER TCP SERVER           ");
        System.out.println("             Java 21 Virtual Threads             ");
        System.out.println("=================================================");

        WordRepository wordRepository = loadWords();
        RoomManager roomManager = new RoomManager();
        roomManager.setWordRepository(wordRepository);

        ServerPacketRouter packetRouter = new ServerPacketRouter(roomManager);

        // Nối dây GameEngine & CanvasSyncService vào ServerPacketRouter
        packetRouter.setStartGameHandler((room, session) -> {
            if (room.getGameEngine() != null) {
                room.getGameEngine().onStart(session);
            }
        });

        packetRouter.setWordChoiceHandler((session, dto) -> {
            GameRoom room = roomManager.getRoom(session.getCurrentRoomCode());
            if (room != null && room.getGameEngine() != null) {
                room.getGameEngine().onChoose(session, dto);
            }
        });

        packetRouter.setChatGuessHandler((session, dto) -> {
            GameRoom room = roomManager.getRoom(session.getCurrentRoomCode());
            if (room != null && room.getGameEngine() != null) {
                room.getGameEngine().onChat(session, dto);
            }
        });

        packetRouter.setStrokeRecordedHandler((room, dto) -> {
            String drawerId = room.getCurrentDrawerId();
            if (drawerId != null) {
                room.getCanvasSync().recordStroke(drawerId, dto);
            }
        });

        packetRouter.setClearCanvasHandler(room -> {
            String drawerId = room.getCurrentDrawerId();
            if (drawerId != null) {
                room.getCanvasSync().clearCanvas(drawerId);
            }
        });

        ServerEventLoop events = new ServerEventLoop();
        TurnScheduler scheduler = new TurnScheduler(events, () -> {
            for (GameRoom room : roomManager.getAllRooms()) {
                if (room.getGameEngine() != null) {
                    room.getGameEngine().tick();
                }
            }
        });

        TcpServer server = new TcpServer(port);
        server.setHandlers(
                (session, message) -> {
                    if (!events.execute(() -> {
                        if (session.isConnected())
                            packetRouter.route(session, message);
                    })) session.close();
                },
                session -> events.execute(() -> roomManager.handleDisconnect(session))
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Server] Shutting down server gracefully...");
            scheduler.close();
            server.stop();
            events.close();
        }, "server-shutdown-hook"));

        try {
            server.start();
            System.out.printf("[Server] Ready and listening for connections on port %d.%n", server.getPort());
        } catch (IOException e) {
            System.err.printf("[Server] Failed to bind to port %d: %s%n", port, e.getMessage());
            System.exit(1);
        }
    }

    private static WordRepository loadWords() {
        try (InputStream is = ServerMain.class.getResourceAsStream("/words_en.csv")) {
            if (is != null) {
                return new WordRepository(is);
            }
        } catch (IOException e) {
            System.err.println("[Server] Cảnh báo lỗi đọc /words_en.csv: " + e.getMessage());
        }
        try {
            String fallback = "word\napple\nbanana\nelephant\nguitar\nmountain\nrocket\ncomputer\nice cream\n";
            return new WordRepository(new ByteArrayInputStream(fallback.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            throw new IllegalStateException("Không thể khởi tạo từ điển mặc định", ex);
        }
    }
}