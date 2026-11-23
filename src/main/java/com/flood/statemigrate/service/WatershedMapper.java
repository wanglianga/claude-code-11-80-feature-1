package com.flood.statemigrate.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.WeightSet;
import com.flood.statemigrate.error.ApiException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 流域映射器：依据站点与子流域权重生成子流域降雨过程，并折算流域面雨量权重。
 * 权重集：stationCode × subbasinCode → weight（每个子流域权重和须为 1）；
 * 流域面雨量权重 = Σ_子流域 (面积占比 × 站点对该子流域权重)。
 */
@Component
public class WatershedMapper {

    private final ObjectMapper om;

    public WatershedMapper(ObjectMapper om) {
        this.om = om;
    }

    /** 解析后的权重集。 */
    public record Weights(Map<String, Map<String, Double>> bySubbasin,   // subbasin -> station -> weight
                          Map<String, Double> areasKm2,
                          List<String> stationCodes) {

        /** 站点 → 子流域权重；无映射返回 0。 */
        public double weight(String subbasin, String station) {
            return bySubbasin.getOrDefault(subbasin, Map.of()).getOrDefault(station, 0.0);
        }

        /** 站点 → 流域面雨量权重（按子流域面积加权）。 */
        public Map<String, Double> basinWeights() {
            double totalArea = areasKm2.values().stream().mapToDouble(Double::doubleValue).sum();
            Map<String, Double> basin = new LinkedHashMap<>();
            for (String st : stationCodes) {
                double w = 0.0;
                for (Map.Entry<String, Double> area : areasKm2.entrySet()) {
                    w += area.getValue() / totalArea * weight(area.getKey(), st);
                }
                basin.put(st, w);
            }
            return basin;
        }
    }

    public Weights parse(WeightSet set) {
        try {
            List<Map<String, Object>> raw = om.readValue(set.getWeightsJson(), new TypeReference<>() {});
            Map<String, Map<String, Double>> bySubbasin = new LinkedHashMap<>();
            for (Map<String, Object> m : raw) {
                String st = String.valueOf(m.get("stationCode"));
                String sb = String.valueOf(m.get("subbasinCode"));
                double w = ((Number) m.get("weight")).doubleValue();
                if (w < 0 || w > 1) {
                    throw ApiException.badRequest("权重须在 [0,1]: " + st + "→" + sb + "=" + w);
                }
                bySubbasin.computeIfAbsent(sb, k -> new LinkedHashMap<>()).put(st, w);
            }
            Map<String, Object> areaRaw = om.readValue(set.getAreasJson(), new TypeReference<>() {});
            Map<String, Double> areas = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : areaRaw.entrySet()) {
                double a = ((Number) e.getValue()).doubleValue();
                if (a <= 0) throw ApiException.badRequest("子流域面积须为正: " + e.getKey());
                areas.put(e.getKey(), a);
            }
            for (String sb : bySubbasin.keySet()) {
                if (!areas.containsKey(sb)) {
                    throw ApiException.badRequest("权重集子流域 " + sb + " 缺少面积配置");
                }
                double sum = bySubbasin.get(sb).values().stream().mapToDouble(Double::doubleValue).sum();
                if (Math.abs(sum - 1.0) > 1e-6) {
                    throw ApiException.badRequest("子流域 " + sb + " 站点权重之和须为 1，实际 " + sum);
                }
            }
            List<String> stations = new ArrayList<>(new TreeSet<>(
                    bySubbasin.values().stream().flatMap(m -> m.keySet().stream()).toList()));
            return new Weights(bySubbasin, areas, stations);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("权重集解析失败: " + e.getMessage());
        }
    }
}
