package com.skribbl.client.controller;

import com.skribbl.common.dto.*;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;
import com.skribbl.common.protocol.JsonCodec;
import com.skribbl.common.protocol.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClientControllerTest {

    private ClientController controller;

    @BeforeEach
    void setUp() throws IOException {
        controller = new ClientController("Minh");
        String ackJson = JsonCodec.serializeMessage(MessageType.CONNECT_ACK, new ConnectAckDto("p-01", "Minh"));
        controller.handleIncomingMessage(JsonCodec.deserialize(ackJson));
    }

    @Test
    @DisplayName("Cập nhật playerId, quyền chủ phòng và quyền vẽ khi tới lượt")
    void shouldUpdatePlayerIdHostAndDrawPermissionFromMessages() throws IOException {
        assertEquals("p-01", controller.getPlayerId());

        RoomUpdateDto roomDto = new RoomUpdateDto(
                "A7K9P",
                "p-01",
                RoomStatus.WAITING,
                List.of(new PlayerDto("p-01", "Minh", 0, true, false, false)),
                3,
                60,
                0
        );
        controller.handleIncomingMessage(JsonCodec.deserialize(JsonCodec.serializeMessage(MessageType.ROOM_UPDATE, roomDto)));
        assertEquals("A7K9P", controller.getCurrentRoomCode());
        assertTrue(controller.isHost());

        TurnStartDto turnDto = new TurnStartDto(1, 3, "p-01", "Minh", "APPLE", 60);
        controller.handleIncomingMessage(JsonCodec.deserialize(JsonCodec.serializeMessage(MessageType.TURN_START, turnDto)));
        assertTrue(controller.canDraw());
    }

    @Test
    @DisplayName("Khóa quyền vẽ đối với người đoán trong lượt của người khác")
    void shouldDenyDrawPermissionForGuesserInTurn() throws IOException {
        TurnStartDto otherTurn = new TurnStartDto(1, 3, "p-02", "Nam", "_ _ _ _ _", 60);
        controller.handleIncomingMessage(JsonCodec.deserialize(JsonCodec.serializeMessage(MessageType.TURN_START, otherTurn)));

        assertFalse(controller.canDraw());
    }

    @Test
    @DisplayName("Thu hồi quyền vẽ khi hết lượt (TURN_END) và kết thúc ván (GAME_OVER)")
    void shouldRevokeDrawPermissionOnTurnEndAndGameOver() throws IOException {
        TurnStartDto turnDto = new TurnStartDto(1, 3, "p-01", "Minh", "BANANA", 60);
        controller.handleIncomingMessage(JsonCodec.deserialize(JsonCodec.serializeMessage(MessageType.TURN_START, turnDto)));
        assertTrue(controller.canDraw());

        TurnEndDto endDto = new TurnEndDto("BANANA", "TIME_UP", Map.of(), List.of());
        controller.handleIncomingMessage(JsonCodec.deserialize(JsonCodec.serializeMessage(MessageType.TURN_END, endDto)));
        assertFalse(controller.canDraw());

        controller.handleIncomingMessage(JsonCodec.deserialize(JsonCodec.serializeMessage(MessageType.TURN_START, turnDto)));
        assertTrue(controller.canDraw());

        GameOverDto overDto = new GameOverDto(List.of(), List.of());
        controller.handleIncomingMessage(JsonCodec.deserialize(JsonCodec.serializeMessage(MessageType.GAME_OVER, overDto)));
        assertFalse(controller.canDraw());
    }

    @Test
    @DisplayName("Đặt lại toàn bộ trạng thái phòng khi rời phòng (leaveRoom)")
    void shouldResetRoomStateWhenLeavingRoom() throws IOException {
        RoomUpdateDto roomDto = new RoomUpdateDto(
                "B8M2Q",
                "p-01",
                RoomStatus.WAITING,
                List.of(new PlayerDto("p-01", "Minh", 0, true, false, false)),
                3,
                60,
                0
        );
        controller.handleIncomingMessage(JsonCodec.deserialize(JsonCodec.serializeMessage(MessageType.ROOM_UPDATE, roomDto)));
        assertEquals("B8M2Q", controller.getCurrentRoomCode());
        assertTrue(controller.isHost());

        controller.leaveRoom();

        assertNull(controller.getCurrentRoomCode());
        assertFalse(controller.isHost());
        assertFalse(controller.canDraw());
    }
}