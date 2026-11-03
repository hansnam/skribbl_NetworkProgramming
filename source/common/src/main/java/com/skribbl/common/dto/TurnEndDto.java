package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skribbl.common.model.PlayerDto;

import java.util.List;
import java.util.Map;

public record TurnEndDto(
        @JsonProperty("word") String word,
        @JsonProperty("reason") String reason,
        @JsonProperty("turnScores") Map<String, Integer> turnScores,
        @JsonProperty("players") List<PlayerDto> players
) {
}

