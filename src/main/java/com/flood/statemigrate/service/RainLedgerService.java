package com.flood.statemigrate.service;

import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 事件版本账：站点增补、设备更换、权重变动对已计算风险窗口的影响。
 * 每次变更：应用变更 → 按原重建参数重算两方案（旧事件与风险窗口置为 SUPERSEDED）→
 * 落账（变更内容 + 重算窗口 + 风险窗口前后对照）。
 */
@Service
public class RainLedgerService {

    /** 变更请求（由控制器组装）。 */
    public record ChangeReq(RainChangeKind changeType, Instant effectiveAt,
                            Instant reprocessFrom, Instant reprocessTo,
                            String stationCode, String stationName,
                            Long newClockOffsetSec, String newEquipmentId,
                            List<Map<String, Object>> weights, Map<String, Double> areas,
                            String note) {}

    private final RainStationRepo stationRepo;
    private final RainSampleRepo sampleRepo;
    private final RainEventRepo eventRepo;
    private final RainRiskWindowRepo riskWindowRepo;
    private final WeightSetRepo weightSetRepo;
    private final RainLedgerRepo ledgerRepo;
    private final RainEventEvaluator evaluator;
    private final WatershedMapper mapper;
    private final ObjectMapper om;

    public RainLedgerService(RainStationRepo stationRepo, RainSampleRepo sampleRepo,
                             RainEventRepo eventRepo, RainRiskWindowRepo riskWindowRepo,
                             WeightSetRepo weightSetRepo, RainLedgerRepo ledgerRepo,
                             RainEventEvaluator evaluator, WatershedMapper mapper, ObjectMapper om) {
        this.stationRepo = stationRepo;
        this.sampleRepo = sampleRepo;
        this.eventRepo = eventRepo;
        this.riskWindowRepo = riskWindowRepo;
        this.weightSetRepo = weightSetRepo;
        this.ledgerRepo = ledgerRepo;
        this.evaluator = evaluator;
        this.mapper = mapper;
        this.om = om;
    }

    @Transactional
    public RainLedgerEntry recordChange(ChangeReq req) {
        if (!req.reprocessFrom().isBefore(req.reprocessTo())) {
            throw ApiException.badRequest("reprocessFrom 必须早于 reprocessTo");
        }
        // 变更前的有效风险窗口（重算窗口内）
        List<RainRiskWindow> before = riskWindowRepo
                .findByStatusAndWindowStartLessThanAndWindowEndGreaterThan(
                        "ACTIVE", req.reprocessTo(), req.reprocessFrom());

        Map<String, Object> detail = new LinkedHashMap<>();
        String newWeightSetVersion = null;
        String summary;
        switch (req.changeType()) {
            case STATION_ADDED -> {
                RainStation station = applyStationAdded(req, detail);
                newWeightSetVersion = applyWeightSet(req, detail);
                summary = "站点增补 " + station.getStationCode() + " 纳入权重集 " + newWeightSetVersion;
            }
            case EQUIPMENT_REPLACED -> {
                int realigned = applyEquipmentReplaced(req, detail);
                summary = "设备更换 " + req.stationCode() + " → " + req.newEquipmentId()
                        + "（校时误差 " + req.newClockOffsetSec() + "s），重对齐采样 " + realigned + " 条";
            }
            case WEIGHT_CHANGED -> {
                newWeightSetVersion = applyWeightSet(req, detail);
                summary = "权重变动，新权重集 " + newWeightSetVersion;
            }
            default -> throw ApiException.badRequest("不支持的变更类型: " + req.changeType());
        }
        if (req.note() != null && !req.note().isBlank()) {
            detail.put("note", req.note());
            summary += "；" + req.note();
        }

        // 按原重建参数重算两方案
        for (RainScheme scheme : RainScheme.values()) {
            RainEventEvaluator.Params p = paramsForRerun(scheme, req.reprocessFrom(), req.reprocessTo());
            evaluator.reconstruct(p);
        }

        List<RainRiskWindow> after = riskWindowRepo
                .findByStatusAndWindowStartLessThanAndWindowEndGreaterThan(
                        "ACTIVE", req.reprocessTo(), req.reprocessFrom());

        RainLedgerEntry entry = new RainLedgerEntry();
        entry.setChangeType(req.changeType());
        entry.setEffectiveAt(req.effectiveAt());
        entry.setReprocessFrom(req.reprocessFrom());
        entry.setReprocessTo(req.reprocessTo());
        entry.setSummary(summary);
        entry.setDetailJson(writeJson(detail));
        entry.setImpactedJson(writeJson(impacted(before, after)));
        entry.setNewWeightSetVersion(newWeightSetVersion);
        return ledgerRepo.save(entry);
    }

