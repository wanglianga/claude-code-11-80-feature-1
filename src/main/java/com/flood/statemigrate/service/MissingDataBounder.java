package com.flood.statemigrate.service;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缺测界限器：对含缺测时段的累计雨量给出上下界，不用插值点伪装实测。
 * 下界 = 实测累计（缺测按 0）；上界 = 实测累计 + 缺测时段数 × 单时段上界估计。
 * 单时段上界估计优先级：该站事件内最大实测时段雨量 → 该站窗口内最大实测 → 同时段跨站最大实测 → 配置上限。
 */
@Component
public class MissingDataBounder {

    /** 单站事件累计界限。 */
    public record StationBounds(String stationCode, double measuredMm, int missingBuckets,
                                double lowerMm, double upperMm) {}

    /**
     * @param stationCode   站码
     * @param cells         事件跨度内的栅格时段
     * @param windowMaxMm   该站在整个重建窗口内的最大实测时段雨量（无实测时为 null）
     * @param ceilingMm     单时段雨量上限（兜底）
     */
    public StationBounds bounds(String stationCode, List<RainGrid.Cell> cells,
                                Double windowMaxMm, double ceilingMm) {
        double measured = 0.0;
        int missing = 0;
        Double eventMax = null;
        for (RainGrid.Cell c : cells) {
            Double v = c.measured().get(stationCode);
            if (v != null) {
                measured += v;
                eventMax = eventMax == null ? v : Math.max(eventMax, v);
            } else {
                missing++;
            }
        }
        double upper = measured;
        for (RainGrid.Cell c : cells) {
            if (c.measured().get(stationCode) == null) {
                upper += estimateUpper(c, eventMax, windowMaxMm, ceilingMm);
            }
        }
        return new StationBounds(stationCode, round3(measured), missing, round3(measured), round3(upper));
    }

    /** 单缺测时段的上界估计：事件内最大实测 → 窗口最大实测 → 同时段跨站最大实测 → 配置上限。 */
    public double estimateUpper(RainGrid.Cell cell, Double eventMaxMm, Double windowMaxMm, double ceilingMm) {
        if (eventMaxMm != null) return eventMaxMm;
        if (windowMaxMm != null) return windowMaxMm;
        return cell.measured().values().stream().mapToDouble(Double::doubleValue).max().orElse(ceilingMm);
    }

    /** 各站在给定时段内的最大实测时段雨量（无实测的站不出现在结果中）。 */
    public Map<String, Double> maxMeasured(List<RainGrid.Cell> cells, List<String> stationCodes) {
        Map<String, Double> max = new HashMap<>();
        for (String st : stationCodes) {
            double m = -1;
            for (RainGrid.Cell c : cells) {
                Double v = c.measured().get(st);
                if (v != null) m = Math.max(m, v);
            }
            if (m >= 0) max.put(st, m);
        }
        return max;
    }

    public static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** 分站界限转 JSON 友好的 Map 列表。 */
    public static List<Map<String, Object>> toJsonList(List<StationBounds> bounds) {
        return bounds.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("stationCode", b.stationCode());
            m.put("measuredMm", b.measuredMm());
            m.put("missingBuckets", b.missingBuckets());
            m.put("lowerMm", b.lowerMm());
            m.put("upperMm", b.upperMm());
            return m;
        }).toList();
    }
}
