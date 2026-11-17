import com.skribbl.server.game.*;
import com.skribbl.server.room.*;
import com.skribbl.server.net.*;
import com.skribbl.common.dto.*;
import com.skribbl.common.model.*;
import com.skribbl.common.protocol.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lớp kiểm thử độc lập luồng ván đấu phòng riêng (Private Room Flow Verification):
 * Có thể chạy trực tiếp qua hàm main, không cần khởi động JavaFX hay trình chạy JUnit.
 * Tự khởi tạo ServerSocket loopback, ClientSession, GameService và đồng hồ thời gian giả lập AtomicLong.
 */
public final class PrivateFlowCheck implements AutoCloseable {
    // Pool Virtual Thread để thực thi các tác vụ game bất đồng bộ
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    // Đồng hồ giả lập thời gian (nanô giây) để chủ động điều khiển tiến trình thời gian trong test
    private final AtomicLong now = new AtomicLong();
    // Trình quản lý phòng chơi
    private final RoomManager rooms = new RoomManager();
    // Dịch vụ điều phối nghiệp vụ ván chơi
    private final GameService games;
    // ServerSocket trên loopback interface (cổng 0 tự cấp phát)
    private final ServerSocket listener;
    // Danh sách các peer client giả lập tham gia kiểm thử
    private final List<Peer> peers = new ArrayList<>();
    // Biến đếm tổng số lần assertion kiểm tra thành công
    private static int checks;

    /**
     * Đại diện cho một kết nối client kiểm thử: bao gồm Socket client, ClientSession phía server và luồng đọc dữ liệu.
     */
    private record Peer(Socket client, ClientSession session, BufferedReader input) {
        /**
         * Đọc liên tục các dòng JSON từ socket cho tới khi nhận đúng loại gói tin MessageType mong muốn.
         * 
         * @param type Loại gói tin cần đợi.
         * @return NetworkMessage Gói tin nhận được khớp với loại type.
         * @throws Exception Nếu socket đóng hoặc duyệt quá 200 dòng mà không thấy gói tin cần tìm.
         */
        NetworkMessage until(MessageType type) throws Exception {
            for (int i = 0; i < 200; i++) {
                String line = input.readLine();
                require(line != null, "socket closed before " + type);
                NetworkMessage message = JsonCodec.deserialize(line);
                if (message.type() == type) { return message; }
            }
            throw new AssertionError("Missing " + type);
        }
    }

    /**
     * Khởi tạo fixture kiểm thử: tạo ServerSocket, nạp danh sách từ mẫu, tạo GameService và đăng ký hook sự kiện.
     */
    public PrivateFlowCheck() throws Exception {
        listener = new ServerSocket(0);
        WordRepository words = new WordRepository(new ByteArrayInputStream(
                "word\napple\nhouse\ncat\ndog\ncar\n".getBytes(StandardCharsets.UTF_8)));
        games = new GameService(rooms, words, tasks, now::get);
        rooms.setRoomCreatedHandler(games::onCreated);
        rooms.setJoinedHandler(games::onJoined);
        rooms.setLeftHandler(games::onLeft);
    }

    /**
     * Tạo và kết nối một Peer giả lập mới tới listener:
     * 
     * @param id ID người chơi đồng thời là nickname.
     * @return Peer đối tượng đại diện cho client kiểm thử.
     */
    private Peer peer(String id) throws Exception {
        Socket client = new Socket("127.0.0.1", listener.getLocalPort()); client.setSoTimeout(2000);
        ClientSession session = new ClientSession(listener.accept());
        session.setPlayerId(id); session.setNickname(id);
        Peer peer = new Peer(client, session, new BufferedReader(new InputStreamReader(
                client.getInputStream(), StandardCharsets.UTF_8)));
        peers.add(peer); return peer;
    }

    /**
     * Cho một client tham gia vào phòng chơi đã tạo.
     * 
     * @param room Phòng chơi mục tiêu.
     * @param peer Client tham gia.
     */
    private void join(GameRoom room, Peer peer) {
        StringBuilder error = new StringBuilder();
        require(rooms.joinRoom(room.getRoomCode(), peer.session(), error), "join: " + error);
    }

    /**
     * Khởi động ván chơi: Drawer bấm Start, chờ nhận WORD_CHOICES và chọn từ đầu tiên.
     * 
     * @param room   Phòng chơi.
     * @param drawer Client người vẽ.
     * @return WordChoicesDto Danh sách lựa chọn từ nhận được.
     */
    private WordChoicesDto start(GameRoom room, Peer drawer) throws Exception {
        games.onStart(drawer.session());
        WordChoicesDto dto = JsonCodec.decodePayload(drawer.until(MessageType.WORD_CHOICES), WordChoicesDto.class);
        games.onChoose(drawer.session(), new ChooseWordDto(dto.turnId(), 0));
        require(room.getStatus() == RoomStatus.DRAWING, "must be drawing");
        return dto;
    }

