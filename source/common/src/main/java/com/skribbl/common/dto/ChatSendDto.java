package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatSendDto(
        @JsonProperty("text") String text
) {
}

