package com.skribbl.server.net;

import com.skribbl.common.dto.ErrorMsgDto;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.common.protocol.NetworkMessage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Manages an individual TCP client connection on the Server.
 * Runs reading in a Java 21 Virtual Thread and provides thread-safe synchronous writing.
 */
public class ClientSession {

    private final String sessionId;
    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile String playerId;
    private volatile String nickname;
    private volatile String currentRoomCode;

    private BiConsumer<ClientSession, NetworkMessage> messageHandler;
    private Consumer<ClientSession> disconnectHandler;

    public ClientSession(Socket socket) throws IOException {
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
        this.socket = Objects.requireNonNull(socket, "Socket cannot be null");
        this.socket.setTcpNoDelay(true);

        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    public void setHandlers(BiConsumer<ClientSession, NetworkMessage> messageHandler,
                            Consumer<ClientSession> disconnectHandler) {
        this.messageHandler = messageHandler;
        this.disconnectHandler = disconnectHandler;
    }

    /**
     * Starts the virtual thread to read incoming NDJSON lines asynchronously.
     */
    public void startReading() {
        Thread.ofVirtual().name("session-reader-" + sessionId).start(() -> {
            try {
                String line;
                while (!closed.get() && (line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        NetworkMessage message = JsonCodec.deserialize(line);
                        if (message.type() == MessageType.PING) {
                            send(NetworkMessage.of(MessageType.PONG));
                            continue;
                        }
                        if (messageHandler != null) {
                            messageHandler.accept(this, message);
                        }
                    } catch (Exception e) {
                        System.err.printf("[Session %s] Malformed JSON: %s (Error: %s)%n",
                                sessionId, line, e.getMessage());
                        sendError("BAD_PAYLOAD", "Failed to parse message: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                // Connection closed or dropped
            } finally {
                close();
            }
        });
    }

    /**
     * Thread-safe message delivery over TCP socket.
     * Guaranteed single-line JSON followed by standard newline.
     */
    public void send(NetworkMessage message) {
        if (message == null || closed.get()) {
            return;
        }

        synchronized (writeLock) {
            if (closed.get()) {
                return;
            }
            try {
                String json = JsonCodec.serialize(message);
                writer.write(json);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                System.err.printf("[Session %s] Write failed: %s%n", sessionId, e.getMessage());
                close();
            }
        }
    }

    public void send(MessageType type, Object payload) {
        try {
            send(NetworkMessage.of(type, JsonCodec.toNode(payload)));
        } catch (Exception e) {
            System.err.printf("[Session %s] Error sending %s: %s%n", sessionId, type, e.getMessage());
        }
    }

    public void sendError(String code, String message) {
        send(MessageType.ERROR_MSG, new ErrorMsgDto(code, message));
    }

    /**
     * Closes the session and socket, releasing all resources.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try {
            socket.close();
        } catch (IOException ignored) {
        }

        if (disconnectHandler != null) {
            disconnectHandler.accept(this);
        }
    }

    public boolean isConnected() {
        return !closed.get() && socket.isConnected() && !socket.isClosed();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getCurrentRoomCode() {
        return currentRoomCode;
    }

    public void setCurrentRoomCode(String currentRoomCode) {
        this.currentRoomCode = currentRoomCode;
    }

    public Socket getSocket() {
        return socket;
    }

    @Override
    public String toString() {
        return "ClientSession{" +
                "id='" + sessionId + '\'' +
                ", player='" + playerId + '\'' +
                ", nick='" + nickname + '\'' +
                ", room='" + currentRoomCode + '\'' +
                '}';
    }
}
