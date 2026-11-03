package com.skribbl.common.protocol;

import com.skribbl.common.dto.ConnectReqDto;
import com.skribbl.common.dto.DrawStrokeDto;
import com.skribbl.common.dto.RoomUpdateDto;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonCodecTest {

    @Test
    @DisplayName("Should serialize NetworkMessage to single-line JSON without line breaks")
    void testSerializationSingleLine() throws IOException {
        ConnectReqDto req = new ConnectReqDto("PlayerOne");
        String json = JsonCodec.serializeMessage(MessageType.CONNECT_REQ, req);

        assertNotNull(json);
        assertFalse(json.contains("\n"), "Serialized JSON must not contain newlines");
        assertFalse(json.contains("\r"), "Serialized JSON must not contain carriage returns");
        assertTrue(json.contains("CONNECT_REQ"));
        assertTrue(json.contains("PlayerOne"));
    }

    @Test
    @DisplayName("Should deserialize JSON line and decode payload cleanly")
    void testDeserializationAndPayloadDecoding() throws IOException {
        DrawStrokeDto stroke = new DrawStrokeDto(10.5, 20.0, 35.2, 40.8, "#FF0000", 4, false);
        String json = JsonCodec.serializeMessage(MessageType.DRAW_STROKE, stroke);

        NetworkMessage msg = JsonCodec.deserialize(json);
        assertEquals(MessageType.DRAW_STROKE, msg.type());
        assertTrue(msg.timestamp() > 0);

        DrawStrokeDto decoded = JsonCodec.decodePayload(msg, DrawStrokeDto.class);
        assertNotNull(decoded);
        assertEquals(10.5, decoded.x1(), 0.001);
        assertEquals("#FF0000", decoded.color());
        assertEquals(4, decoded.thickness());
        assertFalse(decoded.eraser());
    }

    @Test
    @DisplayName("Should serialize and deserialize complex RoomUpdateDto with nested player list")
    void testComplexPayload() throws IOException {
        PlayerDto p1 = new PlayerDto("p1", "Alice", 150, true, false, false);
        PlayerDto p2 = new PlayerDto("p2", "Bob", 200, false, true, true);
        RoomUpdateDto update = new RoomUpdateDto("ROOM1", "p1", RoomStatus.DRAWING, List.of(p1, p2), 3, 80, 1);

        String json = JsonCodec.serializeMessage(MessageType.ROOM_UPDATE, update);
        NetworkMessage msg = JsonCodec.deserialize(json);

        assertEquals(MessageType.ROOM_UPDATE, msg.type());
        RoomUpdateDto decoded = JsonCodec.decodePayload(msg, RoomUpdateDto.class);
        assertNotNull(decoded);
        assertEquals("ROOM1", decoded.roomCode());
        assertEquals("p1", decoded.hostId());
        assertEquals(RoomStatus.DRAWING, decoded.status());
        assertEquals(2, decoded.players().size());
        assertEquals("Alice", decoded.players().get(0).nickname());
        assertTrue(decoded.players().get(0).isHost());
        assertTrue(decoded.players().get(1).isDrawer());
    }

    @Test
    @DisplayName("Should handle null payload gracefully")
    void testNullPayload() throws IOException {
        NetworkMessage msg = NetworkMessage.of(MessageType.LEAVE_ROOM);
        String json = JsonCodec.serialize(msg);

        NetworkMessage deserialized = JsonCodec.deserialize(json);
        assertEquals(MessageType.LEAVE_ROOM, deserialized.type());
        assertTrue(deserialized.payload().isNull());
    }

    @Test
    @DisplayName("Should throw exception when parsing malformed JSON")
    void testMalformedJson() {
        assertThrows(IOException.class, () -> JsonCodec.deserialize("{not valid json}"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.deserialize(""));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.deserialize(null));
    }
}

