package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JoinRoomReqDto(
        @JsonProperty("roomCode") String roomCode
) {
}

