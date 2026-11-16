package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TurnStartDto(
        @JsonProperty("round") int round,
        @JsonProperty("maxRounds") int maxRounds,
        @JsonProperty("drawerId") String drawerId,
        @JsonProperty("drawerNickname") String drawerNickname,
        @JsonProperty("wordDisplay") String wordDisplay,
        @JsonProperty("drawTimeSec") int drawTimeSec
) {
}

