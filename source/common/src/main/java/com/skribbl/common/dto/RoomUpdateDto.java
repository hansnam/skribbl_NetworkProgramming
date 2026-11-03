package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skribbl.common.model.PlayerDto;
import com.skribbl.common.model.RoomStatus;

import java.util.List;

public record RoomUpdateDto(
        @JsonProperty("roomCode") String roomCode,
        @JsonProperty("hostId") String hostId,
        @JsonProperty("status") RoomStatus status,
        @JsonProperty("players") List<PlayerDto> players,
        @JsonProperty("maxRounds") int maxRounds,
        @JsonProperty("drawTimeSec") int drawTimeSec,
        @JsonProperty("currentRound") int currentRound
) {
}
