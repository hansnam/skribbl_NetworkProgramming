package com.skribbl.client.net;

import com.skribbl.client.controller.ClientController;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Quản lý kết nối TCP Socket phía Client (UTF-8 NDJSON, bật TCP_NODELAY giảm độ trễ nét vẽ).
 */
public class SocketClient {
    private Socket socket;
    private BufferedWriter writer;
    private BufferedReader reader;
    private ServerReaderThread readerThread;
    private final Object writeLock = new Object();

    public void connect(String host, int port, ClientController controller) throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);

        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        readerThread = new ServerReaderThread(reader, controller);
        readerThread.start();
    }

    public void send(MessageType type, Object payload) {
        if (writer == null || socket == null || socket.isClosed()) return;
        synchronized (writeLock) {
            try {
                String ndjsonLine = JsonCodec.serializeMessage(type, payload);
                writer.write(ndjsonLine);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                System.err.println("[SocketClient] Gửi gói tin thất bại: " + e.getMessage());
            }
        }
    }

    public void disconnect() {
        if (readerThread != null) {
            readerThread.stopReading();
        }
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }
}