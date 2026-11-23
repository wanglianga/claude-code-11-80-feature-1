package com.flood.statemigrate.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * 权重集登记请求。weights 形如 [{"stationCode":"ST-A","subbasinCode":"SB-1","weight":0.7}]；
 * areas 为子流域面积 km²，如 {"SB-1":5.2}。每个子流域站点权重之和须为 1。
 */
public record WeightSetReq(
        @NotEmpty List<Map<String, Object>> weights,
        @NotEmpty Map<String, Double> areas
) {
}
