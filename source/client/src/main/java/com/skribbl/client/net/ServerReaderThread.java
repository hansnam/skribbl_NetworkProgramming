package com.skribbl.client.net;

import com.skribbl.client.controller.ClientController;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.NetworkMessage;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;

/**
 * Luồng nền đọc bất đồng bộ từng dòng NDJSON từ Server mà không làm đơ giao diện JavaFX.
 */
public class ServerReaderThread extends Thread {
    private final BufferedReader reader;
    private final ClientController controller;
    private volatile boolean running = true;

    public ServerReaderThread(BufferedReader reader, ClientController controller) {
        super("Skribbl-ServerReader");
        this.reader = reader;
        this.controller = controller;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            String jsonLine;
            while (running && (jsonLine = reader.readLine()) != null) {
                if (jsonLine.isBlank()) continue;
                try {
                    NetworkMessage message = JsonCodec.deserialize(jsonLine);
                    controller.handleIncomingMessage(message);
                } catch (Exception parseEx) {
                    System.err.println("[Client] Lỗi giải mã JSON: " + parseEx.getMessage());
                }
            }
            // readLine() trả về null nghĩa là Server đã đóng luồng (EOF)
            if (running) {
                throw new EOFException("Server đã đóng kết nối (EOF).");
            }
        } catch (EOFException eof) {
            if (running) controller.onConnectionLost("Server đã ngắt kết nối (EOF).");
        } catch (SocketException se) {
            if (running) controller.onConnectionLost("Mất kết nối Socket tới Server: " + se.getMessage());
        } catch (IOException ioe) {
            if (running) controller.onConnectionLost("Lỗi đọc luồng dữ liệu mạng: " + ioe.getMessage());
        }
    }

    public void stopReading() {
        running = false;
        interrupt();
    }
}