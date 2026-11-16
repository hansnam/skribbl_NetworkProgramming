package com.skribbl.client.net;

import com.skribbl.client.controller.ClientController;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.NetworkMessage;

import java.io.BufferedReader;
import java.io.IOException;

public class ServerReaderThread extends Thread {
    private final BufferedReader reader;
    private final ClientController controller;
    private final SocketClient socketClient;
    private volatile boolean running = true;

    public ServerReaderThread(BufferedReader reader, ClientController controller, SocketClient socketClient) {
        super("ServerReaderThread");
        setDaemon(true);
        this.reader = reader;
        this.controller = controller;
        this.socketClient = socketClient;
    }

    @Override
    public void run() {
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    NetworkMessage msg = JsonCodec.deserialize(line);
                    controller.handleIncomingMessage(msg);
                } catch (Exception parseEx) {
                    System.err.println("[ServerReaderThread] Bỏ qua gói tin lỗi định dạng: " + parseEx.getMessage());
                }
            }
            if (running) {
                socketClient.handleDisconnect("Server đã đóng kết nối.");
                controller.onConnectionLost("Server đã ngắt kết nối.");
            }
        } catch (IOException e) {
            if (running) {
                socketClient.handleDisconnect(e.getMessage());
                controller.onConnectionLost("Mất kết nối tới Server: " + e.getMessage());
            }
        }
    }

    public void stopReading() {
        running = false;
        interrupt();
    }
}