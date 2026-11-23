package com.flood.statemigrate.service;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 降雨数据集查询：事件列表/详情、两方案对比（含风险归因差异）、子流域降雨过程。
 * 只返回未取代（非 SUPERSEDED）的事件与其风险窗口、过程线。
 */
@Service
public class RainQueryService {

    private final RainEventRepo eventRepo;
    private final RainRiskWindowRepo riskWindowRepo;
    private final SubbasinSeriesRepo seriesRepo;

    public RainQueryService(RainEventRepo eventRepo, RainRiskWindowRepo riskWindowRepo,
                            SubbasinSeriesRepo seriesRepo) {
        this.eventRepo = eventRepo;
        this.riskWindowRepo = riskWindowRepo;
        this.seriesRepo = seriesRepo;
    }

    public List<RainEvent> listEvents(RainScheme scheme, Instant from, Instant to) {
        if (scheme != null && from != null && to != null) {
            return eventRepo.findBySchemeAndStatusNotAndStartAtLessThanAndEndAtGreaterThan(
                    scheme, RainEventStatus.SUPERSEDED, to, from);
        }
        if (scheme != null) {
            return eventRepo.findBySchemeAndStatusNotOrderByStartAtAsc(scheme, RainEventStatus.SUPERSEDED);
        }
        return eventRepo.findByStatusNotAndStartAtLessThanAndEndAtGreaterThan(
                RainEventStatus.SUPERSEDED,
                to != null ? to : Instant.parse("9999-01-01T00:00:00Z"),
                from != null ? from : Instant.EPOCH);
    }

    public Map<String, Object> eventDetail(Long id) {
        RainEvent event = eventRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("降雨事件不存在: " + id));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", event);
        m.put("riskWindows", riskWindowRepo.findByEventId(id));
        m.put("subbasinSeries", seriesRepo.findByEventIdOrderBySubbasinCodeAscBucketStartAsc(id));
        return m;
    }

    /** 子流域降雨过程：窗口内有效事件的 10 分钟分区雨量（上下界与实测覆盖率），可按方案过滤。 */
    public List<SubbasinSeries> subbasinSeries(String subbasinCode, Instant from, Instant to, RainScheme scheme) {
        List<Long> activeEventIds = eventRepo
                .findByStatusNotAndStartAtLessThanAndEndAtGreaterThan(RainEventStatus.SUPERSEDED, to, from)
                .stream().map(RainEvent::getId).toList();
        if (activeEventIds.isEmpty()) return List.of();
        return seriesRepo.findByEventIdIn(activeEventIds).stream()
                .filter(s -> s.getSubbasinCode().equals(subbasinCode))
                .filter(s -> scheme == null || s.getScheme() == scheme)
                .filter(s -> !s.getBucketStart().isBefore(from) && s.getBucketStart().isBefore(to))
                .sorted(java.util.Comparator.comparing(SubbasinSeries::getBucketStart))
                .toList();
    }

    /**
     * 两方案对比：各方案的候选集（事件+风险窗口），以及风险归因差异。
     * 缺站导致边界不可判的方案保留候选区间；已定场方案给出唯一归因。
     */
    public Map<String, Object> compare(Instant from, Instant to) {
        Map<String, Object> schemesJson = new LinkedHashMap<>();
        List<String> schemeSummaries = new ArrayList<>();
        for (RainScheme scheme : RainScheme.values()) {
            List<RainEvent> events = eventRepo
                    .findBySchemeAndStatusNotAndStartAtLessThanAndEndAtGreaterThan(
                            scheme, RainEventStatus.SUPERSEDED, to, from);
            // 按候选集分组（CONFIRMED 事件 candidateSetNo 为 null，归入同一组）
            Map<String, List<RainEvent>> sets = new LinkedHashMap<>();
            for (RainEvent e : events) {
                String key = e.getCandidateSetNo() == null ? "confirmed" : "set-" + e.getCandidateSetNo();
                sets.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            }
            RainEventStatus status = events.stream().anyMatch(e -> e.getStatus() == RainEventStatus.CANDIDATE)
                    ? RainEventStatus.CANDIDATE : RainEventStatus.CONFIRMED;
            List<Map<String, Object>> setRows = new ArrayList<>();
            for (Map.Entry<String, List<RainEvent>> set : sets.entrySet()) {
                List<Map<String, Object>> eventRows = set.getValue().stream().map(e -> {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("id", e.getId());
                    row.put("startAt", e.getStartAt());
                    row.put("endAt", e.getEndAt());
                    row.put("accMmLower", e.getAccMmLower());
                    row.put("accMmUpper", e.getAccMmUpper());
                    row.put("crossesDay", e.isCrossesDay());
                    row.put("openEnded", e.isOpenEnded());
                    row.put("spatialCoverage", e.getSpatialCoverage());
                    row.put("riskWindows", riskWindowRepo.findByEventId(e.getId()).stream()
                            .filter(w -> "ACTIVE".equals(w.getStatus())).toList());
                    return row;
                }).toList();
                Map<String, Object> setRow = new LinkedHashMap<>();
                setRow.put("setNo", set.getValue().get(0).getCandidateSetNo());
                setRow.put("events", eventRows);
                setRows.add(setRow);
            }
            Map<String, Object> schemeJson = new LinkedHashMap<>();
            schemeJson.put("status", status);
            schemeJson.put("candidateSets", setRows);
            schemesJson.put(scheme.name(), schemeJson);
            schemeSummaries.add(scheme.name() + "（" + (status == RainEventStatus.CANDIDATE ? "边界不可判，保留候选" : "已定场")
                    + "）：" + describeSets(sets));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", to);
        result.put("schemes", schemesJson);
        result.put("riskAttributionDiff", String.join("；", schemeSummaries)
                + "。固定间隔方案在土壤已饱和时可能把对产流重要的间歇误判为新场；"
                + "湿润状态方案按前期累计面雨量与流域响应时间判定，间歇不足响应时间则并入同一场。");
        return result;
    }

    private String describeSets(Map<String, List<RainEvent>> sets) {
        if (sets.isEmpty()) return "窗口内无事件";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<RainEvent>> set : sets.entrySet()) {
            StringBuilder sb = new StringBuilder();
            sb.append(set.getKey().equals("confirmed") ? "" : ("候选集" + set.getKey().substring(4) + " "));
            sb.append(set.getValue().size()).append(" 场[");
            List<String> evs = set.getValue().stream().map(e ->
                    e.getStartAt() + "—" + e.getEndAt()
                            + (e.isCrossesDay() ? "（跨日）" : "")
                            + " 累计 " + e.getAccMmLower() + "–" + e.getAccMmUpper() + "mm").toList();
            sb.append(String.join("；", evs)).append("]");
            parts.add(sb.toString().trim());
        }
        return String.join("，", parts);
    }
}
