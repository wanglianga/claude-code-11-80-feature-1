package com.flood.statemigrate.web.dto;

import com.flood.statemigrate.domain.RainChangeKind;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 事件版本账变更请求。
 * STATION_ADDED：stationCode 必填；站点未登记时需 stationName/newClockOffsetSec/newEquipmentId；
 *                weights 必填（含新站的完整新权重列表），areas 缺省沿用当前权重集。
 * EQUIPMENT_REPLACED：stationCode/newEquipmentId/newClockOffsetSec 必填；
 *                effectiveAt 起观测的采样按新校时误差重对齐。
 * WEIGHT_CHANGED：weights 必填，areas 缺省沿用当前权重集。
 * reprocessFrom/reprocessTo 为变更触发的重算窗口。
 */
public record LedgerChangeReq(
        @NotNull RainChangeKind changeType,
        @NotNull Instant effectiveAt,
        @NotNull Instant reprocessFrom,
        @NotNull Instant reprocessTo,
        String stationCode,
        String stationName,
        Long newClockOffsetSec,
        String newEquipmentId,
        List<Map<String, Object>> weights,
        Map<String, Double> areas,
        String note
) {
}
