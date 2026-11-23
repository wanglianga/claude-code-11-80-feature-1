package com.flood.statemigrate;

import com.flood.statemigrate.service.RainStationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 降雨数据集接口层：鉴权、重建、两方案对比、子流域过程、版本账。 */
@AutoConfigureMockMvc
class RainApiTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RainStationService stationService;

    private static final String WINDOW =
            "from=2026-08-14T23:00:00Z&to=2026-08-15T02:00:00Z";

    @Test
    void rainApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/rain/stations"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/rain/events"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/rain/ledger"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reconstructCompareSeriesAndLedgerFlow() throws Exception {
        new RainScenario(rainStationRepo, weightSetRepo, stationService).seedNight();

        // 重建（两方案）：固定间隔保留候选区间，湿润状态定为单场跨日事件
        mockMvc.perform(post("/api/rain/events/reconstruct").with(httpBasic("operator", "operator123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-08-14T23:00:00Z\",\"to\":\"2026-08-15T02:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.FIXED_GAP.status").value("CANDIDATE"))
                .andExpect(jsonPath("$.ADAPTIVE_WETNESS.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.ADAPTIVE_WETNESS.candidateSets[0].events[0].crossesDay").value(true));

        // 两方案对比：风险归因差异
        mockMvc.perform(get("/api/rain/events/compare?" + WINDOW)
                        .with(httpBasic("operator", "operator123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemes.FIXED_GAP.status").value("CANDIDATE"))
                .andExpect(jsonPath("$.schemes.ADAPTIVE_WETNESS.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.riskAttributionDiff").isString());

        // 子流域降雨过程
        mockMvc.perform(get("/api/rain/subbasins/SB-1/series?" + WINDOW)
                        .with(httpBasic("operator", "operator123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subbasinCode").value("SB-1"))
                .andExpect(jsonPath("$[0].mmLower").isNumber());

        // 版本账：权重变动
        String change = """
                {
                  "changeType": "WEIGHT_CHANGED",
                  "effectiveAt": "2026-08-14T23:00:00Z",
                  "reprocessFrom": "2026-08-14T23:00:00Z",
                  "reprocessTo": "2026-08-15T02:00:00Z",
                  "weights": [
                    {"stationCode":"ST-A","subbasinCode":"SB-1","weight":0.5},
                    {"stationCode":"ST-B","subbasinCode":"SB-1","weight":0.5},
                    {"stationCode":"ST-A","subbasinCode":"SB-2","weight":0.5},
                    {"stationCode":"ST-B","subbasinCode":"SB-2","weight":0.5}
                  ],
                  "note": "权重复核"
                }
                """;
        mockMvc.perform(post("/api/rain/ledger/changes").with(httpBasic("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newWeightSetVersion").value("WS-2"))
                .andExpect(jsonPath("$.impactedJson").isString());

        mockMvc.perform(get("/api/rain/ledger").with(httpBasic("operator", "operator123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].changeType").value("WEIGHT_CHANGED"));
    }
}
