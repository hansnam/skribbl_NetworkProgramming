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
        @JsonProperty("hasGuessed") boolean hasGuessed,
        @JsonProperty("avatar") AvatarDto avatar
) {
    public PlayerDto {
        if (avatar == null) {
            avatar = AvatarDto.DEFAULT;
        }
    }

    public PlayerDto(String id, String nickname, int score, boolean isHost, boolean isDrawer, boolean hasGuessed) {
        this(id, nickname, score, isHost, isDrawer, hasGuessed, AvatarDto.DEFAULT);
    }

    public PlayerDto withScore(int newScore) {
        return new PlayerDto(id, nickname, newScore, isHost, isDrawer, hasGuessed, avatar);
    }

    public PlayerDto withHost(boolean newIsHost) {
        return new PlayerDto(id, nickname, score, newIsHost, isDrawer, hasGuessed, avatar);
    }

    public PlayerDto withDrawer(boolean newIsDrawer) {
        return new PlayerDto(id, nickname, score, isHost, newIsDrawer, hasGuessed, avatar);
    }

    public PlayerDto withGuessed(boolean newHasGuessed) {
        return new PlayerDto(id, nickname, score, isHost, isDrawer, newHasGuessed, avatar);
    }

    public PlayerDto withAvatar(AvatarDto newAvatar) {
        return new PlayerDto(id, nickname, score, isHost, isDrawer, hasGuessed, newAvatar);
    }
}
