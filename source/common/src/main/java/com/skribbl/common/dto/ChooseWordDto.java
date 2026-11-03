package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChooseWordDto(
        @JsonProperty("choiceIndex") int choiceIndex
) {
}
