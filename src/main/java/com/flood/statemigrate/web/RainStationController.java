package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.RainSample;
import com.flood.statemigrate.domain.RainStation;
import com.flood.statemigrate.service.RainStationService;
import com.flood.statemigrate.web.dto.RainSampleBatchReq;
import com.flood.statemigrate.web.dto.RainStationReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@Tag(name = "雨量站点与采样")
@RestController
@RequestMapping("/api/rain/stations")
public class RainStationController {

    private final RainStationService stationService;

    public RainStationController(RainStationService stationService) {
        this.stationService = stationService;
    }

    @Operation(summary = "登记雨量站（站码/校时误差/设备编号）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RainStation register(@Valid @RequestBody RainStationReq req) {
        RainStation s = new RainStation();
        s.setStationCode(req.stationCode());
        s.setName(req.name());
        s.setClockOffsetSec(req.clockOffsetSec());
        s.setEquipmentId(req.equipmentId());
        s.setActive(true);
        return stationService.register(s);
    }

    @Operation(summary = "雨量站列表")
    @GetMapping
    public List<RainStation> list() {
        return stationService.list();
    }

    @Operation(summary = "雨量站详情")
    @GetMapping("/{id}")
    public RainStation get(@PathVariable Long id) {
        return stationService.get(id);
    }

    @Operation(summary = "批量写入 10 分钟雨量采样（按校时误差对齐到标准时段；同站同时段重复写入视为订正）")
    @PostMapping("/{id}/samples")
    @ResponseStatus(HttpStatus.CREATED)
    public List<RainSample> addSamples(@PathVariable Long id,
                                       @Valid @RequestBody RainSampleBatchReq req) {
        RainStation station = stationService.get(id);
        return req.samples().stream()
                .map(item -> stationService.addSample(station, item.observedAt(), item.mm(), item.quality()))
                .toList();
    }

    @Operation(summary = "查询站点采样（校时对齐视图：observedAt/correctedAt/bucketStart）")
    @GetMapping("/{id}/samples")
    public List<RainSample> samples(@PathVariable Long id,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        stationService.get(id);
        return stationService.samples(id, from, to);
    }
}
