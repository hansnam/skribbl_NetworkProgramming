package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skribbl.common.model.StrokeData;

public record DrawStrokeDto(
        @JsonProperty("x1") double x1,
        @JsonProperty("y1") double y1,
        @JsonProperty("x2") double x2,
        @JsonProperty("y2") double y2,
        @JsonProperty("color") String color,
        @JsonProperty("thickness") int thickness,
        @JsonProperty("eraser") boolean eraser
) {
    public StrokeData toStrokeData() {
        return new StrokeData(x1, y1, x2, y2, color, thickness, eraser);
    }

    public static DrawStrokeDto fromStrokeData(StrokeData s) {
        return new DrawStrokeDto(s.x1(), s.y1(), s.x2(), s.y2(), s.color(), s.thickness(), s.eraser());
    }
}
