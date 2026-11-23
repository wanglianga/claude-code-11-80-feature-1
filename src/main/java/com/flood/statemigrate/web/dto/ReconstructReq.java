package com.flood.statemigrate.web.dto;

import com.flood.statemigrate.domain.RainScheme;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 降雨事件重建请求。scheme 为空时两方案都重建。
 * 其余参数缺省用服务默认（gapMinutes=30、responseMinutes=45、wetnessThresholdMm=12、
 * riskMediumMm=15、riskHighMm=25、missingCeilingMm=10）。
 */
public record ReconstructReq(
        @NotNull Instant from,
        @NotNull Instant to,
        RainScheme scheme,
        Integer gapMinutes,
        Integer responseMinutes,
        Double wetnessThresholdMm,
        Double riskMediumMm,
        Double riskHighMm,
        Double missingCeilingMm
) {
}
