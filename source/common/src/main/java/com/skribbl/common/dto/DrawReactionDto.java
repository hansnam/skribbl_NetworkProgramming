package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload for liking or disliking the current drawing during a turn.
 */
public record DrawReactionDto(
        @JsonProperty("sender") String sender,
        @JsonProperty("like") boolean like
) {
}
