package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TimerUpdateDto(
        @JsonProperty("remainingSec") int remainingSec,
        @JsonProperty("wordMask") String wordMask
) {
}
