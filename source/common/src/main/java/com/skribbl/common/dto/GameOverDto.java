package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skribbl.common.model.PlayerDto;

import java.util.List;

public record GameOverDto(
        @JsonProperty("winners") List<PlayerDto> winners,
        @JsonProperty("players") List<PlayerDto> players
) {
}

