package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatMessageDto(
        @JsonProperty("sender") String sender,
        @JsonProperty("text") String text,
        @JsonProperty("kind") ChatKind kind
) {
}

