package com.skribbl.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skribbl.common.model.StrokeData;

import java.util.List;

public record CanvasSyncDto(
        @JsonProperty("strokes") List<StrokeData> strokes
) {
    public CanvasSyncDto {
        if (strokes == null) {
            strokes = List.of();
        }
    }
}
