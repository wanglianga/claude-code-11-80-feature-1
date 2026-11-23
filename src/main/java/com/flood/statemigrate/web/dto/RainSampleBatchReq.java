package com.flood.statemigrate.web.dto;

import com.flood.statemigrate.domain.SampleQuality;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/** 站点采样批量写入请求。MEASURED 必须带非负 mm；MISSING 不带 mm（缺测段显式登记）。 */
public record RainSampleBatchReq(
        @NotEmpty @Valid List<SampleItem> samples
) {
    public record SampleItem(
            @NotNull Instant observedAt,
            Double mm,
            @NotNull SampleQuality quality
    ) {
    }
}
