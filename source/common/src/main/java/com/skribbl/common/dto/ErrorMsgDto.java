package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorMsgDto(
        @JsonProperty("code") String code,
        @JsonProperty("message") String message
) {
}
