package com.skribbl.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Immutable data transfer object representing a player's state in a room.
 */
public record PlayerDto(
        @JsonProperty("id") String id,
        @JsonProperty("nickname") String nickname,
        @JsonProperty("score") int score,
        @JsonProperty("isHost") boolean isHost,
        @JsonProperty("isDrawer") boolean isDrawer,
        @JsonProperty("hasGuessed") boolean hasGuessed
) {
    public PlayerDto withScore(int newScore) {
        return new PlayerDto(id, nickname, newScore, isHost, isDrawer, hasGuessed);
    }

    public PlayerDto withHost(boolean newIsHost) {
        return new PlayerDto(id, nickname, score, newIsHost, isDrawer, hasGuessed);
    }

    public PlayerDto withDrawer(boolean newIsDrawer) {
        return new PlayerDto(id, nickname, score, isHost, newIsDrawer, hasGuessed);
    }

    public PlayerDto withGuessed(boolean newHasGuessed) {
        return new PlayerDto(id, nickname, score, isHost, isDrawer, newHasGuessed);
    }
}

