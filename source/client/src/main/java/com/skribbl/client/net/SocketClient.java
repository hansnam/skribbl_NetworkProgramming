package com.skribbl.client.net;

import com.skribbl.client.controller.ClientController;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.common.protocol.NetworkMessage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Quản lý kết nối TCP Socket phía Client (UTF-8 NDJSON, bật TCP_NODELAY giảm độ trễ nét vẽ).
 */
public class SocketClient {
    private volatile Connection current;

    /**
     * Thực hiện kết nối TCP tới server và khởi tạo luồng đọc/ghi:
     * - Thiết lập thời gian chờ timeout kết nối là 3000ms (3 giây).
     * - Kích hoạt tcpNoDelay(true) để gửi gói tin ngay lập tức (không chờ Nagle).
     * - Khởi động ServerReaderThread để đọc dữ liệu và Virtual Thread writerThread để gửi dữ liệu.
     *
     * @param host       Địa chỉ IP hoặc hostname của server.
     * @param port       Cổng kết nối TCP (ví dụ: 8888).
     * @param controller ClientController quản lý phiên làm việc.
     * @param epoch      Số phiên định danh kết nối (tránh xử lý chồng chéo).
     * @throws IOException Nếu kết nối tới server bị từ chối hoặc hết thời gian timeout.
     */
    public void connect(String host, int port, ClientController controller, long epoch) throws IOException {
        // Socket thuộc lần connect này, không dùng field để retry ghi đè phiên đang chạy.
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8
            ));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8
            ));
            Connection connection = new Connection(socket, writer, epoch);

            synchronized (this) {
                if (!controller.isCurrentEpoch(epoch)) {
                    connection.close();
                    return;
                }
                if (current != null)
                    throw new IOException("A connection already exists");
                current = connection;
            }

            connection.readerThread = new ServerReaderThread(reader, controller, epoch);
            connection.writerThread = Thread.ofVirtual().name("client-writer-" + epoch)
                    .start(() -> writeLoop(connection, controller));

            if (connection.closed.get()) {
                connection.readerThread.stopReading();
                connection.writerThread.interrupt();
            } else {
                connection.readerThread.start();
            }
        } catch (IOException | RuntimeException ex) {
            try {
                socket.close();
            } catch (IOException closeError) {
                ex.addSuppressed(closeError);
            }
            throw ex;
        }
    }

    /**
     * Đẩy gói tin vào hàng đợi gửi (outgoing) của phiên kết nối hiện tại.
     * Hàm này non-blocking, chạy an toàn trên JavaFX thread vì không gọi ghi socket trực tiếp.
     *
     * @param type    Loại gói tin (MessageType).
     * @param payload Dữ liệu đính kèm (DTO hoặc null).
     * @return boolean:
     *         - true: Đã nhận vào hàng đợi thành công.
     *         - false: Kết nối đã đóng hoặc hàng đợi gửi bị đầy (256 gói tin).
     */
    public boolean send(MessageType type, Object payload) {
        Connection c = current;
        if (c == null || c.closed.get())
            return false;

        NetworkMessage packet = NetworkMessage.of(type, JsonCodec.toNode(payload));
        if (c.outgoing.offer(packet))
            return true;

        c.close();
        return false;
    }

    /**
     * Ngắt kết nối mạng hiện tại và đóng tất cả tài nguyên (socket, reader, writer).
     */
    public void disconnect() {
        Connection previous;
        synchronized (this) {
            previous = current;
            current = null;
        }
        if (previous != null)
            previous.close();
    }

    /**
     * Ngắt kết nối nếu phiên hiện tại đúng bằng epoch chỉ định (tránh ngắt nhầm phiên kết nối mới).
     *
     * @param epoch Số phiên cần ngắt kết nối.
     */
    public void disconnect(long epoch) {
        Connection previous;
        synchronized (this) {
            previous = current;
            if (previous == null || previous.epoch != epoch)
                return;
            current = null;
        }
        previous.close();
    }

    /**
     * Kiểm tra trạng thái kết nối mạng hiện tại.
     *
     * @return boolean: true nếu đang kết nối bình thường; false nếu chưa kết nối hoặc socket đã đóng.
     */
    public boolean isConnected() {
        Connection c = current;
        return c != null && !c.closed.get() && !c.socket.isClosed();
    }



    /**
     * Vòng lặp của Virtual Thread writerThread:
     * Liên tục rút từng gói tin trong hàng đợi outgoing (outgoing.take()) và ghi xuống socket TCP.
     *
     * @param c          Đối tượng Connection của phiên.
     * @param controller ClientController để báo mất kết nối nếu gặp lỗi I/O.
     */
    private void writeLoop(Connection c, ClientController controller) {
        try {
            while (!c.closed.get()) {
                NetworkMessage message = c.outgoing.take();
                c.writer.write(JsonCodec.serialize(message));
                c.writer.write('\n');
                c.writer.flush();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
            controller.onConnectionLost("Send failed: " + ex.getMessage(), c.epoch);
        } finally { c.close(); }
    }

    /**
     * Đóng gói toàn bộ tài nguyên của một phiên kết nối TCP:
     * - socket: Kết nối TCP thực tế tới server.
     * - writer: Luồng ghi dữ liệu UTF-8 xuống socket.
     * - outgoing: Hàng đợi gửi bất đồng bộ (tối đa 256 gói tin).
     * - closed: Cờ AtomicBoolean đánh dấu kết nối đã đóng chưa (chống đóng lặp).
     * - epoch: Số phiên kết nối (chống xử lý nhầm gói tin của kết nối cũ).
     * - writerThread, readerThread: Luồng ghi và luồng đọc dữ liệu mạng.
     */
    private static final class Connection {
        final Socket socket;
        final BufferedWriter writer;
        final ArrayBlockingQueue<NetworkMessage> outgoing = new ArrayBlockingQueue<>(256);
        final AtomicBoolean closed = new AtomicBoolean();
        final long epoch;
        Thread writerThread;
        ServerReaderThread readerThread;
        Connection(Socket socket, BufferedWriter writer, long epoch) {
            this.socket = socket;
            this.writer = writer;
            this.epoch = epoch;
        }

        /**
         * Đóng phiên kết nối an toàn luồng:
         * Dừng reader, ngắt writer, xóa hàng đợi và đóng socket.
         */
        void close() {
            if (!closed.compareAndSet(false, true))
                return;
            if (readerThread != null)
                readerThread.stopReading();
            if (writerThread != null)
                writerThread.interrupt();
            outgoing.clear();
            try {
                socket.close();
            } catch (IOException ex) {
                System.err.println("Close socket failed: " + ex.getMessage());
            }
        }
    }
}
