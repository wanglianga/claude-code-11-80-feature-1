package com.flood.statemigrate;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.service.RainEventEvaluator;
import com.flood.statemigrate.service.RainLedgerService;
import com.flood.statemigrate.service.RainStationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 事件版本账：站点增补、设备更换、权重变动对已计算风险窗口的影响。
 */
class RainLedgerTest extends BaseIntegrationTest {

    @Autowired
    private RainEventEvaluator evaluator;
    @Autowired
    private RainLedgerService ledgerService;
    @Autowired
    private RainStationService stationService;

    private RainScenario rain;

    @BeforeEach
    void seedAndReconstruct() {
        rain = new RainScenario(rainStationRepo, weightSetRepo, stationService);
        rain.seedNight();
        evaluator.reconstruct(RainEventEvaluator.defaultParams(
                RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO, RainScheme.FIXED_GAP));
        evaluator.reconstruct(RainEventEvaluator.defaultParams(
                RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO, RainScheme.ADAPTIVE_WETNESS));
    }

    @Test
    void stationAddedResolvesUndecidableBoundaryAndImpactsWindows() {
        // 新站 ST-C（城南）登记并写入采样：仅 23:50–00:20 三段有雨 1.0mm，其余实测无雨
        RainStation c = new RainStation();
        c.setStationCode("ST-C");
        c.setName("城南雨量站");
        c.setClockOffsetSec(0);
        c.setEquipmentId("EQ-C-1");
        c.setActive(true);
        rainStationRepo.save(c);
        for (int i = 0; i < RainScenario.MM_A.length; i++) {
            Instant bucket = RainScenario.WINDOW_FROM.plus(10L * i, ChronoUnit.MINUTES);
            double mm = (i >= 5 && i <= 7) ? 1.0 : 0.0;
            stationService.addSample(c, bucket, mm, SampleQuality.MEASURED);
        }

        // 站点增补：ST-C 纳入权重体系（各子流域权重和仍为 1）
        List<Map<String, Object>> weights = List.of(
                Map.of("stationCode", "ST-A", "subbasinCode", "SB-1", "weight", 0.5),
                Map.of("stationCode", "ST-B", "subbasinCode", "SB-1", "weight", 0.3),
                Map.of("stationCode", "ST-C", "subbasinCode", "SB-1", "weight", 0.2),
                Map.of("stationCode", "ST-A", "subbasinCode", "SB-2", "weight", 0.1),
                Map.of("stationCode", "ST-B", "subbasinCode", "SB-2", "weight", 0.6),
                Map.of("stationCode", "ST-C", "subbasinCode", "SB-2", "weight", 0.3));
        RainLedgerEntry entry = ledgerService.recordChange(new RainLedgerService.ChangeReq(
                RainChangeKind.STATION_ADDED, Instant.parse("2026-08-14T23:00:00Z"),
                RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO,
                "ST-C", null, null, null, weights, null, "新增城南雨量站"));

        assertThat(entry.getNewWeightSetVersion()).isEqualTo("WS-2");
        assertThat(entry.getImpactedJson()).contains("FIXED_GAP").contains("windowStart");
        assertThat(weightSetRepo.findByStatus("ACTIVE").orElseThrow().getVersion()).isEqualTo("WS-2");
        assertThat(weightSetRepo.findByVersion("WS-1").orElseThrow().getStatus()).isEqualTo("SUPERSEDED");

        // 新站证实间歇期有雨 → 固定间隔方案的候选区间消解，两方案均定为单场跨日事件
        List<RainEvent> fixed = rainEventRepo.findBySchemeAndStatusNotOrderByStartAtAsc(
                RainScheme.FIXED_GAP, RainEventStatus.SUPERSEDED);
        assertThat(fixed).hasSize(1);
        assertThat(fixed.get(0).getStatus()).isEqualTo(RainEventStatus.CONFIRMED);
        assertThat(fixed.get(0).isCrossesDay()).isTrue();
        assertThat(fixed.get(0).getRunNo()).isEqualTo(2);
        assertThat(fixed.get(0).getWeightSetVersion()).isEqualTo("WS-2");

        // 流域累计下界 = 0.3311×28.1 + 0.4267×20.8 + 0.2422×3.0 ≈ 18.906（含 ST-C 贡献）
        RainEvent merged = rainEventRepo.findBySchemeAndStatusNotOrderByStartAtAsc(
                RainScheme.ADAPTIVE_WETNESS, RainEventStatus.SUPERSEDED).get(0);
        assertThat(merged.getAccMmLower()).isCloseTo(18.906, within(0.001));
        assertThat(merged.getAccMmUpper()).isCloseTo(23.002, within(0.001));
        assertThat(merged.getStationBoundsJson()).contains("ST-C");

        // 旧版本全部取代，可溯源
        List<RainEvent> superseded = rainEventRepo.findAll().stream()
                .filter(e -> e.getStatus() == RainEventStatus.SUPERSEDED).toList();
        assertThat(superseded).isNotEmpty();
        assertThat(superseded).allSatisfy(e -> assertThat(e.getSupersededByRunNo()).isNotNull());
    }

