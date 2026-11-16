package com.skribbl.client.net;

import com.skribbl.client.controller.ClientController;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class SocketClient {
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private Socket socket;
    private BufferedWriter writer;
    private ServerReaderThread readerThread;
    private final Object writeLock = new Object();
    private volatile boolean connected = false;

    public void connect(String host, int port, ClientController controller) throws IOException {
        disconnect();
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);

        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        connected = true;
        readerThread = new ServerReaderThread(reader, controller, this);
        readerThread.start();
    }

    public void send(MessageType type, Object payload) {
        if (!connected || writer == null) return;
        synchronized (writeLock) {
            try {
                String jsonLine = JsonCodec.serializeMessage(type, payload);
                writer.write(jsonLine);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                handleDisconnect("Lỗi gửi gói tin tới Server: " + e.getMessage());
            }
        }
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    void handleDisconnect(String reason) {
        if (!connected) return;
        disconnect();
    }

    public void disconnect() {
        connected = false;
        if (readerThread != null) {
            readerThread.stopReading();
            readerThread = null;
        }
        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {
        }
    }
}