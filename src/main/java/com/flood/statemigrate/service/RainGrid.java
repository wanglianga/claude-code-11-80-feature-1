package com.flood.statemigrate.service;

import com.flood.statemigrate.domain.RainSample;
import com.flood.statemigrate.domain.SampleQuality;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 事件求值器的对齐栅格：把多站采样按校时后的标准 10 分钟时段对齐。
 * 每个时段记录各站实测值（measured）与缺测站（missing）；
 * 时段状态：WET 有实测降雨；DRY 全部加权站实测无雨；UNKNOWN 无实测降雨但有站缺测（可能其实在下雨）。
 */
public class RainGrid {

    public enum BucketState { WET, DRY, UNKNOWN }

    public record Cell(Instant bucketStart,
                       Map<String, Double> measured,
                       Set<String> missing) {

        BucketState state() {
            boolean anyWet = measured.values().stream().anyMatch(v -> v > 0.0);
            if (anyWet) return BucketState.WET;
            return missing.isEmpty() ? BucketState.DRY : BucketState.UNKNOWN;
        }
    }

    private final List<String> stationCodes;
    private final List<Cell> cells;

    private RainGrid(List<String> stationCodes, List<Cell> cells) {
        this.stationCodes = stationCodes;
        this.cells = cells;
    }

    public List<String> stationCodes() { return stationCodes; }
    public List<Cell> cells() { return cells; }

    /** 由采样构建 [from, to) 的 10 分钟栅格。samplesByStation: stationCode -> 该站在窗口内的采样。 */
    public static RainGrid build(Instant from, Instant to, List<String> stations,
                                 Map<String, List<RainSample>> samplesByStation) {
        List<String> codes = stations.stream().sorted().toList();
        Map<Instant, Map<String, RainSample>> index = new LinkedHashMap<>();
        for (Map.Entry<String, List<RainSample>> e : samplesByStation.entrySet()) {
            for (RainSample s : e.getValue()) {
                index.computeIfAbsent(s.getBucketStart(), k -> new LinkedHashMap<>()).put(e.getKey(), s);
            }
        }
        List<Cell> cells = new ArrayList<>();
        for (Instant b = from; b.isBefore(to); b = b.plusSeconds(600)) {
            Map<String, RainSample> at = index.getOrDefault(b, Map.of());
            Map<String, Double> measured = new LinkedHashMap<>();
            Set<String> missing = new TreeSet<>();
            for (String code : codes) {
                RainSample s = at.get(code);
                if (s != null && s.getQuality() == SampleQuality.MEASURED && s.getMm() != null) {
                    measured.put(code, s.getMm());
                } else {
                    missing.add(code);
                }
            }
            cells.add(new Cell(b, measured, missing));
        }
        return new RainGrid(codes, cells);
    }
}
