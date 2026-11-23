package com.flood.statemigrate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.service.RainEventEvaluator;
import com.flood.statemigrate.service.RainQueryService;
import com.flood.statemigrate.service.RainStationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 降雨事件重建：校时对齐、两方案事件边界、缺测上下界（不插值）、
 * 跨日判定、候选区间保留、流域映射分区降雨过程。
 */
class RainfallReconstructionTest extends BaseIntegrationTest {

    @Autowired
    private RainEventEvaluator evaluator;
    @Autowired
    private RainStationService stationService;
    @Autowired
    private RainQueryService queryService;
    @Autowired
    private ObjectMapper om;

    private RainScenario rain;

    @BeforeEach
    void seedRain() {
        rain = new RainScenario(rainStationRepo, weightSetRepo, stationService);
        rain.seedNight();
    }

    @Test
    void clockAlignmentCorrectsStationClockOffset() {
        Instant bucket = Instant.parse("2026-08-14T23:00:00Z");
        RainSample a = rainSampleRepo.findByStationIdAndBucketStart(rain.stationA.getId(), bucket).orElseThrow();
        // ST-A 站钟快 30s：观测 23:00:30 → 校正 23:00:00
        assertThat(a.getObservedAt()).isEqualTo(Instant.parse("2026-08-14T23:00:30Z"));
        assertThat(a.getCorrectedAt()).isEqualTo(bucket);
        assertThat(a.getClockOffsetSecApplied()).isEqualTo(30);

        RainSample b = rainSampleRepo.findByStationIdAndBucketStart(rain.stationB.getId(), bucket).orElseThrow();
        // ST-B 站钟慢 40s：观测 22:59:20 → 校正 23:00:00
        assertThat(b.getObservedAt()).isEqualTo(Instant.parse("2026-08-14T22:59:20Z"));
        assertThat(b.getCorrectedAt()).isEqualTo(bucket);
        assertThat(b.getClockOffsetSecApplied()).isEqualTo(-40);
    }