    public List<RainLedgerEntry> list(RainChangeKind changeType) {
        return changeType == null ? ledgerRepo.findAllByOrderByIdAsc()
                : ledgerRepo.findByChangeTypeOrderByIdAsc(changeType);
    }

    // ---------- 变更应用 ----------

    private RainStation applyStationAdded(ChangeReq req, Map<String, Object> detail) {
        if (req.stationCode() == null) throw ApiException.badRequest("STATION_ADDED 需要 stationCode");
        if (req.weights() == null || req.weights().isEmpty()) {
            throw ApiException.badRequest("STATION_ADDED 需要完整的新权重列表（含新站，各子流域权重和为 1）");
        }
        RainStation station = stationRepo.findByStationCode(req.stationCode()).orElseGet(() -> {
            if (req.stationName() == null || req.newClockOffsetSec() == null || req.newEquipmentId() == null) {
                throw ApiException.badRequest("站点未登记时，STATION_ADDED 需要 stationName/newClockOffsetSec/newEquipmentId");
            }
            RainStation s = new RainStation();
            s.setStationCode(req.stationCode());
            s.setName(req.stationName());
            s.setClockOffsetSec(req.newClockOffsetSec());
            s.setEquipmentId(req.newEquipmentId());
            s.setActive(true);
            return stationRepo.save(s);
        });
        WatershedMapper.Weights current = currentWeights();
        if (current.stationCodes().contains(station.getStationCode())) {
            throw ApiException.conflict("站点已在当前权重集中: " + station.getStationCode());
        }
        boolean included = req.weights().stream()
                .anyMatch(w -> station.getStationCode().equals(String.valueOf(w.get("stationCode"))));
        if (!included) {
            throw ApiException.badRequest("新权重列表未包含增补站点 " + station.getStationCode());
        }
        detail.put("stationCode", station.getStationCode());
        detail.put("effectiveAt", req.effectiveAt().toString());
        return station;
    }

    private int applyEquipmentReplaced(ChangeReq req, Map<String, Object> detail) {
        if (req.stationCode() == null || req.newEquipmentId() == null || req.newClockOffsetSec() == null) {
            throw ApiException.badRequest("EQUIPMENT_REPLACED 需要 stationCode/newEquipmentId/newClockOffsetSec");
        }
        RainStation station = stationRepo.findByStationCode(req.stationCode())
                .orElseThrow(() -> ApiException.notFound("雨量站不存在: " + req.stationCode()));
        long oldOffset = station.getClockOffsetSec();
        String oldEquipment = station.getEquipmentId();

        // 重对齐 effectiveAt 起观测的采样：correctedAt = observedAt - 新校时误差。
        // 先按新时段归组选出每时段留存值，删除全部受影响旧行后再落新值，避免唯一约束在搬移中冲突。
        List<RainSample> affected = sampleRepo.findByStationIdAndObservedAtGreaterThanEqual(
                station.getId(), req.effectiveAt());
        Map<Instant, List<RainSample>> byNewBucket = affected.stream().collect(Collectors.groupingBy(
                s -> RainStationService.bucketOf(s.getObservedAt().minusSeconds(req.newClockOffsetSec()))));
        record Winner(Instant observedAt, Double mm, SampleQuality quality) {}
        Map<Instant, Winner> assignment = new LinkedHashMap<>();
        for (Map.Entry<Instant, List<RainSample>> e : byNewBucket.entrySet()) {
            RainSample winner = e.getValue().stream()
                    .max(Comparator.comparing((RainSample s) -> s.getQuality() == SampleQuality.MEASURED ? 1 : 0)
                            .thenComparing(s -> s.getObservedAt()))
                    .orElseThrow();
            assignment.put(e.getKey(), new Winner(winner.getObservedAt(), winner.getMm(), winner.getQuality()));
        }
        sampleRepo.deleteAll(affected);
        sampleRepo.flush();
        int realigned = 0;
        for (Map.Entry<Instant, Winner> e : assignment.entrySet()) {
            // 同时段已有未受影响旧行时被订正覆盖，否则新建
            RainSample row = sampleRepo.findByStationIdAndBucketStart(station.getId(), e.getKey())
                    .orElseGet(RainSample::new);
            row.setStationId(station.getId());
            row.setObservedAt(e.getValue().observedAt());
            row.setCorrectedAt(e.getValue().observedAt().minusSeconds(req.newClockOffsetSec()));
            row.setBucketStart(e.getKey());
            row.setMm(e.getValue().mm());
            row.setQuality(e.getValue().quality());
            row.setClockOffsetSecApplied(req.newClockOffsetSec());
            row.setEquipmentId(req.newEquipmentId());
            sampleRepo.save(row);
            realigned++;
        }
        station.setEquipmentId(req.newEquipmentId());
        station.setClockOffsetSec(req.newClockOffsetSec());
        stationRepo.save(station);

        detail.put("stationCode", station.getStationCode());
        detail.put("oldEquipmentId", oldEquipment);
        detail.put("newEquipmentId", req.newEquipmentId());
        detail.put("oldClockOffsetSec", oldOffset);
        detail.put("newClockOffsetSec", req.newClockOffsetSec());
        detail.put("effectiveAt", req.effectiveAt().toString());
        detail.put("realignedSamples", realigned);
        return realigned;
    }

