package com.skribbl.server.net;

import com.skribbl.common.protocol.NetworkMessage;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Multi-threaded TCP Server using Java 21 Virtual Threads for concurrent client handling.
 */
public class TcpServer {

    private final int port;
    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

    private BiConsumer<ClientSession, NetworkMessage> messageHandler;
    private Consumer<ClientSession> sessionDisconnectHandler;

    public TcpServer(int port) {
        this.port = port;
    }

    public void setHandlers(BiConsumer<ClientSession, NetworkMessage> messageHandler,
                            Consumer<ClientSession> sessionDisconnectHandler) {
        this.messageHandler = messageHandler;
        this.sessionDisconnectHandler = sessionDisconnectHandler;
    }

    /**
     * Starts listening for client connections. Runs the accept loop on a virtual thread.
     */
    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        this.serverSocket = new ServerSocket(port);
        this.running.set(true);

        System.out.printf("[TcpServer] Server started on port %d with Java 21 Virtual Threads%n", getPort());

        Thread.ofVirtual().name("tcp-acceptor").start(this::runAcceptLoop);
    }

    private void runAcceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                ClientSession session = new ClientSession(clientSocket);
                sessions.put(session.getSessionId(), session);

                session.setHandlers(
                        (sess, msg) -> {
                            if (messageHandler != null) {
                                messageHandler.accept(sess, msg);
                            }
                        },
                        (sess) -> {
                            sessions.remove(sess.getSessionId());
                            if (sessionDisconnectHandler != null) {
                                sessionDisconnectHandler.accept(sess);
                            }
                        }
                );

                session.startReading();
                System.out.printf("[TcpServer] Accepted connection from %s (Session ID: %s, Total: %d)%n",
                        clientSocket.getRemoteSocketAddress(), session.getSessionId(), sessions.size());
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
                System.err.printf("[TcpServer] Accept error: %s%n", e.getMessage());
            }
        }
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }

        // Close all sessions
        for (ClientSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        System.out.println("[TcpServer] Server stopped.");
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public Collection<ClientSession> getSessions() {
        return sessions.values();
    }

    public ClientSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }
}