    @Test
    void fixedGapKeepsCandidateIntervalsWhenMissingHidesBoundary() {
        Map<String, Object> result = evaluator.reconstruct(RainEventEvaluator.defaultParams(
                RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO, RainScheme.FIXED_GAP));
        // B 站缺测掩盖 30 分钟无雨间隔 → 边界不可判，保留候选区间，不强行定场
        assertThat(result.get("status")).isEqualTo(RainEventStatus.CANDIDATE);

        List<RainEvent> events = rainEventRepo.findBySchemeAndStatusNotOrderByStartAtAsc(
                RainScheme.FIXED_GAP, RainEventStatus.SUPERSEDED);
        assertThat(events).hasSize(3);
        List<RainEvent> set1 = events.stream().filter(e -> e.getCandidateSetNo() == 1).toList();
        List<RainEvent> set2 = events.stream().filter(e -> e.getCandidateSetNo() == 2).toList();

        // 候选集 1：假设缺测段无雨 → 达到 30 分钟拆场阈值，分为两场，均不跨日
        assertThat(set1).hasSize(2);
        RainEvent e1 = set1.get(0);
        assertThat(e1.getStartAt()).isEqualTo(Instant.parse("2026-08-14T23:00:00Z"));
        assertThat(e1.getEndAt()).isEqualTo(Instant.parse("2026-08-14T23:50:00Z"));
        assertThat(e1.isCrossesDay()).isFalse();
        assertThat(e1.getAccMmLower()).isCloseTo(15.444, within(0.001));
        assertThat(e1.getAccMmUpper()).isCloseTo(15.444, within(0.001));
        RainEvent e2 = set1.get(1);
        assertThat(e2.getStartAt()).isEqualTo(Instant.parse("2026-08-15T00:20:00Z"));
        assertThat(e2.getEndAt()).isEqualTo(Instant.parse("2026-08-15T01:20:00Z"));
        assertThat(e2.isCrossesDay()).isFalse();
        assertThat(e2.getAccMmLower()).isCloseTo(8.924, within(0.001));

        // 候选集 2：假设缺测段有雨 → 合并为单场跨日事件
        assertThat(set2).hasSize(1);
        RainEvent merged = set2.get(0);
        assertThat(merged.getStartAt()).isEqualTo(Instant.parse("2026-08-14T23:00:00Z"));
        assertThat(merged.getEndAt()).isEqualTo(Instant.parse("2026-08-15T01:20:00Z"));
        assertThat(merged.isCrossesDay()).isTrue();
        assertThat(merged.getAccMmLower()).isCloseTo(24.369, within(0.001));
        assertThat(merged.getAccMmUpper()).isCloseTo(29.276, within(0.001));

        // 不可判区间说明：缺测站与时段
        assertThat(merged.getUndecidableJson()).contains("ST-B").contains("2026-08-14T23:50");

        // 风险窗口：候选集 1 两场分别 MEDIUM / LOW；候选集 2 单场 MEDIUM..HIGH
        RainRiskWindow w1 = rainRiskWindowRepo.findByEventId(e1.getId()).get(0);
        assertThat(w1.getRiskBandLower()).isEqualTo(RiskLevel.MEDIUM);
        RainRiskWindow w2 = rainRiskWindowRepo.findByEventId(e2.getId()).get(0);
        assertThat(w2.getRiskBandLower()).isEqualTo(RiskLevel.LOW);
        RainRiskWindow wm = rainRiskWindowRepo.findByEventId(merged.getId()).get(0);
        assertThat(wm.getRiskBandLower()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(wm.getRiskBandUpper()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void adaptiveWetnessMergesIntoSingleCrossDayEvent() {
        Map<String, Object> result = evaluator.reconstruct(RainEventEvaluator.defaultParams(
                RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO, RainScheme.ADAPTIVE_WETNESS));
        // 前期面雨量 15.4mm ≥ 12mm（土壤饱和），间歇 30min < 流域响应 45min → 两种假设同为单场，可定场
        assertThat(result.get("status")).isEqualTo(RainEventStatus.CONFIRMED);

        List<RainEvent> events = rainEventRepo.findBySchemeAndStatusNotOrderByStartAtAsc(
                RainScheme.ADAPTIVE_WETNESS, RainEventStatus.SUPERSEDED);
        assertThat(events).hasSize(1);
        RainEvent e = events.get(0);
        assertThat(e.getCandidateSetNo()).isNull();
        assertThat(e.getStartAt()).isEqualTo(Instant.parse("2026-08-14T23:00:00Z"));
        assertThat(e.getEndAt()).isEqualTo(Instant.parse("2026-08-15T01:20:00Z"));
        assertThat(e.isCrossesDay()).isTrue();
        // 窗口末端确认无雨 40min < 响应 45min → 事件未闭合
        assertThat(e.isOpenEnded()).isTrue();
        assertThat(e.getAccMmLower()).isCloseTo(24.369, within(0.001));
        assertThat(e.getAccMmUpper()).isCloseTo(29.276, within(0.001));
        // 空间覆盖：14 个时段中 3 个仅 ST-A 实测（覆盖 0.489）
        assertThat(e.getSpatialCoverage()).isCloseTo(0.890, within(0.001));

        RainRiskWindow w = rainRiskWindowRepo.findByEventId(e.getId()).get(0);
        assertThat(w.getRiskBandLower()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(w.getRiskBandUpper()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void adaptiveFallsBackToGapThresholdWhenSoilNotSaturated() {
        // 湿润阈值调到 100mm：前期累计不饱和 → 按固定间隔拆场（仍因缺测保留候选）
        RainEventEvaluator.Params p = new RainEventEvaluator.Params(
                RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO, RainScheme.ADAPTIVE_WETNESS,
                30, 45, 100.0, 15.0, 25.0, 10.0);
        Map<String, Object> result = evaluator.reconstruct(p);
        assertThat(result.get("status")).isEqualTo(RainEventStatus.CANDIDATE);
        List<RainEvent> events = rainEventRepo.findBySchemeAndStatusNotOrderByStartAtAsc(
                RainScheme.ADAPTIVE_WETNESS, RainEventStatus.SUPERSEDED);
        List<RainEvent> set1 = events.stream().filter(e -> e.getCandidateSetNo() == 1).toList();
        assertThat(set1).hasSize(2); // 与固定间隔方案一致拆为两场
    }

    @Test
    void fixedGapSplitsWhenGapFullyMeasured() {
        // 补齐 B 站缺测段（实测无雨）→ 边界可判：固定间隔拆为两场 CONFIRMED
        for (int i = 5; i <= 7; i++) {
            Instant bucket = RainScenario.WINDOW_FROM.plus(10L * i, java.time.temporal.ChronoUnit.MINUTES);
            stationService.addSample(rain.stationB, bucket.minusSeconds(40), 0.0, SampleQuality.MEASURED);
        }
        Map<String, Object> result = evaluator.reconstruct(RainEventEvaluator.defaultParams(
                RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO, RainScheme.FIXED_GAP));
        assertThat(result.get("status")).isEqualTo(RainEventStatus.CONFIRMED);
        List<RainEvent> events = rainEventRepo.findBySchemeAndStatusNotOrderByStartAtAsc(
                RainScheme.FIXED_GAP, RainEventStatus.SUPERSEDED);
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(e -> assertThat(e.getCandidateSetNo()).isNull());
    }

    @Test
    void missingDataBounderGivesBoundsWithoutInterpolation() {
        evaluator.reconstruct(RainEventEvaluator.defaultParams(
                RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO, RainScheme.ADAPTIVE_WETNESS));
        RainEvent e = rainEventRepo.findBySchemeAndStatusNotOrderByStartAtAsc(
                RainScheme.ADAPTIVE_WETNESS, RainEventStatus.SUPERSEDED).get(0);

        JsonNode bounds = om.readTree(e.getStationBoundsJson());
        JsonNode b = null;
        JsonNode a = null;
        for (JsonNode n : bounds) {
            if ("ST-B".equals(n.path("stationCode").asText())) b = n;
            if ("ST-A".equals(n.path("stationCode").asText())) a = n;
        }
        // ST-B：实测 20.8mm，缺测 3 段；上界 = 实测 + 3 × 事件内最大实测 3.2 = 30.4，不做插值
        assertThat(b).isNotNull();
        assertThat(b.path("measuredMm").asDouble()).isCloseTo(20.8, within(0.001));
        assertThat(b.path("missingBuckets").asInt()).isEqualTo(3);
        assertThat(b.path("lowerMm").asDouble()).isCloseTo(20.8, within(0.001));
        assertThat(b.path("upperMm").asDouble()).isCloseTo(30.4, within(0.001));
        // ST-A：全实测，上下界相同
        assertThat(a).isNotNull();
        assertThat(a.path("missingBuckets").asInt()).isEqualTo(0);
        assertThat(a.path("lowerMm").asDouble()).isCloseTo(28.1, within(0.001));
        assertThat(a.path("upperMm").asDouble()).isCloseTo(28.1, within(0.001));

        // 缺测采样不落任何数值（无插值点伪装实测）
        List<RainSample> missing = rainSampleRepo.findAll().stream()
                .filter(s -> s.getQuality() == SampleQuality.MISSING).toList();
        assertThat(missing).hasSize(3);
        assertThat(missing).allSatisfy(s -> assertThat(s.getMm()).isNull());
    }

    @Test
    void watershedMapperProducesSubbasinProcess() {
        evaluator.reconstruct(RainEventEvaluator.defaultParams(
                RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO, RainScheme.ADAPTIVE_WETNESS));
        RainEvent e = rainEventRepo.findBySchemeAndStatusNotOrderByStartAtAsc(
                RainScheme.ADAPTIVE_WETNESS, RainEventStatus.SUPERSEDED).get(0);

        List<SubbasinSeries> sb1 = subbasinSeriesRepo
                .findByEventIdOrderBySubbasinCodeAscBucketStartAsc(e.getId()).stream()
                .filter(s -> s.getSubbasinCode().equals("SB-1")).toList();
        assertThat(sb1).hasSize(14);
        // 23:00 时段：0.7×3.0 + 0.3×2.4 = 2.82
        assertThat(sb1.get(0).getMmLower()).isCloseTo(2.82, within(0.001));
        assertThat(sb1.get(0).getMeasuredCoverage()).isCloseTo(1.0, within(0.001));
        // 缺测时段 23:50：下界 0（A 实测 0），上界 0.3×3.2=0.96，实测覆盖 0.7
        SubbasinSeries gap = sb1.stream()
                .filter(s -> s.getBucketStart().equals(Instant.parse("2026-08-14T23:50:00Z")))
                .findFirst().orElseThrow();
        assertThat(gap.getMmLower()).isEqualTo(0.0);
        assertThat(gap.getMmUpper()).isCloseTo(0.96, within(0.001));
        assertThat(gap.getMeasuredCoverage()).isCloseTo(0.7, within(0.001));
        // 子流域累计：SB-1 = 0.7×28.1 + 0.3×20.8 = 25.91；SB-2 = 0.2×28.1 + 0.8×20.8 = 22.26
        double sb1Total = sb1.stream().mapToDouble(SubbasinSeries::getMmLower).sum();
        assertThat(sb1Total).isCloseTo(25.91, within(0.01));
        double sb2Total = subbasinSeriesRepo
                .findByEventIdOrderBySubbasinCodeAscBucketStartAsc(e.getId()).stream()
                .filter(s -> s.getSubbasinCode().equals("SB-2"))
                .mapToDouble(SubbasinSeries::getMmLower).sum();
        assertThat(sb2Total).isCloseTo(22.26, within(0.01));
        // 流域面雨量 = 面积加权：(5.2×25.91 + 3.8×22.26)/9 ≈ 事件累计下界
        double basin = (5.2 * sb1Total + 3.8 * sb2Total) / 9.0;
        assertThat(basin).isCloseTo(e.getAccMmLower(), within(0.01));
    }

    @Test
    void compareShowsRiskAttributionDifference() {
        evaluator.reconstruct(RainEventEvaluator.defaultParams(
                RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO, RainScheme.FIXED_GAP));
        evaluator.reconstruct(RainEventEvaluator.defaultParams(
                RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO, RainScheme.ADAPTIVE_WETNESS));

        Map<String, Object> cmp = queryService.compare(RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO);
        @SuppressWarnings("unchecked")
        Map<String, Object> schemes = (Map<String, Object>) cmp.get("schemes");
        @SuppressWarnings("unchecked")
        Map<String, Object> fixed = (Map<String, Object>) schemes.get("FIXED_GAP");
        @SuppressWarnings("unchecked")
        Map<String, Object> adaptive = (Map<String, Object>) schemes.get("ADAPTIVE_WETNESS");
        assertThat(fixed.get("status")).isEqualTo(RainEventStatus.CANDIDATE);
        assertThat(adaptive.get("status")).isEqualTo(RainEventStatus.CONFIRMED);
        // 风险归因差异：固定间隔候选集 1 把夜间拆为两场独立入流，湿润方案归因单场跨日事件
        String diff = (String) cmp.get("riskAttributionDiff");
        assertThat(diff).contains("FIXED_GAP").contains("ADAPTIVE_WETNESS").contains("跨日");
    }
}
