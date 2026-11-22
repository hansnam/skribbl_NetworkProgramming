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

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? DEFAULT_PORT : Integer.parseInt(args[0]);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be 1..65535");
        }

        WordRepository words = new WordRepository(WordRepository.class.getResourceAsStream("/words_en.csv"));
        ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
        RoomManager rooms = new RoomManager();
        GameService games = new GameService(rooms, words, tasks, System::nanoTime);

        rooms.setRoomCreatedHandler(games::onCreated);
        rooms.setJoinedHandler(games::onJoined);
        rooms.setLeftHandler(games::onLeft);

        ServerPacketRouter router = new ServerPacketRouter(rooms);
        router.setStartGameHandler((room, sender) -> games.onStart(sender));
        router.setWordChoiceHandler(games::onChoose);
        router.setChatGuessHandler(games::onChat);
        router.setDrawStrokeHandler(games::onDrawStroke);
        router.setFillCanvasHandler(games::onFillCanvas);
        router.setUndoCanvasHandler(games::onUndoCanvas);
        router.setClearCanvasHandler(games::onClearCanvas);
        router.setReactionHandler(games::onReaction);

        TcpServer server = new TcpServer(port);
        server.setHandlers((session, message) -> {
            if (session.isConnected()) {
                router.route(session, message);
            }
        }, session -> {
            try {
                tasks.execute(() -> rooms.handleDisconnect(session));
            } catch (RejectedExecutionException ignored) {
            }
        });

        TurnScheduler clock = new TurnScheduler(games, tasks);

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

        try {
            server.start();
            stopped.await();
        } finally {
            stop.run();
        }
    }
}
