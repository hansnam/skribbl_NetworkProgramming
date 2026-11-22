package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skribbl.common.model.AvatarDto;

public record ConnectReqDto(
        @JsonProperty("nickname") String nickname,
        @JsonProperty("avatar") AvatarDto avatar
) {
    public ConnectReqDto {
        if (avatar == null) {
            avatar = AvatarDto.DEFAULT;
        }
    }

    public ConnectReqDto(String nickname) {
        this(nickname, AvatarDto.DEFAULT);
    }
}