    /**
     * Kịch bản kiểm thử luồng chơi chính:
     * - Bảo mật từ khóa: người vẽ thấy từ thật, người đoán thấy mặt nạ gạch dưới.
     * - Tranh chấp đồng thời (concurrency): 2 luồng gửi đáp án cùng lúc nhưng điểm chỉ cộng 1 lần.
     * - Nét vẽ và đồng bộ join muộn (CANVAS_SYNC).
     * - Rời phòng (LEAVE_ROOM) nhận ACK xác nhận và giữ kết nối socket.
     */
    void privateGame() throws Exception {
        Peer a = peer("A"), b = peer("B"), c = peer("C");
        GameRoom room = rooms.createRoom(a.session(), 1, 60); join(room, b); join(room, c);
        WordChoicesDto choice = start(room, a);
        TurnStartDto ta = JsonCodec.decodePayload(a.until(MessageType.TURN_START), TurnStartDto.class);
        TurnStartDto tb = JsonCodec.decodePayload(b.until(MessageType.TURN_START), TurnStartDto.class);
        // Xác minh người vẽ và người đoán nhận wordDisplay khác nhau (bảo mật từ khóa)
        require(!ta.wordDisplay().equals(tb.wordDisplay()), "answer must be private");
        now.addAndGet(30_000_000_000L);
        String answer = choice.choices().get(0);
        CountDownLatch start = new CountDownLatch(1);
        // Mô phỏng 2 tác vụ gửi đáp án song song cùng thời điểm
        Future<?> first = tasks.submit(() -> { await(start); games.onChat(b.session(), new ChatSendDto(answer)); });
        Future<?> second = tasks.submit(() -> { await(start); games.onChat(b.session(), new ChatSendDto(answer)); });
        start.countDown(); first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS);
        room.withStateLock(() -> {
            // Xác minh B chỉ nhận đúng 300 điểm và A chỉ nhận 50 điểm thưởng 1 lần
            require(room.getPlayer("B").score() == 300, "duplicate guess cannot award twice");
            require(room.getPlayer("A").score() == 50, "drawer bonus once");
        });
        games.onDrawStroke(a.session(), new DrawStrokeDto(1, 2, 3, 4, "#000000", 5, false));
        // Người chơi D vào phòng giữa chừng
        Peer d = peer("D"); join(room, d);
        TurnStartDto td = JsonCodec.decodePayload(d.until(MessageType.TURN_START), TurnStartDto.class);
        CanvasSyncDto sync = JsonCodec.decodePayload(d.until(MessageType.CANVAS_SYNC), CanvasSyncDto.class);
        // Xác minh người vào muộn nhận đúng snapshot nét vẽ đã có
        require(sync.strokes().size() == 1, "late join history exactly once");
        games.onChat(c.session(), new ChatSendDto(answer));
        // Khi tất cả người đoán đều đoán đúng -> kết thúc lượt sớm
        require(room.getStatus() == RoomStatus.TURN_REVEAL, "all eligible guessed");
        room.withStateLock(() -> require(room.getPlayer("A").score() == 100, "two drawer bonuses"));
        // D rời phòng và nhận gói LEAVE_ROOM ACK
        rooms.leaveRoom(d.session(), true); d.until(MessageType.LEAVE_ROOM);
        require(d.session().getCurrentRoomCode() == null && d.session().isConnected(), "leave keeps connection");
    }

    /**
     * Kịch bản kiểm thử quá hạn thời gian vẽ (deadline expiry):
     * Tăng clock giả qua 61s trước khi B chat, đảm bảo không cộng điểm sau deadline.
     */
    // Tăng clock giả qua deadline trước tick để kiểm tra không chấm đáp án đến muộn.
    void expiry() throws Exception {
        Peer a = peer("E"), b = peer("F");
        GameRoom room = rooms.createRoom(a.session(), 1, 60); join(room, b);
        WordChoicesDto choices = start(room, a);
        now.addAndGet(61_000_000_000L);
        games.onChat(b.session(), new ChatSendDto(choices.choices().get(0)));
        require(room.getStatus() == RoomStatus.TURN_REVEAL, "expiry before tick");
        room.withStateLock(() -> require(room.getPlayer("F").score() == 0, "no score after deadline"));
    }
    
    /**
     * Tiện ích chờ mở chốt CountDownLatch với xử lý ngắt luồng an toàn.
     */
    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
    }

    /**
     * Hàm kiểm tra assertion điều kiện đúng, tăng biến đếm checks.
     */
    private static void require(boolean value, String message) {
        checks++; if (!value) { throw new AssertionError(message); }
    }

    /**
     * Dọn dẹp đóng tất cả phòng chơi, socket client và dừng pool Virtual Thread.
     */
    @Override public void close() throws Exception {
        rooms.closeAll();
        for (Peer peer : peers) { peer.session().close(); peer.client().close(); }
        listener.close(); tasks.shutdownNow(); tasks.awaitTermination(3, TimeUnit.SECONDS);
    }

    /**
     * Hàm main chạy toàn bộ quy trình kiểm thử độc lập.
     */
    public static void main(String[] args) throws Exception {
        try (PrivateFlowCheck test = new PrivateFlowCheck()) {
            test.privateGame(); test.expiry();
        }
        System.out.println("PASS " + checks + " checks");
    }
}
