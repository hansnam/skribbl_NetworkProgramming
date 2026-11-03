package com.skribbl.server;

import com.skribbl.server.handler.ServerPacketRouter;
import com.skribbl.server.net.TcpServer;
import com.skribbl.server.room.RoomManager;

import java.io.IOException;

/**
 * Main application entry point for the Skribbl Multiplayer Server.
 */
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

        RoomManager roomManager = new RoomManager();
        ServerPacketRouter packetRouter = new ServerPacketRouter(roomManager);

        TcpServer server = new TcpServer(port);
        server.setHandlers(
                packetRouter::route,
                session -> {
                    System.out.printf("[Server] Session disconnected: %s (%s)%n",
                            session.getSessionId(), session.getNickname());
                    roomManager.handleDisconnect(session);
                }
        );

        // Register JVM Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Server] Shutting down server gracefully...");
            server.stop();
        }, "server-shutdown-hook"));

        try {
            server.start();
            System.out.printf("[Server] Ready and listening for connections on port %d.%n", server.getPort());
        } catch (IOException e) {
            System.err.printf("[Server] Failed to bind to port %d: %s%n", port, e.getMessage());
            System.exit(1);
        }
    }
}
