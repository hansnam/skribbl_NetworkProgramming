package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateRoomReqDto(
        @JsonProperty("maxRounds") int maxRounds,
        @JsonProperty("drawTimeSec") int drawTimeSec
) {
    public CreateRoomReqDto {
        if (maxRounds <= 0) maxRounds = 3;
        if (drawTimeSec <= 0) drawTimeSec = 60;
    }
}
