package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.SubbasinSeries;
import com.flood.statemigrate.domain.WeightSet;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.WeightSetRepo;
import com.flood.statemigrate.service.RainQueryService;
import com.flood.statemigrate.service.WatershedMapper;
import com.flood.statemigrate.web.dto.WeightSetReq;
import tools.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@Tag(name = "流域映射与权重集")
@RestController
@RequestMapping("/api/rain")
public class RainBasinController {

    private final WeightSetRepo weightSetRepo;
    private final WatershedMapper mapper;
    private final RainQueryService queryService;
    private final ObjectMapper om;

    public RainBasinController(WeightSetRepo weightSetRepo, WatershedMapper mapper,
                               RainQueryService queryService, ObjectMapper om) {
        this.weightSetRepo = weightSetRepo;
        this.mapper = mapper;
        this.queryService = queryService;
        this.om = om;
    }

    @Operation(summary = "登记首个权重集（后续变动走事件版本账 WEIGHT_CHANGED）")
    @PostMapping("/weight-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public WeightSet register(@Valid @RequestBody WeightSetReq req) {
        if (weightSetRepo.findByStatus("ACTIVE").isPresent()) {
            throw ApiException.conflict("已存在 ACTIVE 权重集，权重变动请使用事件版本账 WEIGHT_CHANGED");
        }
        WeightSet ws = new WeightSet();
        ws.setVersion("WS-1");
        ws.setWeightsJson(writeJson(req.weights()));
        ws.setAreasJson(writeJson(req.areas()));
        ws.setStatus("ACTIVE");
        mapper.parse(ws); // 校验：每个子流域权重和为 1、面积为正
        return weightSetRepo.save(ws);
    }

    @Operation(summary = "权重集列表（含历史版本）")
    @GetMapping("/weight-sets")
    public List<WeightSet> weightSets() {
        return weightSetRepo.findAll();
    }

    @Operation(summary = "子流域降雨过程：窗口内有效事件的 10 分钟分区雨量上下界与实测覆盖率")
    @GetMapping("/subbasins/{code}/series")
    public List<SubbasinSeries> series(
            @PathVariable String code,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) com.flood.statemigrate.domain.RainScheme scheme) {
        return queryService.subbasinSeries(code, from, to, scheme);
    }

    private String writeJson(Object v) {
        try {
            return om.writeValueAsString(v);
        } catch (Exception e) {
            throw ApiException.badRequest("JSON 序列化失败: " + e.getMessage());
        }
    }
}
