package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChooseWordDto(
        @JsonProperty("turnId") String turnId,
        @JsonProperty("choiceIndex") int choiceIndex
) {
}