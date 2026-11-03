package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skribbl.common.model.PlayerDto;

import java.util.List;

public record PlayerGuessedDto(
        @JsonProperty("playerId") String playerId,
        @JsonProperty("nickname") String nickname,
        @JsonProperty("awardedPoints") int awardedPoints,
        @JsonProperty("drawerBonus") int drawerBonus,
        @JsonProperty("players") List<PlayerDto> players
) {
}

