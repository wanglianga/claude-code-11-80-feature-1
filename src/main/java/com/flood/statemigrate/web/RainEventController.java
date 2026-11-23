package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.RainEvent;
import com.flood.statemigrate.domain.RainScheme;
import com.flood.statemigrate.service.RainEventEvaluator;
import com.flood.statemigrate.service.RainQueryService;
import com.flood.statemigrate.web.dto.ReconstructReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "降雨事件重建")
@RestController
@RequestMapping("/api/rain/events")
public class RainEventController {

    private final RainEventEvaluator evaluator;
    private final RainQueryService queryService;

    public RainEventController(RainEventEvaluator evaluator, RainQueryService queryService) {
        this.evaluator = evaluator;
        this.queryService = queryService;
    }

    @Operation(summary = "重建降雨事件：校时对齐采样，按方案输出开始/结束、累计上下界、空间覆盖；"
            + "缺站导致边界不可判时保留候选区间。scheme 为空时两方案都重建。")
    @PostMapping("/reconstruct")
    public Map<String, Object> reconstruct(@Valid @RequestBody ReconstructReq req) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (RainScheme scheme : req.scheme() != null ? List.of(req.scheme()) : List.of(RainScheme.values())) {
            RainEventEvaluator.Params p = new RainEventEvaluator.Params(
                    req.from(), req.to(), scheme,
                    orDefault(req.gapMinutes(), RainEventEvaluator.DEFAULT_GAP_MINUTES),
                    orDefault(req.responseMinutes(), RainEventEvaluator.DEFAULT_RESPONSE_MINUTES),
                    orDefault(req.wetnessThresholdMm(), RainEventEvaluator.DEFAULT_WETNESS_THRESHOLD_MM),
                    orDefault(req.riskMediumMm(), RainEventEvaluator.DEFAULT_RISK_MEDIUM_MM),
                    orDefault(req.riskHighMm(), RainEventEvaluator.DEFAULT_RISK_HIGH_MM),
                    orDefault(req.missingCeilingMm(), RainEventEvaluator.DEFAULT_MISSING_CEILING_MM));
            result.put(scheme.name(), evaluator.reconstruct(p));
        }
        return result;
    }

    @Operation(summary = "事件列表（默认只含未取代版本）")
    @GetMapping
    public List<RainEvent> list(@RequestParam(required = false) RainScheme scheme,
                                @RequestParam(required = false)
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                @RequestParam(required = false)
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return queryService.listEvents(scheme, from, to);
    }

    @Operation(summary = "事件详情（含分站累计界限、风险窗口、子流域降雨过程）")
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return queryService.eventDetail(id);
    }

    @Operation(summary = "两方案对比：候选集、风险窗口与风险归因差异")
    @GetMapping("/compare")
    public Map<String, Object> compare(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return queryService.compare(from, to);
    }

    private static int orDefault(Integer v, int d) { return v != null ? v : d; }
    private static double orDefault(Double v, double d) { return v != null ? v : d; }
}
