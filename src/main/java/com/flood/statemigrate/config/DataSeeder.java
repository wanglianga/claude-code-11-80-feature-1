package com.flood.statemigrate.config;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.repo.*;
import com.flood.statemigrate.service.RainStationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示数据：流 FY-MAIN 四个分区。
 *  p0 检查点完成且启停守恒 → 适配；p1 快照缺失启停事件 → 限定窗口回放；
 *  p2 旧作业检查点未完成且新规则低→高 → 闸门待验证；p3 守恒满足（空启停）→ 适配。
 * 另含降雨事件重建演示：两站 10 分钟雨量跨日样例（见 seedRainfall）。
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final StreamDefinitionRepo streamRepo;
    private final RulePackageRepo rulePackageRepo;
    private final CheckpointManifestRepo checkpointRepo;
    private final StreamEventRepo eventRepo;
    private final EvidenceEntryRepo evidenceRepo;
    private final RainStationRepo rainStationRepo;
    private final RainSampleRepo rainSampleRepo;
    private final WeightSetRepo weightSetRepo;
    private final RainStationService rainStationService;
    private final boolean seedEnabled;

    public DataSeeder(StreamDefinitionRepo streamRepo, RulePackageRepo rulePackageRepo,
                      CheckpointManifestRepo checkpointRepo, StreamEventRepo eventRepo,
                      EvidenceEntryRepo evidenceRepo,
                      RainStationRepo rainStationRepo, RainSampleRepo rainSampleRepo,
                      WeightSetRepo weightSetRepo, RainStationService rainStationService,
                      @Value("${app.seed-demo-data:true}") boolean seedEnabled) {
        this.streamRepo = streamRepo;
        this.rulePackageRepo = rulePackageRepo;
        this.checkpointRepo = checkpointRepo;
        this.eventRepo = eventRepo;
        this.evidenceRepo = evidenceRepo;
        this.rainStationRepo = rainStationRepo;
        this.rainSampleRepo = rainSampleRepo;
        this.weightSetRepo = weightSetRepo;
        this.rainStationService = rainStationService;
        this.seedEnabled = seedEnabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            return;
        }
        if (streamRepo.count() == 0) {
            seedMigrationDemo();
        }
        if (rainStationRepo.count() == 0) {
            seedRainfall();
        }
    }

    private void seedMigrationDemo() {
        log.info("写入演示数据: FY-MAIN");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        StreamDefinition stream = new StreamDefinition();
        stream.setStreamCode("FY-MAIN");
        stream.setName("城区防汛主干实时计算链路");
        stream.setInputTopic("flood.telemetry.raw");
        stream.setEventTimeField("eventTime");
        stream.setWaterLevelBaseline(2.5);
        stream.setStateSchema(StateSchema.GRID_WINDOW_V1);
        stream.setRulePackageVersion("v1.0.0");
        stream.setTargetRulePackageVersion("v2.0.0");
        stream.setTotalPartitions(4);
        stream.setEventRetentionHours(48);
        stream.setPartitionConfigJson("["
                + "{\"partitionNo\":0,\"roadId\":\"R-001\",\"pumpId\":\"P-0\",\"reachId\":\"RCH-0\",\"key\":true},"
                + "{\"partitionNo\":1,\"roadId\":\"R-002\",\"pumpId\":\"P-1\",\"reachId\":\"RCH-1\",\"key\":true},"
                + "{\"partitionNo\":2,\"roadId\":\"R-003\",\"pumpId\":\"P-2\",\"reachId\":\"RCH-2\",\"key\":true},"
                + "{\"partitionNo\":3,\"roadId\":\"R-004\",\"pumpId\":\"P-3\",\"reachId\":\"RCH-3\",\"key\":false}]");
        stream.setStatus(StreamStatus.ACTIVE);
        streamRepo.save(stream);
        Long sid = stream.getId();

        RulePackage oldPkg = new RulePackage();
        oldPkg.setStreamId(sid);
        oldPkg.setVersion("v1.0.0");
        oldPkg.setKind(RuleKind.FIXED_THRESHOLD);
        oldPkg.setConfigJson("{\"medium\":20,\"high\":50}");
        oldPkg.setStatus("ACTIVE");
        rulePackageRepo.save(oldPkg);

        RulePackage newPkg = new RulePackage();
        newPkg.setStreamId(sid);
        newPkg.setVersion("v2.0.0");
        newPkg.setKind(RuleKind.MULTI_FACTOR);
        newPkg.setConfigJson("{\"medium\":25,\"high\":55,\"wRain\":0.6,\"wTrend\":1.0,"
                + "\"wLag\":0.15,\"wDepth\":0.2,\"credibilityFloor\":0.5}");
        newPkg.setStatus("ACTIVE");
        rulePackageRepo.save(newPkg);

        // 每分区事件参数：{分区, 道路, 泵站, 河段, 每10分钟雨量mm, 水位起点, 水位终点, 健康度}
        double[][] params = {
                {0, 3.5, 2.60, 2.75, 0.92},
                {1, 2.9, 2.62, 2.80, 0.88},
                {2, 1.15, 2.70, 3.50, 0.90},
                {3, 0.92, 2.55, 2.60, 0.95}};
        String[] roads = {"R-001", "R-002", "R-003", "R-004"};
        String[] pumps = {"P-0", "P-1", "P-2", "P-3"};
        String[] reaches = {"RCH-0", "RCH-1", "RCH-2", "RCH-3"};

        for (double[] pa : params) {
            int p = (int) pa[0];
            double rainPer10Min = pa[1];
            double levelStart = pa[2];
            double levelEnd = pa[3];
            double health = pa[4];
            List<StreamEvent> events = new ArrayList<>();

            for (int i = 0; i <= 18; i++) { // 每10分钟雨量，覆盖近3小时
                events.add(event(sid, p, now.minus(180 - 10L * i, ChronoUnit.MINUTES),
                        "RAIN", roads[p], null, null, "{\"mm\":" + rainPer10Min + "}"));
            }
            for (int i = 0; i <= 12; i++) { // 每15分钟水位
                double level = levelStart + (levelEnd - levelStart) * i / 12.0;
                events.add(event(sid, p, now.minus(180 - 15L * i, ChronoUnit.MINUTES),
                        "WATER_LEVEL", null, null, reaches[p],
                        "{\"levelM\":" + Math.round(level * 1000.0) / 1000.0 + "}"));
            }
            for (int i = 0; i <= 6; i++) { // 每30分钟设备健康度
                events.add(event(sid, p, now.minus(180 - 30L * i, ChronoUnit.MINUTES),
                        "DEVICE_HEALTH", null, pumps[p], null, "{\"score\":" + health + "}"));
            }
            if (p == 0) { // 泵站启停：与 p0 快照守恒（30 分钟）
                events.add(event(sid, p, now.minus(90, ChronoUnit.MINUTES), "PUMP", null, pumps[p], null,
                        "{\"on\":true}"));
                events.add(event(sid, p, now.minus(60, ChronoUnit.MINUTES), "PUMP", null, pumps[p], null,
                        "{\"on\":false}"));
            }
            if (p == 1) { // p1 实际有启停（供回放重算），但旧快照缺失该区间
                events.add(event(sid, p, now.minus(80, ChronoUnit.MINUTES), "PUMP", null, pumps[p], null,
                        "{\"on\":true}"));
                events.add(event(sid, p, now.minus(50, ChronoUnit.MINUTES), "PUMP", null, pumps[p], null,
                        "{\"on\":false}"));
            }
            if (p == 2) { // p2 人工上报与视频识别积水
                events.add(event(sid, p, now.minus(40, ChronoUnit.MINUTES), "ROAD_REPORT", roads[p], null, null,
                        "{\"depthCm\":45}"));
                events.add(event(sid, p, now.minus(30, ChronoUnit.MINUTES), "VIDEO", roads[p], null, null,
                        "{\"depthCm\":50}"));
            }
            events.sort((a, b) -> a.getEventTime().compareTo(b.getEventTime()));
            long offset = 0;
            for (StreamEvent e : events) {
                e.setEventOffset(++offset);
                eventRepo.save(e);
            }
        }

        // 检查点：p0 守恒满足；p1 缺失启停事件；p2 旧作业未完成；p3 空启停守恒满足
        CheckpointManifest cp0 = checkpoint(sid, 0, 30, now,
                "{\"gridId\":\"R-001\",\"windowStart\":\"" + now.minus(130, ChronoUnit.MINUTES) + "\","
                        + "\"windowEnd\":\"" + now.minus(10, ChronoUnit.MINUTES) + "\","
                        + "\"rain2hAcc\":45.5,\"upstreamInflowAcc\":1300.0,\"deviceHealth\":0.92,"
                        + "\"pumpRuntimeMin\":30,"
                        + "\"pumpOnOffEvents\":[{\"t\":\"" + now.minus(90, ChronoUnit.MINUTES) + "\",\"on\":true},"
                        + "{\"t\":\"" + now.minus(60, ChronoUnit.MINUTES) + "\",\"on\":false}],"
                        + "\"riskLevel\":\"MEDIUM\"}",
                CheckpointStatus.COMPLETE, "old-batch-p0-30");
        CheckpointManifest cp1 = checkpoint(sid, 1, 32, now,
                "{\"gridId\":\"R-002\",\"windowStart\":\"" + now.minus(130, ChronoUnit.MINUTES) + "\","
                        + "\"windowEnd\":\"" + now.minus(10, ChronoUnit.MINUTES) + "\","
                        + "\"rain2hAcc\":37.7,\"upstreamInflowAcc\":900.0,\"deviceHealth\":0.88,"
                        + "\"pumpRuntimeMin\":40,\"riskLevel\":\"MEDIUM\"}",
                CheckpointStatus.COMPLETE, "old-batch-p1-32");
        checkpoint(sid, 2, 25, now, null, CheckpointStatus.PENDING, "old-batch-p2-24");
        CheckpointManifest cp3 = checkpoint(sid, 3, 28, now,
                "{\"gridId\":\"R-004\",\"windowStart\":\"" + now.minus(130, ChronoUnit.MINUTES) + "\","
                        + "\"windowEnd\":\"" + now.minus(10, ChronoUnit.MINUTES) + "\","
                        + "\"rain2hAcc\":11.96,\"upstreamInflowAcc\":300.0,\"deviceHealth\":0.95,"
                        + "\"pumpRuntimeMin\":0,\"pumpOnOffEvents\":[],\"riskLevel\":\"LOW\"}",
                CheckpointStatus.COMPLETE, "old-batch-p3-28");

        // 旧作业已发布输出（证据账 OLD_OUTPUT）
        oldOutput(sid, 0, "R-001", RiskLevel.MEDIUM, 45.5, 30, "snap:chk-" + cp0.getId());
        oldOutput(sid, 1, "R-002", RiskLevel.MEDIUM, 37.7, 32, "snap:chk-" + cp1.getId());
        oldOutput(sid, 3, "R-004", RiskLevel.LOW, 11.96, 28, "snap:chk-" + cp3.getId());
        log.info("演示数据就绪: streamId={}", sid);
    }

    private StreamEvent event(Long sid, int p, Instant time, String type,
                              String roadId, String pumpId, String reachId, String payload) {
        StreamEvent e = new StreamEvent();
        e.setStreamId(sid);
        e.setPartitionNo(p);
        e.setEventTime(time);
        e.setType(type);
        e.setRoadId(roadId);
        e.setPumpId(pumpId);
        e.setReachId(reachId);
        e.setPayloadJson(payload);
        return e;
    }

    private CheckpointManifest checkpoint(Long sid, int p, long offset, Instant now, String snapshot,
                                          CheckpointStatus status, String batchId) {
        CheckpointManifest cp = new CheckpointManifest();
        cp.setStreamId(sid);
        cp.setPartitionNo(p);
        cp.setConsumeOffset(offset);
        cp.setWindowWatermark(now.minus(10, ChronoUnit.MINUTES));
        cp.setStateSnapshotJson(snapshot);
        cp.setOutputBatchId(batchId);
        cp.setRulePackageVersion("v1.0.0");
        cp.setStateSchema(StateSchema.GRID_WINDOW_V1);
        cp.setStatus(status);
        return checkpointRepo.save(cp);
    }

    private void oldOutput(Long sid, int p, String roadId, RiskLevel level, double score,
                           long offset, String stateVersion) {
        EvidenceEntry e = new EvidenceEntry();
        e.setStreamId(sid);
        e.setPartitionNo(p);
        e.setKind(EvidenceKind.OLD_OUTPUT);
        e.setRoadId(roadId);
        e.setRiskLevel(level);
        e.setRiskScore(score);
        e.setInputRefsJson("{\"fromOffset\":0,\"toOffset\":" + offset + ",\"eventIds\":[]}");
        e.setRuleVersion("v1.0.0");
        e.setStateVersion(stateVersion);
        e.setWatermarkOffset(offset);
        e.setNote("旧作业已发布输出");
        evidenceRepo.save(e);
    }

    /**
     * 降雨事件重建演示：2026-08-14 23:00 → 2026-08-15 02:00（UTC）两站 10 分钟雨量。
     *  ST-A（城东，站钟快 30s）：23:50–00:20 有 30 分钟无雨间隔，00:20 短暂降雨，00:40 起再降雨；
     *  ST-B（城西，站钟慢 40s）：同段（23:50/00:00/00:10 三个时段）缺测。
     * 固定间隔方案（30 分钟）在缺口处达到拆场阈值，但 B 站缺测使边界不可判 → 保留候选区间；
     * 湿润状态方案因前期面雨量 15.4mm ≥ 12mm（土壤饱和）且间歇 30min &lt; 流域响应 45min → 合并为单场跨日事件。
     * 权重集 WS-1：SB-1（城东 5.2km²）A0.7/B0.3，SB-2（城西 3.8km²）A0.2/B0.8。
     */
    private void seedRainfall() {
        log.info("写入降雨演示数据: ST-A/ST-B 跨日样例");
        RainStation a = new RainStation();
        a.setStationCode("ST-A");
        a.setName("城东雨量站");
        a.setClockOffsetSec(30);
        a.setEquipmentId("EQ-A-1");
        a.setActive(true);
        rainStationRepo.save(a);

        RainStation b = new RainStation();
        b.setStationCode("ST-B");
        b.setName("城西雨量站");
        b.setClockOffsetSec(-40);
        b.setEquipmentId("EQ-B-1");
        b.setActive(true);
        rainStationRepo.save(b);

        WeightSet ws = new WeightSet();
        ws.setVersion("WS-1");
        ws.setWeightsJson("["
                + "{\"stationCode\":\"ST-A\",\"subbasinCode\":\"SB-1\",\"weight\":0.7},"
                + "{\"stationCode\":\"ST-B\",\"subbasinCode\":\"SB-1\",\"weight\":0.3},"
                + "{\"stationCode\":\"ST-A\",\"subbasinCode\":\"SB-2\",\"weight\":0.2},"
                + "{\"stationCode\":\"ST-B\",\"subbasinCode\":\"SB-2\",\"weight\":0.8}]");
        ws.setAreasJson("{\"SB-1\":5.2,\"SB-2\":3.8}");
        ws.setStatus("ACTIVE");
        weightSetRepo.save(ws);

        Instant base = Instant.parse("2026-08-14T23:00:00Z");
        Double[] mmA = {3.0, 3.4, 3.8, 4.2, 3.6, 0.0, 0.0, 0.0, 1.8, 0.0,
                2.2, 2.6, 2.0, 1.5, 0.0, 0.0, 0.0, 0.0};
        Double[] mmB = {2.4, 2.8, 3.2, 2.6, 2.0, null, null, null, 1.2, 0.0,
                1.8, 2.1, 1.6, 1.1, 0.0, 0.0, 0.0, 0.0};
        for (int i = 0; i < mmA.length; i++) {
            Instant bucket = base.plus(10L * i, ChronoUnit.MINUTES);
            // ST-A 站钟快 30s：观测时间戳 = 真值 + 30s；ST-B 站钟慢 40s：观测时间戳 = 真值 - 40s
            rainStationService.addSample(a, bucket.plusSeconds(30), mmA[i], SampleQuality.MEASURED);
            rainStationService.addSample(b, bucket.minusSeconds(40),
                    mmB[i], mmB[i] == null ? SampleQuality.MISSING : SampleQuality.MEASURED);
        }
        log.info("降雨演示数据就绪: 两站各 {} 个时段（ST-B 缺测 3 段）", mmA.length);
    }
}
