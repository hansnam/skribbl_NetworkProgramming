package com.skribbl.server.room;

import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;
import com.skribbl.server.net.ClientSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoomManagerTest {

    private ServerSocket dummyServerSocket;
    private final List<Socket> openSockets = new ArrayList<>();
    private RoomManager roomManager;

    @BeforeEach
    void setUp() throws IOException {
        dummyServerSocket = new ServerSocket(0);
        roomManager = new RoomManager();
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Socket s : openSockets) {
            try {
                s.close();
            } catch (IOException ignored) {}
        }
        if (dummyServerSocket != null && !dummyServerSocket.isClosed()) {
            dummyServerSocket.close();
        }
    }

    private ClientSession createTestSession(String nickname) throws IOException {
        Socket client = new Socket("localhost", dummyServerSocket.getLocalPort());
        Socket serverSide = dummyServerSocket.accept();
        openSockets.add(client);
        openSockets.add(serverSide);

        ClientSession session = new ClientSession(serverSide);
        session.setPlayerId("P-" + System.nanoTime());
        session.setNickname(nickname);
        return session;
    }

    @Test
    @DisplayName("Should create room successfully and set creator as host")
    void testCreateRoom() throws IOException {
        ClientSession hostSession = createTestSession("HostPlayer");
        GameRoom room = roomManager.createRoom(hostSession, 3, 60);

        assertNotNull(room);
        assertEquals(5, room.getRoomCode().length());
        assertEquals(hostSession.getPlayerId(), room.getHostId());
        assertEquals(1, room.getPlayerCount());
        assertEquals(RoomStatus.WAITING, room.getStatus());
        assertEquals(1, roomManager.getRoomCount());
    }

    @Test
    @DisplayName("Should allow second player to join room and reject duplicate nicknames")
    void testJoinRoomAndDuplicateNickname() throws IOException {
        ClientSession host = createTestSession("Alice");
        GameRoom room = roomManager.createRoom(host, 3, 60);

        ClientSession player2 = createTestSession("Bob");
        StringBuilder error = new StringBuilder();
        boolean joined = roomManager.joinRoom(room.getRoomCode(), player2, error);
        assertTrue(joined);
        assertEquals(2, room.getPlayerCount());

        // Duplicate nickname (case-insensitive)
        ClientSession duplicateBob = createTestSession("bob");
        StringBuilder dupError = new StringBuilder();
        boolean dupJoined = roomManager.joinRoom(room.getRoomCode(), duplicateBob, dupError);
        assertFalse(dupJoined);
        assertTrue(dupError.toString().contains("already taken"));
        assertEquals(2, room.getPlayerCount());
    }

    @Test
    @DisplayName("Should reassign host when original host leaves the room")
    void testHostReassignment() throws IOException {
        ClientSession host = createTestSession("HostUser");
        GameRoom room = roomManager.createRoom(host, 3, 60);

        ClientSession player2 = createTestSession("SecondUser");
        StringBuilder err = new StringBuilder();
        roomManager.joinRoom(room.getRoomCode(), player2, err);

        assertEquals(host.getPlayerId(), room.getHostId());

        // Host leaves
        roomManager.leaveRoom(host);

        // player2 must now be host
        assertEquals(player2.getPlayerId(), room.getHostId());
        PlayerDto newHostDto = room.getPlayer(player2.getPlayerId());
        assertNotNull(newHostDto);
        assertTrue(newHostDto.isHost());
        assertEquals(1, room.getPlayerCount());
    }

    @Test
    @DisplayName("Should automatically clean up room when all players leave")
    void testRoomCleanupWhenEmpty() throws IOException {
        ClientSession host = createTestSession("SoloUser");
        GameRoom room = roomManager.createRoom(host, 3, 60);
        String code = room.getRoomCode();

        assertEquals(1, roomManager.getRoomCount());

        roomManager.leaveRoom(host);

        assertEquals(0, roomManager.getRoomCount());
        assertNull(roomManager.getRoom(code));
    }
}
