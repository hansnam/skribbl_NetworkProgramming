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
        @JsonProperty("eraser") boolean eraser,
        @JsonProperty("strokeGroupId") String strokeGroupId
) {
    public DrawStrokeDto {
        if (strokeGroupId == null) {
            strokeGroupId = "";
        }
    }

    public DrawStrokeDto(double x1, double y1, double x2, double y2, String color, int thickness, boolean eraser) {
        this(x1, y1, x2, y2, color, thickness, eraser, "");
    }

    public StrokeData toStrokeData() {
        return new StrokeData(x1, y1, x2, y2, color, thickness, eraser, strokeGroupId, false);
    }

    public static DrawStrokeDto fromStrokeData(StrokeData s) {
        return new DrawStrokeDto(s.x1(), s.y1(), s.x2(), s.y2(), s.color(), s.thickness(), s.eraser(), s.strokeGroupId());
    }
}
