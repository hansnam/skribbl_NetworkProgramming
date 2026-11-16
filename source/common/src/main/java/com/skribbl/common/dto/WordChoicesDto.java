package com.skribbl.common.dto;

import java.util.List;

public record WordChoicesDto(String turnId, List<String> choices, int chooseSeconds) {}