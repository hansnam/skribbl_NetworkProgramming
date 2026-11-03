package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record WordChoicesDto(
        @JsonProperty("choices") List<String> choices,
        @JsonProperty("chooseSeconds") int chooseSeconds
) {
}
