package com.flood.statemigrate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 雨量站登记请求。clockOffsetSec 为站钟比标准时快的秒数（可为负）。 */
public record RainStationReq(
        @NotBlank String stationCode,
        @NotBlank String name,
        @NotNull Long clockOffsetSec,
        @NotBlank String equipmentId
) {
}
