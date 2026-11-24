package com.skribbl.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a player's customizable cartoon avatar (color, eyes, mouth).
 */
public record AvatarDto(
        @JsonProperty("colorIndex") int colorIndex,
        @JsonProperty("eyesIndex") int eyesIndex,
        @JsonProperty("mouthIndex") int mouthIndex,
        @JsonProperty("imageId") String imageId
) {
    public static final int COLOR_COUNT = 10;
    public static final int EYES_COUNT = 8;
    public static final int MOUTH_COUNT = 8;
    public static final AvatarDto DEFAULT = new AvatarDto(0, 0, 0);

    public AvatarDto(int colorIndex, int eyesIndex, int mouthIndex) {
        this(colorIndex, eyesIndex, mouthIndex, null);
    }

    public AvatarDto {
        colorIndex = Math.floorMod(colorIndex, COLOR_COUNT);
        eyesIndex = Math.floorMod(eyesIndex, EYES_COUNT);
        mouthIndex = Math.floorMod(mouthIndex, MOUTH_COUNT);

        if (imageId == null || !imageId.matches("robot-[1-6]")) {
            imageId = null;
        }
    }
}
