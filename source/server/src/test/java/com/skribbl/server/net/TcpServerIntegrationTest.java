package com.skribbl.server.net;
import com.skribbl.server.game.GameService;
import com.skribbl.server.game.WordRepository;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


import com.skribbl.common.dto.ConnectAckDto;
import com.skribbl.common.dto.ConnectReqDto;
import com.skribbl.common.dto.CreateRoomReqDto;
import com.skribbl.common.dto.JoinRoomReqDto;
import com.skribbl.common.dto.RoomUpdateDto;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;
import com.skribbl.common.protocol.NetworkMessage;
import com.skribbl.server.handler.ServerPacketRouter;
import com.skribbl.server.room.RoomManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TcpServerIntegrationTest {

    private TcpServer server;
    private RoomManager roomManager;
    private ServerPacketRouter packetRouter;

    @BeforeEach
    void setUp() throws IOException {
        roomManager = new RoomManager();
        attachGameRuntime();
        packetRouter = new ServerPacketRouter(roomManager);
        server = new TcpServer(0);
        server.setHandlers(packetRouter::route,
                session -> gameTasks.execute(() -> roomManager.handleDisconnect(session)));
        server.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        try {
            // Đóng transport trước, khi executor còn nhận được task disconnect.
            if (server != null) {
                server.stop();
            }
        } finally {
            try {
                if (roomManager != null) {
                    roomManager.closeAll();
                }
            } finally {
                // Luôn dừng worker, kể cả khi đóng socket/phòng gặp lỗi.
                if (gameTasks != null) {
                    gameTasks.shutdownNow();
                    assertTrue(gameTasks.awaitTermination(3, TimeUnit.SECONDS));
                }
            }
        }
    }

    private static class TestClientHelper implements AutoCloseable {
        final Socket socket;
        final BufferedReader reader;
        final BufferedWriter writer;

        TestClientHelper(int port) throws IOException {
            this.socket = new Socket("localhost", port);
            this.socket.setTcpNoDelay(true);
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        void send(MessageType type, Object payload) throws IOException {
            String json = JsonCodec.serializeMessage(type, payload);
            writer.write(json);
            writer.newLine();
            writer.flush();
        }

        NetworkMessage readMessage() throws IOException {
            String line = reader.readLine();
            assertNotNull(line, "Server closed connection unexpectedly");
            return JsonCodec.deserialize(line);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    @DisplayName("Full integration test: Connect -> Create Room -> Join Room -> Ping/Pong")
    void testConnectAndCreateRoomFlow() throws Exception {
        int port = server.getPort();

        try (TestClientHelper client1 = new TestClientHelper(port);
             TestClientHelper client2 = new TestClientHelper(port)) {

            // Client 1: CONNECT_REQ
            client1.send(MessageType.CONNECT_REQ, new ConnectReqDto("PlayerOne"));
            NetworkMessage ack1 = client1.readMessage();
            assertEquals(MessageType.CONNECT_ACK, ack1.type());
            ConnectAckDto ackDto1 = JsonCodec.decodePayload(ack1, ConnectAckDto.class);
            assertNotNull(ackDto1.playerId());
            assertEquals("PlayerOne", ackDto1.nickname());

            // Client 1: PING -> PONG
            client1.send(MessageType.PING, null);
            NetworkMessage pong = client1.readMessage();
            assertEquals(MessageType.PONG, pong.type());

            // Client 1: CREATE_ROOM
            client1.send(MessageType.CREATE_ROOM, new CreateRoomReqDto(3, 60));
            // First message is room update
            NetworkMessage roomUpdMsg1 = client1.readMessage();
            assertEquals(MessageType.ROOM_UPDATE, roomUpdMsg1.type());
            RoomUpdateDto roomUpdate1 = JsonCodec.decodePayload(roomUpdMsg1, RoomUpdateDto.class);
            assertNotNull(roomUpdate1.roomCode());
            assertEquals(1, roomUpdate1.players().size());
            assertEquals(ackDto1.playerId(), roomUpdate1.hostId());

            // Skip any chat system notification
            String roomCode = roomUpdate1.roomCode();

            // Client 2: CONNECT_REQ
            client2.send(MessageType.CONNECT_REQ, new ConnectReqDto("PlayerTwo"));
            NetworkMessage ack2 = client2.readMessage();
            assertEquals(MessageType.CONNECT_ACK, ack2.type());

            // Client 2: JOIN_ROOM
            client2.send(MessageType.JOIN_ROOM, new JoinRoomReqDto(roomCode));

            // Client 2 should receive ROOM_UPDATE
            NetworkMessage c2RoomUpd = client2.readMessage();
            assertEquals(MessageType.ROOM_UPDATE, c2RoomUpd.type());
            RoomUpdateDto c2Dto = JsonCodec.decodePayload(c2RoomUpd, RoomUpdateDto.class);
            assertEquals(roomCode, c2Dto.roomCode());
            assertEquals(2, c2Dto.players().size());
        }
    }

    @Test
    @DisplayName("Quick Join should match two clients into the same PUBLIC room")
    void testQuickJoinMatchmakingFlow() throws Exception {
        int port = server.getPort();
        try (TestClientHelper client1 = new TestClientHelper(port);
             TestClientHelper client2 = new TestClientHelper(port)) {

            client1.send(MessageType.CONNECT_REQ, new ConnectReqDto("QuickA"));
            assertEquals(MessageType.CONNECT_ACK, client1.readMessage().type());

            client1.send(MessageType.QUICK_JOIN, null);
            NetworkMessage upd1 = client1.readMessage();
            assertEquals(MessageType.ROOM_UPDATE, upd1.type());
            RoomUpdateDto dto1 = JsonCodec.decodePayload(upd1, RoomUpdateDto.class);
            assertEquals(com.skribbl.common.model.RoomType.PUBLIC, dto1.roomType());

            client2.send(MessageType.CONNECT_REQ, new ConnectReqDto("QuickB"));
            assertEquals(MessageType.CONNECT_ACK, client2.readMessage().type());

            client2.send(MessageType.QUICK_JOIN, null);
            NetworkMessage upd2 = client2.readMessage();
            assertEquals(MessageType.ROOM_UPDATE, upd2.type());
            RoomUpdateDto dto2 = JsonCodec.decodePayload(upd2, RoomUpdateDto.class);
            assertEquals(dto1.roomCode(), dto2.roomCode());
            assertEquals(2, dto2.players().size());
        }
    }

    private ExecutorService gameTasks;

    private void attachGameRuntime() throws IOException {
        gameTasks = Executors.newVirtualThreadPerTaskExecutor();
        WordRepository words = new WordRepository(
                WordRepository.class.getResourceAsStream("/words_en.csv"));
        GameService games = new GameService(roomManager, words, gameTasks, System::nanoTime);
        roomManager.setRoomCreatedHandler(games::onCreated);
        roomManager.setJoinedHandler(games::onJoined);
        roomManager.setLeftHandler(games::onLeft);
    }
}