    private String applyWeightSet(ChangeReq req, Map<String, Object> detail) {
        if (req.weights() == null || req.weights().isEmpty()) {
            throw ApiException.badRequest("权重变动需要完整的新权重列表");
        }
        WeightSet current = weightSetRepo.findByStatus("ACTIVE")
                .orElseThrow(() -> ApiException.badRequest("尚无 ACTIVE 权重集"));
        Map<String, Double> areas = req.areas() != null ? req.areas() : currentAreas(current);
        WeightSet next = new WeightSet();
        next.setVersion(nextWeightSetVersion());
        next.setWeightsJson(writeJson(req.weights()));
        next.setAreasJson(writeJson(areas));
        next.setStatus("ACTIVE");
        mapper.parse(next); // 校验权重和与面积
        current.setStatus("SUPERSEDED");
        weightSetRepo.save(current);
        weightSetRepo.save(next);
        detail.put("previousWeightSetVersion", current.getVersion());
        detail.put("newWeightSetVersion", next.getVersion());
        return next.getVersion();
    }

    // ---------- 影响对照 ----------

    /** 风险窗口前后对照（按方案分组，不强行配对，边界可能整体移动）。 */
    private List<Map<String, Object>> impacted(List<RainRiskWindow> before, List<RainRiskWindow> after) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RainScheme scheme : RainScheme.values()) {
            List<RainRiskWindow> b = before.stream().filter(w -> w.getScheme() == scheme).toList();
            List<RainRiskWindow> a = after.stream().filter(w -> w.getScheme() == scheme).toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scheme", scheme);
            row.put("before", b.stream().map(this::windowRow).toList());
            row.put("after", a.stream().map(this::windowRow).toList());
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> windowRow(RainRiskWindow w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("windowStart", w.getWindowStart());
        m.put("windowEnd", w.getWindowEnd());
        m.put("accMmLower", w.getAccMmLower());
        m.put("accMmUpper", w.getAccMmUpper());
        m.put("riskBandLower", w.getRiskBandLower());
        m.put("riskBandUpper", w.getRiskBandUpper());
        m.put("candidateSetNo", w.getCandidateSetNo());
        return m;
    }

    /** 重算参数：沿用该方案最近一次重建的旋钮，窗口换为本次重算窗口。 */
    private RainEventEvaluator.Params paramsForRerun(RainScheme scheme, Instant from, Instant to) {
        return eventRepo.findBySchemeOrderByRunNoDescStartAtAsc(scheme).stream()
                .filter(e -> e.getStatus() != RainEventStatus.SUPERSEDED)
                .findFirst()
                .map(e -> evaluator.paramsFromJson(e.getParamsJson(), from, to, scheme))
                .orElseGet(() -> RainEventEvaluator.defaultParams(from, to, scheme));
    }

    private WatershedMapper.Weights currentWeights() {
        WeightSet current = weightSetRepo.findByStatus("ACTIVE")
                .orElseThrow(() -> ApiException.badRequest("尚无 ACTIVE 权重集"));
        return mapper.parse(current);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> currentAreas(WeightSet current) {
        try {
            Map<String, Object> raw = om.readValue(current.getAreasJson(), Map.class);
            Map<String, Double> areas = new LinkedHashMap<>();
            raw.forEach((k, v) -> areas.put(k, ((Number) v).doubleValue()));
            return areas;
        } catch (Exception e) {
            throw new IllegalStateException("权重集面积解析失败: " + e.getMessage(), e);
        }
    }

    private String nextWeightSetVersion() {
        int max = weightSetRepo.findAll().stream()
                .map(WeightSet::getVersion)
                .filter(v -> v.startsWith("WS-"))
                .mapToInt(v -> {
                    try {
                        return Integer.parseInt(v.substring(3));
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }).max().orElse(0);
        return "WS-" + (max + 1);
    }

    private String writeJson(Object v) {
        try {
            return om.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }
}
