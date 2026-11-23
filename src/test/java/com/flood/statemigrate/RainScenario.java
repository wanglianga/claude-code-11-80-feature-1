package com.flood.statemigrate;

import com.flood.statemigrate.domain.RainStation;
import com.flood.statemigrate.domain.SampleQuality;
import com.flood.statemigrate.domain.WeightSet;
import com.flood.statemigrate.repo.RainStationRepo;
import com.flood.statemigrate.repo.WeightSetRepo;
import com.flood.statemigrate.service.RainStationService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 降雨测试场景：2026-08-14 23:00 → 2026-08-15 02:00（UTC）两站 10 分钟雨量。
 * ST-A 站钟快 30s，23:50–00:20 有 30 分钟无雨间隔，00:40 起再降雨；
 * ST-B 站钟慢 40s，同段三个时段缺测。
 */
class RainScenario {

    static final Instant WINDOW_FROM = Instant.parse("2026-08-14T23:00:00Z");
    static final Instant WINDOW_TO = Instant.parse("2026-08-15T02:00:00Z");

    static final Double[] MM_A = {3.0, 3.4, 3.8, 4.2, 3.6, 0.0, 0.0, 0.0, 1.8, 0.0,
            2.2, 2.6, 2.0, 1.5, 0.0, 0.0, 0.0, 0.0};
    static final Double[] MM_B = {2.4, 2.8, 3.2, 2.6, 2.0, null, null, null, 1.2, 0.0,
            1.8, 2.1, 1.6, 1.1, 0.0, 0.0, 0.0, 0.0};

    private final RainStationRepo stationRepo;
    private final WeightSetRepo weightSetRepo;
    private final RainStationService stationService;

    RainScenario(RainStationRepo stationRepo, WeightSetRepo weightSetRepo,
                 RainStationService stationService) {
        this.stationRepo = stationRepo;
        this.weightSetRepo = weightSetRepo;
        this.stationService = stationService;
    }

    RainStation stationA;
    RainStation stationB;

    /** 两站 + 全部采样 + 权重集 WS-1（SB-1: A0.7/B0.3 5.2km²，SB-2: A0.2/B0.8 3.8km²）。 */
    void seedNight() {
        stationA = new RainStation();
        stationA.setStationCode("ST-A");
        stationA.setName("城东雨量站");
        stationA.setClockOffsetSec(30);
        stationA.setEquipmentId("EQ-A-1");
        stationA.setActive(true);
        stationRepo.save(stationA);

        stationB = new RainStation();
        stationB.setStationCode("ST-B");
        stationB.setName("城西雨量站");
        stationB.setClockOffsetSec(-40);
        stationB.setEquipmentId("EQ-B-1");
        stationB.setActive(true);
        stationRepo.save(stationB);

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

        for (int i = 0; i < MM_A.length; i++) {
            Instant bucket = WINDOW_FROM.plus(10L * i, ChronoUnit.MINUTES);
            stationService.addSample(stationA, bucket.plusSeconds(30), MM_A[i], SampleQuality.MEASURED);
            stationService.addSample(stationB, bucket.minusSeconds(40),
                    MM_B[i], MM_B[i] == null ? SampleQuality.MISSING : SampleQuality.MEASURED);
        }
    }
}
