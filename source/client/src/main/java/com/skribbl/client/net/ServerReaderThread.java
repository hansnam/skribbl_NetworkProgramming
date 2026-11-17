package com.skribbl.client.net;

import com.skribbl.client.controller.ClientController;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.NetworkMessage;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * Luồng nền đọc bất đồng bộ từng dòng NDJSON từ Server mà không làm đơ giao diện JavaFX.
 */
public class ServerReaderThread extends Thread {
    private final BufferedReader reader;
    private final ClientController controller;
    private volatile boolean running = true;
    private final long epoch;

    /**
     * Khởi tạo luồng đọc dữ liệu từ server (Daemon thread):
     *
     * @param reader     Luồng đọc BufferedReader từ socket.
     * @param controller ClientController nhận các gói tin được giải mã.
     * @param epoch      Số phiên kết nối định danh phiên làm việc này.
     */
    public ServerReaderThread(BufferedReader reader, ClientController controller, long epoch) {
        super("client-reader-" + epoch);
        this.reader = reader;
        this.controller = controller;
        this.epoch = epoch;
        setDaemon(true);
    }

    /**
     * Vòng lặp đọc dữ liệu mạng:
     * Liên tục đọc từng dòng text từ socket (reader.readLine()),
     * giải mã chuỗi JSON thành đối tượng NetworkMessage,
     * và gửi tới controller.handleIncomingMessage(message, epoch) để xử lý.
     * Nếu server đóng kết nối hoặc gặp lỗi mạng -> gọi controller.onConnectionLost.
     */
    @Override
    public void run() {
        try {
            String line;

            while (running && (line = reader.readLine()) != null) {
                if (line.isBlank())
                    continue;
                NetworkMessage message;

                try {
                    message = JsonCodec.deserialize(line);
                }
                catch (RuntimeException ex) {
                    System.err.println("Invalid JSON: " + ex.getMessage());
                    continue;
                }
                controller.handleIncomingMessage(message, epoch);
            }

            if (running)
                controller.onConnectionLost("Server closed the connection", epoch);
        } catch (IOException ex) {
            if (running)
                controller.onConnectionLost(ex.getMessage(), epoch);
        }
    }

    public void stopReading() {
        running = false;
        interrupt();
    }
}
