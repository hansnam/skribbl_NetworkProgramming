package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ConnectReqDto(
        @JsonProperty("nickname") String nickname
) {
}

