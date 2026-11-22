package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;
import com.skribbl.common.model.RoomType;

import java.util.List;

public record RoomUpdateDto(
        @JsonProperty("roomCode") String roomCode,
        @JsonProperty("roomType") RoomType roomType,
        @JsonProperty("hostId") String hostId,
        @JsonProperty("status") RoomStatus status,
        @JsonProperty("players") List<PlayerDto> players,
        @JsonProperty("maxRounds") int maxRounds,
        @JsonProperty("drawTimeSec") int drawTimeSec,
        @JsonProperty("currentRound") int currentRound,
        @JsonProperty("maxPlayers") int maxPlayers,
        @JsonProperty("startInSec") Integer startInSec
) {
    public RoomUpdateDto {
        if (roomType == null) {
            roomType = RoomType.PRIVATE;
        }
        if (maxPlayers <= 0) {
            maxPlayers = 6;
        }
    }

    /**
     * Backwards-compatible convenience constructor for 7-argument calls (defaults to PRIVATE, maxPlayers=6, startInSec=null).
     */
    public RoomUpdateDto(String roomCode, String hostId, RoomStatus status, List<PlayerDto> players,
                         int maxRounds, int drawTimeSec, int currentRound) {
        this(roomCode, RoomType.PRIVATE, hostId, status, players, maxRounds, drawTimeSec, currentRound, 6, null);
    }
}
