package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ConnectAckDto(
        @JsonProperty("playerId") String playerId,
        @JsonProperty("nickname") String nickname
) {
}

