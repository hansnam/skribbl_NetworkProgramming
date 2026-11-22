package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload for flood-filling an enclosed canvas area at logical coordinates (x, y).
 */
public record FillCanvasDto(
        @JsonProperty("x") double x,
        @JsonProperty("y") double y,
        @JsonProperty("color") String color,
        @JsonProperty("strokeGroupId") String strokeGroupId
) {
    public FillCanvasDto(double x, double y, String color) {
        this(x, y, color, "");
    }
}