    @Test
    void equipmentReplacedRealignsSamplesAndRecomputes() {
        // ST-B 设备更换：2026-08-15T00:30 起新设备 EQ-B-2，校时误差 -5s
        RainLedgerEntry entry = ledgerService.recordChange(new RainLedgerService.ChangeReq(
                RainChangeKind.EQUIPMENT_REPLACED, Instant.parse("2026-08-15T00:30:00Z"),
                RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO,
                "ST-B", null, -5L, "EQ-B-2", null, null, "巡检更换采集器"));

        assertThat(entry.getSummary()).contains("EQ-B-2").contains("重对齐采样");
        // 站点当前参数更新
        RainStation b = rainStationRepo.findByStationCode("ST-B").orElseThrow();
        assertThat(b.getEquipmentId()).isEqualTo("EQ-B-2");
        assertThat(b.getClockOffsetSec()).isEqualTo(-5);

        // 原 00:40 的 1.8mm（站钟观测 00:39:20）按新误差校正到 00:39:25 → 落入 00:30 时段
        RainSample moved = rainSampleRepo.findByStationIdAndBucketStart(
                b.getId(), Instant.parse("2026-08-15T00:30:00Z")).orElseThrow();
        assertThat(moved.getMm()).isEqualTo(1.8);
        assertThat(moved.getEquipmentId()).isEqualTo("EQ-B-2");
        assertThat(moved.getClockOffsetSecApplied()).isEqualTo(-5);

        // 两方案按原参数重算，版本递增，账已落
        List<RainEvent> active = rainEventRepo.findBySchemeAndStatusNotOrderByStartAtAsc(
                RainScheme.ADAPTIVE_WETNESS, RainEventStatus.SUPERSEDED);
        assertThat(active).isNotEmpty();
        assertThat(active.get(0).getRunNo()).isEqualTo(2);
        assertThat(rainLedgerRepo.findByChangeTypeOrderByIdAsc(RainChangeKind.EQUIPMENT_REPLACED))
                .hasSize(1);
    }

    @Test
    void weightChangedSupersedesWeightSetAndRecomputes() {
        List<Map<String, Object>> weights = List.of(
                Map.of("stationCode", "ST-A", "subbasinCode", "SB-1", "weight", 0.5),
                Map.of("stationCode", "ST-B", "subbasinCode", "SB-1", "weight", 0.5),
                Map.of("stationCode", "ST-A", "subbasinCode", "SB-2", "weight", 0.5),
                Map.of("stationCode", "ST-B", "subbasinCode", "SB-2", "weight", 0.5));
        RainLedgerEntry entry = ledgerService.recordChange(new RainLedgerService.ChangeReq(
                RainChangeKind.WEIGHT_CHANGED, Instant.parse("2026-08-14T23:00:00Z"),
                RainScenario.WINDOW_FROM, RainScenario.WINDOW_TO,
                null, null, null, null, weights, null, "权重复核"));

        assertThat(entry.getNewWeightSetVersion()).isEqualTo("WS-2");
        assertThat(weightSetRepo.findByVersion("WS-1").orElseThrow().getStatus()).isEqualTo("SUPERSEDED");

        // 等权重后面雨量下界 = 0.5×28.1 + 0.5×20.8 = 24.45
        RainEvent merged = rainEventRepo.findBySchemeAndStatusNotOrderByStartAtAsc(
                RainScheme.ADAPTIVE_WETNESS, RainEventStatus.SUPERSEDED).get(0);
        assertThat(merged.getWeightSetVersion()).isEqualTo("WS-2");
        assertThat(merged.getAccMmLower()).isCloseTo(24.45, within(0.001));
        // 上界 = 0.5×28.1 + 0.5×30.4 = 29.25
        assertThat(merged.getAccMmUpper()).isCloseTo(29.25, within(0.001));

        // 账本三条变更均可按类型查询
        assertThat(rainLedgerRepo.findByChangeTypeOrderByIdAsc(RainChangeKind.WEIGHT_CHANGED)).hasSize(1);
        assertThat(rainLedgerRepo.findAllByOrderByIdAsc()).hasSize(1);
    }
}
