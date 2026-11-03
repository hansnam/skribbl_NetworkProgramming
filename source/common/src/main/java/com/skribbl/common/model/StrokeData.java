package com.skribbl.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a single stroke segment drawn on the canvas.
 */
public record StrokeData(
        @JsonProperty("x1") double x1,
        @JsonProperty("y1") double y1,
        @JsonProperty("x2") double x2,
        @JsonProperty("y2") double y2,
        @JsonProperty("color") String color,
        @JsonProperty("thickness") int thickness,
        @JsonProperty("eraser") boolean eraser
) {
}
