package com.skribbl.client.controller;

import com.skribbl.client.ui.game.GameView;
import com.skribbl.client.ui.lobby.LobbyView;
import com.skribbl.common.dto.*;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;
import com.skribbl.common.protocol.*;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm tra view/controller thật trên FX thread và socket loopback, không mở Stage. */
@Timeout(15)
class ClientFlowTest {
    private ClientController controller;
    private GameView game;
    private LobbyView lobby;
    private ServerSocket listener;
    private Peer peer;
    private final AtomicInteger joins = new AtomicInteger();
    private final AtomicInteger leaves = new AtomicInteger();

    @BeforeAll
    static void startFx() throws Exception {
        // Native JavaFX phải cache trong thư mục được phép ghi, không phụ thuộc user.home.
        if (System.getProperty("javafx.cachedir") == null) {
            Path cache = Path.of("target", "javafx-test-cache").toAbsolutePath();
            Files.createDirectories(cache);
            System.setProperty("javafx.cachedir", cache.toString());
        }
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            // Nạp controls/font/native lần đầu trong bootstrap, trước timeout của từng thao tác test.
            ClientController warmup = new ClientController("Warmup");
            new GameView(warmup);
            new LobbyView(warmup, () -> {});
            warmup.shutdown();
            ready.countDown();
        });
        assertTrue(ready.await(15, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopFx() {
        Platform.exit();
    }

    @BeforeEach
    void setUp() throws Exception {
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(3000);
        fx(() -> {
            controller = new ClientController("Tester");
            game = new GameView(controller);
            controller.setGameView(game);
            lobby = new LobbyView(controller, () -> {});
            controller.setLobbyView(lobby);
            controller.setOnRoomJoinedCallback(room -> joins.incrementAndGet());
            controller.setOnRoomLeftCallback(leaves::incrementAndGet);
            return null;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (controller != null) {
                fx(() -> { controller.shutdown(); return null; });
            }
        } finally {
            if (peer != null) peer.close();
            if (listener != null) listener.close();
        }
    }

    @Test
    void createWaitsForConnectAckAndSnapshotOnlyJoinsOnce() throws Exception {
        connectAndCreate();
        assertEquals("A", fx(controller::getPlayerId));
        assertEquals("ROOM1", fx(controller::getCurrentRoomCode));
        assertEquals(1, joins.get());

        // Cập nhật cùng phòng không được gọi callback chuyển scene lần thứ hai.
        peer.send(MessageType.ROOM_UPDATE, room("ROOM1", RoomStatus.WAITING));
        drainIncoming();
        assertEquals(1, joins.get());
    }

    /** Bấm nút thật ở lobby: cả tạo/vào phòng phải dùng cổng đã nhập, không giữ cứng 8888. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lobbyUsesConfiguredPortForRoomRequests(boolean joining) throws Exception {
        fx(() -> {
            VBox card = (VBox) lobby.getChildren().get(1);
            HBox serverBox = (HBox) card.getChildren().get(1);
            TextField port = (TextField) serverBox.getChildren().get(1);
            HBox joinBox = (HBox) card.getChildren().get(9);
            ((TextField) joinBox.getChildren().getFirst()).setText("ABCD2");
            Button action = joining ? (Button) joinBox.getChildren().getLast()
                    : (Button) card.getChildren().get(6);
            // Dữ liệu sai phải báo tại view trước khi mở socket.
            for (String invalid : List.of("", "abc", "0", "65536")) {
                port.setText(invalid);
                action.fire();
                assertEquals("Cổng TCP phải là số từ 1 đến 65535.",
                        ((Label) card.getChildren().getLast()).getText());
                assertFalse(port.isDisabled());
            }
            port.setText(Integer.toString(listener.getLocalPort()));
            action.fire();
            assertTrue(port.isDisabled());
            return null;
        });
        peer = new Peer(listener.accept());
        peer.expect(MessageType.CONNECT_REQ);
        peer.send(MessageType.CONNECT_ACK, new ConnectAckDto("A", "Tester"));
        peer.expect(joining ? MessageType.JOIN_ROOM : MessageType.CREATE_ROOM);
        peer.send(MessageType.ROOM_UPDATE, room("ABCD2", RoomStatus.WAITING));
        drainIncoming();
        assertEquals("ABCD2", fx(controller::getCurrentRoomCode));
        assertEquals(1, joins.get());
    }

    @Test
    void chatSendsTextButRejectsEmptyTooLongAndDisabledInput() throws Exception {
        connectAndCreate();
        fx(() -> {
            input().setText("  hello  ");
            sendButton().fire();
            assertEquals("", input().getText());
            return null;
        });
        ChatSendDto sent = JsonCodec.decodePayload(peer.expect(MessageType.CHAT_SEND), ChatSendDto.class);
        assertEquals("hello", sent.text());

        fx(() -> {
            input().setText("   ");
            sendButton().fire();
            input().setText("a".repeat(201));
            sendButton().fire();
            game.setChatInputEnabled(false);
            input().setText("hidden answer");
            input().getOnAction().handle(new ActionEvent());
            return null;
        });
        // PONG phải là packet kế tiếp: các thao tác bị chặn ở trên không gửi CHAT_SEND.
        drainIncoming();
    }

    @Test
    void leaveWaitsForAckAndNextCreateReusesRegisteredConnection() throws Exception {
        connectAndCreate();
        fx(() -> { controller.leaveRoom(); return null; });
        peer.expect(MessageType.LEAVE_ROOM);
        assertEquals("ROOM1", fx(controller::getCurrentRoomCode));
        assertEquals(0, leaves.get());

        // Snapshot bình thường không được hủy trạng thái đang chờ leave.
        peer.send(MessageType.ROOM_UPDATE, room("ROOM1", RoomStatus.WAITING));
        drainIncoming();
        fx(() -> { controller.leaveRoom(); return null; });
        drainIncoming(); // Không được gửi LEAVE lần hai khi request đầu vẫn pending.

        peer.send(MessageType.LEAVE_ROOM, null);
        drainIncoming();
        assertNull(fx(controller::getCurrentRoomCode));
        assertEquals("A", fx(controller::getPlayerId));
        assertEquals(1, leaves.get());

        fx(() -> {
            controller.requestCreateRoom("127.0.0.1", listener.getLocalPort(), "Tester", 1, 60);
            return null;
        });
        peer.expect(MessageType.CREATE_ROOM); // Không có CONNECT_REQ thứ hai.
        peer.send(MessageType.ROOM_UPDATE, room("ROOM2", RoomStatus.WAITING));
        drainIncoming();
        assertEquals("ROOM2", fx(controller::getCurrentRoomCode));
        assertEquals(2, joins.get());
    }

    @Test
    void oldEpochCannotReplaceIdentityOrDisconnectCurrentSession() throws Exception {
        connectAndCreate();
        controller.handleIncomingMessage(NetworkMessage.of(MessageType.CONNECT_ACK,
                JsonCodec.toNode(new ConnectAckDto("OLD", "Old user"))), 0);
        controller.onConnectionLost("old connection failed", 0);
        drainIncoming();
        assertEquals("A", fx(controller::getPlayerId));
        assertEquals("ROOM1", fx(controller::getCurrentRoomCode));
        assertEquals(0, leaves.get());
        assertFalse(fx(controller::enterOfflineMode));
        drainIncoming(); // Từ chối offline phải giữ nguyên socket đang chạy.
    }

    @Test
    void onlineClearWaitsForServerEchoBeforeErasingCanvas() throws Exception {
        connectAndCreate();
        peer.send(MessageType.ROOM_UPDATE, room("ROOM1", RoomStatus.DRAWING));
        peer.send(MessageType.TURN_START, new TurnStartDto(1, 1, "A", "Tester", "house", 60));
        drainIncoming();
        fx(() -> {
            game.getDrawingCanvas().renderRemoteStroke(new DrawStrokeDto(10, 20, 30, 20, "#000000", 5, false));
            assertEquals(Color.BLACK, canvas().snapshot(null, null).getPixelReader().getColor(20, 20));
            HBox toolbar = (HBox) game.getDrawingCanvas().getBottom();
            ((Button) toolbar.getChildren().getLast()).fire();
            assertEquals(Color.BLACK, canvas().snapshot(null, null).getPixelReader().getColor(20, 20));
            return null;
        });
        peer.expect(MessageType.CLEAR_CANVAS);
        peer.send(MessageType.CLEAR_CANVAS, null);
        drainIncoming();
        assertEquals(Color.WHITE, fx(() -> canvas().snapshot(null, null).getPixelReader().getColor(20, 20)));
    }

    private void connectAndCreate() throws Exception {
        fx(() -> {
            controller.requestCreateRoom("127.0.0.1", listener.getLocalPort(), "Tester", 1, 60);
            return null;
        });
        peer = new Peer(listener.accept());
        peer.expect(MessageType.CONNECT_REQ);
        assertNull(fx(controller::getPlayerId));
        peer.send(MessageType.CONNECT_ACK, new ConnectAckDto("A", "Tester"));
        peer.expect(MessageType.CREATE_ROOM); // Chỉ được gửi sau ACK ở trên.
        peer.send(MessageType.ROOM_UPDATE, room("ROOM1", RoomStatus.WAITING));
        drainIncoming();
    }

    private RoomUpdateDto room(String code, RoomStatus status) {
        return new RoomUpdateDto(code, "A", status, List.of(
                new PlayerDto("A", "Tester", 0, true, status == RoomStatus.DRAWING, false),
                new PlayerDto("B", "Other", 0, false, false, false)), 1, 60, 1);
    }

    private TextField input() {
        return (TextField) ((HBox) ((VBox) game.getRight()).getChildren().getLast()).getChildren().getFirst();
    }

    private Button sendButton() {
        return (Button) ((HBox) ((VBox) game.getRight()).getChildren().getLast()).getChildren().getLast();
    }

    private Canvas canvas() {
        return findCanvas(game.getDrawingCanvas());
    }

    private static Canvas findCanvas(Pane parent) {
        for (javafx.scene.Node node : parent.getChildren()) {
            if (node instanceof Canvas canvas) return canvas;
            if (node instanceof Pane pane) {
                Canvas nested = findCanvas(pane);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private void drainIncoming() throws Exception {
        // Reader enqueue mọi packet trước PING, rồi writer trả PONG. Sau đó đặt FX barrier.
        peer.send(MessageType.PING, null);
        peer.expect(MessageType.PONG);
        fx(() -> null);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }

    private static final class Peer implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader input;
        private final BufferedWriter output;

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(3000);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        void send(MessageType type, Object dto) throws IOException {
            output.write(JsonCodec.serialize(NetworkMessage.of(type, JsonCodec.toNode(dto))));
            output.write('\n');
            output.flush();
        }

        NetworkMessage expect(MessageType type) throws IOException {
            String line = input.readLine();
            assertNotNull(line, "Connection closed before " + type);
            NetworkMessage message = JsonCodec.deserialize(line);
            assertEquals(type, message.type());
            return message;
        }

        @Override public void close() throws IOException {
            socket.close();
        }
    }
}
