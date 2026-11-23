package com.flood.statemigrate.service;

import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 事件求值器：把站点采样按校时误差对齐到标准 10 分钟时段，重建降雨事件，
 * 输出开始/结束、累计量上下界与空间覆盖。
 * 事件边界两方案：
 *   FIXED_GAP —— 固定无雨间隔合并：连续无雨 ≥ gapMinutes 即拆场；
 *   ADAPTIVE_WETNESS —— 前期湿润状态（事件已累计面雨量 ≥ wetnessThresholdMm 视为土壤饱和）
 *   与流域响应时间（responseMinutes）估计边界：饱和时间歇须 ≥ 响应时间才拆场。
 * 缺测时段不按无雨处理：缺站导致边界不可判时保留候选区间（status=CANDIDATE），不强行定场。
 */
@Service
public class RainEventEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RainEventEvaluator.class);

    public static final int DEFAULT_GAP_MINUTES = 30;
    public static final int DEFAULT_RESPONSE_MINUTES = 45;
    public static final double DEFAULT_WETNESS_THRESHOLD_MM = 12.0;
    public static final double DEFAULT_RISK_MEDIUM_MM = 15.0;
    public static final double DEFAULT_RISK_HIGH_MM = 25.0;
    public static final double DEFAULT_MISSING_CEILING_MM = 10.0;

    public static Params defaultParams(Instant from, Instant to, RainScheme scheme) {
        return new Params(from, to, scheme, DEFAULT_GAP_MINUTES, DEFAULT_RESPONSE_MINUTES,
                DEFAULT_WETNESS_THRESHOLD_MM, DEFAULT_RISK_MEDIUM_MM, DEFAULT_RISK_HIGH_MM,
                DEFAULT_MISSING_CEILING_MM);
    }

    /** 重建参数。 */
    public record Params(Instant from, Instant to, RainScheme scheme,
                         int gapMinutes, int responseMinutes, double wetnessThresholdMm,
                         double riskMediumMm, double riskHighMm, double missingCeilingMm) {
        int gapBuckets() { return (int) Math.ceil(gapMinutes / 10.0); }
        int responseBuckets() { return (int) Math.ceil(responseMinutes / 10.0); }

        /** 除窗口外的旋钮，供版本账重算复用。 */
        public Params withWindow(Instant newFrom, Instant newTo) {
            return new Params(newFrom, newTo, scheme, gapMinutes, responseMinutes,
                    wetnessThresholdMm, riskMediumMm, riskHighMm, missingCeilingMm);
        }
    }

    /** 事件跨度（栅格时段下标，闭区间）。 */
    private record EventSpan(int startIdx, int endIdx, boolean openEnded) {}

    /** 两场之间的无雨段。 */
    private record Gap(int startIdx, int length, int dryCount, int unknownCount,
                       List<String> missingStations) {
        boolean uncertain() { return unknownCount > 0; }
    }

    private record Outcome(List<EventSpan> spans, boolean[] hypothesis) {}

    private final RainStationRepo stationRepo;
    private final RainSampleRepo sampleRepo;
    private final RainEventRepo eventRepo;
    private final RainRiskWindowRepo riskWindowRepo;
    private final SubbasinSeriesRepo seriesRepo;
    private final WeightSetRepo weightSetRepo;
    private final WatershedMapper mapper;
    private final MissingDataBounder bounder;
    private final ObjectMapper om;

    public RainEventEvaluator(RainStationRepo stationRepo, RainSampleRepo sampleRepo,
                              RainEventRepo eventRepo, RainRiskWindowRepo riskWindowRepo,
                              SubbasinSeriesRepo seriesRepo, WeightSetRepo weightSetRepo,
                              WatershedMapper mapper, MissingDataBounder bounder, ObjectMapper om) {
        this.stationRepo = stationRepo;
        this.sampleRepo = sampleRepo;
        this.eventRepo = eventRepo;
        this.riskWindowRepo = riskWindowRepo;
        this.seriesRepo = seriesRepo;
        this.weightSetRepo = weightSetRepo;
        this.mapper = mapper;
        this.bounder = bounder;
        this.om = om;
    }

    @Transactional
    public Map<String, Object> reconstruct(Params p) {
        if (!p.from().isBefore(p.to())) {
            throw ApiException.badRequest("重建窗口 from 必须早于 to");
        }
        if (p.riskMediumMm() >= p.riskHighMm()) {
            throw ApiException.badRequest("riskMediumMm 必须小于 riskHighMm");
        }
        // 采样按标准 10 分钟时段落桶，重建窗口对齐到时段边界
        Instant alignedFrom = RainStationService.bucketOf(p.from());
        Instant alignedTo = RainStationService.bucketOf(p.to().minusSeconds(1)).plusSeconds(600);
        p = p.withWindow(alignedFrom, alignedTo);
        WeightSet ws = weightSetRepo.findByStatus("ACTIVE")
                .orElseThrow(() -> ApiException.badRequest("尚无 ACTIVE 权重集，请先登记"));
        WatershedMapper.Weights weights = mapper.parse(ws);
        if (weights.stationCodes().isEmpty()) {
            throw ApiException.badRequest("权重集无站点映射");
        }
        Map<String, RainStation> stations = stationRepo.findAll().stream()
                .collect(Collectors.toMap(RainStation::getStationCode, Function.identity()));
        for (String code : weights.stationCodes()) {
            if (!stations.containsKey(code)) {
                throw ApiException.badRequest("权重集引用了未登记的站点: " + code);
            }
        }

        // 校时对齐后的采样按 10 分钟时段入栅格（采样落库时已按写入时校时误差校正）
        List<RainSample> samples = sampleRepo.findByBucketStartBetweenOrderByBucketStartAsc(
                p.from(), p.to().minusSeconds(1));
        Map<String, List<RainSample>> byStation = new LinkedHashMap<>();
        Map<Long, String> codeById = stations.values().stream()
                .collect(Collectors.toMap(RainStation::getId, RainStation::getStationCode));
        for (RainSample s : samples) {
            String code = codeById.get(s.getStationId());
            if (code != null && weights.stationCodes().contains(code)) {
                byStation.computeIfAbsent(code, k -> new ArrayList<>()).add(s);
            }
        }
        RainGrid grid = RainGrid.build(p.from(), p.to(), weights.stationCodes(), byStation);
        Map<String, Double> basinW = weights.basinWeights();
        Map<String, Double> windowMax = bounder.maxMeasured(grid.cells(), grid.stationCodes());

        List<RainGrid.BucketState> states = grid.cells().stream().map(RainGrid.Cell::state).toList();
        List<Gap> gaps = findGaps(grid, states);
        List<Integer> uncertain = new ArrayList<>();
        for (int g = 0; g < gaps.size(); g++) {
            if (gaps.get(g).uncertain()) uncertain.add(g);
        }
        List<boolean[]> hypotheses = enumerateHypotheses(uncertain.size());

        // 每个假设组合解出事件划分；划分相同者合并
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        for (boolean[] hyp : hypotheses) {
            RainGrid.BucketState[] resolved = resolve(states, gaps, uncertain, hyp);
            List<EventSpan> spans = scan(resolved, states, grid, basinW, windowMax, p);
            outcomes.computeIfAbsent(signature(spans), k -> new Outcome(spans, hyp));
        }
        boolean decidable = outcomes.size() == 1;
        List<Outcome> ordered = outcomes.values().stream()
                .sorted(Comparator.<Outcome>comparingInt(o -> -o.spans().size())
                        .thenComparing(o -> o.spans().isEmpty() ? Instant.MAX
                                : grid.cells().get(o.spans().get(0).startIdx()).bucketStart()))
                .toList();

        long runNo = eventRepo.findBySchemeOrderByRunNoDescStartAtAsc(p.scheme()).stream()
                .mapToLong(RainEvent::getRunNo).max().orElse(0) + 1;
        supersedeOverlapping(p.scheme(), p.from(), p.to(), runNo);

        RainEventStatus status = decidable ? RainEventStatus.CONFIRMED : RainEventStatus.CANDIDATE;
        String undecidableJson = decidable ? null : undecidableJson(grid, gaps, uncertain);
        List<Map<String, Object>> setSummaries = new ArrayList<>();
        int setNo = 0;
        int eventCount = 0;
        for (Outcome outcome : ordered) {
            Integer candidateSetNo = decidable ? null : ++setNo;
            List<Map<String, Object>> eventRows = new ArrayList<>();
            for (EventSpan span : outcome.spans()) {
                RainEvent event = persistEvent(p, grid, weights, ws, basinW, windowMax,
                        span, runNo, status, candidateSetNo, undecidableJson);
                eventRows.add(eventRow(event));
                eventCount++;
            }
            Map<String, Object> setRow = new LinkedHashMap<>();
            setRow.put("setNo", candidateSetNo);
            setRow.put("events", eventRows);
            setSummaries.add(setRow);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scheme", p.scheme());
        result.put("runNo", runNo);
        result.put("status", status);
        result.put("windowFrom", p.from());
        result.put("windowTo", p.to());
        result.put("weightSetVersion", ws.getVersion());
        result.put("eventCount", eventCount);
        result.put("candidateSets", setSummaries);
        if (undecidableJson != null) {
            result.put("undecidable", readJson(undecidableJson));
        }
        if (uncertain.size() > 4) {
            result.put("hypothesisNote", "不可判无雨段超过 4 个，仅评估全干/全湿两种极端假设");
        }
        return result;
    }

    /** 查询某方案当前有效（未取代）的事件。 */
    public List<RainEvent> activeEvents(RainScheme scheme, Instant from, Instant to) {
        return eventRepo.findBySchemeAndStatusNotAndStartAtLessThanAndEndAtGreaterThan(
                scheme, RainEventStatus.SUPERSEDED, to, from);
    }

    // ---------- 内部逻辑 ----------

    /** 找出首末实测有雨时段之间的无雨段（可能含缺测时段）。 */
    private List<Gap> findGaps(RainGrid grid, List<RainGrid.BucketState> states) {
        List<Gap> gaps = new ArrayList<>();
        int n = states.size();
        int firstWet = -1, lastWet = -1;
        for (int i = 0; i < n; i++) {
            if (states.get(i) == RainGrid.BucketState.WET) {
                if (firstWet < 0) firstWet = i;
                lastWet = i;
            }
        }
        if (firstWet < 0) return gaps;
        int i = firstWet;
        while (i <= lastWet) {
            if (states.get(i) == RainGrid.BucketState.WET) { i++; continue; }
            int start = i;
            int dry = 0, unknown = 0;
            List<String> missingStations = new ArrayList<>();
            while (i <= lastWet && states.get(i) != RainGrid.BucketState.WET) {
                if (states.get(i) == RainGrid.BucketState.DRY) dry++; else unknown++;
                RainGrid.Cell c = grid.cells().get(i);
                for (String st : c.missing()) {
                    if (!missingStations.contains(st)) missingStations.add(st);
                }
                i++;
            }
            gaps.add(new Gap(start, i - start, dry, unknown, missingStations));
        }
        return gaps;
    }

    /** 不可判无雨段的假设组合：true=该段缺测时段按无雨处理，false=按有雨处理。 */
    private List<boolean[]> enumerateHypotheses(int uncertainCount) {
        if (uncertainCount == 0) {
            return List.of(new boolean[0]);
        }
        if (uncertainCount > 4) {
            log.warn("不可判无雨段 {} 个，仅评估全干/全湿两种极端假设", uncertainCount);
            return List.of(fullArray(uncertainCount, true), fullArray(uncertainCount, false));
        }
        List<boolean[]> all = new ArrayList<>();
        for (int mask = 0; mask < (1 << uncertainCount); mask++) {
            boolean[] hyp = new boolean[uncertainCount];
            for (int b = 0; b < uncertainCount; b++) {
                hyp[b] = (mask & (1 << b)) != 0;
            }
            all.add(hyp);
        }
        return all;
    }

    private boolean[] fullArray(int n, boolean v) {
        boolean[] a = new boolean[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    /** 按假设组合把缺测时段解析为有雨/无雨。 */
    private RainGrid.BucketState[] resolve(List<RainGrid.BucketState> states, List<Gap> gaps,
                                           List<Integer> uncertain, boolean[] hyp) {
        RainGrid.BucketState[] resolved = states.toArray(new RainGrid.BucketState[0]);
        for (int g = 0; g < uncertain.size(); g++) {
            Gap gap = gaps.get(uncertain.get(g));
            RainGrid.BucketState fill = hyp[g] ? RainGrid.BucketState.DRY : RainGrid.BucketState.WET;
            for (int i = gap.startIdx(); i < gap.startIdx() + gap.length(); i++) {
                if (resolved[i] == RainGrid.BucketState.UNKNOWN) resolved[i] = fill;
            }
        }
        return resolved;
    }

    /** 在解析后的状态序列上扫描事件：连续无雨段长度 ≥ 阈值 T 即拆场。 */
    private List<EventSpan> scan(RainGrid.BucketState[] resolved, List<RainGrid.BucketState> original,
                                 RainGrid grid, Map<String, Double> basinW,
                                 Map<String, Double> windowMax, Params p) {
        int n = resolved.length;
        List<EventSpan> spans = new ArrayList<>();
        int eventStart = -1, lastWet = -1, dryStart = -1;
        for (int i = 0; i < n; i++) {
            if (resolved[i] == RainGrid.BucketState.WET) {
                if (eventStart < 0) {
                    eventStart = i;
                } else if (dryStart >= 0) {
                    int t = threshold(eventStart, dryStart, resolved, original, grid, basinW, windowMax, p);
                    if (i - dryStart >= t) {
                        spans.add(new EventSpan(eventStart, lastWet, false));
                        eventStart = i;
                    }
                    dryStart = -1;
                }
                lastWet = i;
            } else if (eventStart >= 0 && dryStart < 0) {
                dryStart = i;
            }
        }
        if (eventStart >= 0) {
            boolean open = false;
            if (dryStart >= 0) {
                int t = threshold(eventStart, dryStart, resolved, original, grid, basinW, windowMax, p);
                // 窗口末段：缺测时段不算“确认无雨”，确认无雨长度不足阈值则事件未闭合
                int confirmedDry = 0;
                for (int k = dryStart; k < n; k++) {
                    if (resolved[k] == RainGrid.BucketState.DRY) confirmedDry++;
                }
                open = confirmedDry < t;
            }
            spans.add(new EventSpan(eventStart, lastWet, open));
        }
        return spans;
    }

    /**
     * 拆场阈值（时段数）：FIXED_GAP 恒为 gapMinutes/10；
     * ADAPTIVE_WETNESS 按无雨段起点处的事件前期累计面雨量判定土壤是否饱和，
     * 饱和时须连续无雨 ≥ responseMinutes 才拆场（流域仍在退水响应，间歇不产生新场）。
     */
    private int threshold(int eventStart, int dryStart, RainGrid.BucketState[] resolved,
                          List<RainGrid.BucketState> original, RainGrid grid,
                          Map<String, Double> basinW, Map<String, Double> windowMax, Params p) {
        if (p.scheme() == RainScheme.FIXED_GAP) {
            return p.gapBuckets();
        }
        double antecedent = 0.0;
        for (int k = eventStart; k < dryStart; k++) {
            RainGrid.Cell c = grid.cells().get(k);
            antecedent += basinMeasured(c, basinW);
            if (original.get(k) == RainGrid.BucketState.UNKNOWN
                    && resolved[k] == RainGrid.BucketState.WET) {
                antecedent += basinMissingUpper(c, basinW, windowMax, null, p.missingCeilingMm());
            }
        }
        boolean saturated = antecedent >= p.wetnessThresholdMm();
        return saturated ? p.responseBuckets() : p.gapBuckets();
    }

    private double basinMeasured(RainGrid.Cell c, Map<String, Double> basinW) {
        double v = 0.0;
        for (Map.Entry<String, Double> e : c.measured().entrySet()) {
            v += basinW.getOrDefault(e.getKey(), 0.0) * e.getValue();
        }
        return v;
    }

    /** 时段内缺测站的流域权重上界贡献（饱和度判定用窗口最大实测估计）。 */
    private double basinMissingUpper(RainGrid.Cell c, Map<String, Double> basinW,
                                     Map<String, Double> windowMax,
                                     Map<String, Double> eventMax, double ceiling) {
        double v = 0.0;
        for (String st : c.missing()) {
            Double em = eventMax == null ? null : eventMax.get(st);
            v += basinW.getOrDefault(st, 0.0)
                    * bounder.estimateUpper(c, em, windowMax.get(st), ceiling);
        }
        return v;
    }

    private String signature(List<EventSpan> spans) {
        return spans.stream().map(s -> s.startIdx() + ":" + s.endIdx())
                .collect(Collectors.joining(","));
    }

    /** 持久化一个事件及其风险窗口与子流域降雨过程。 */
    private RainEvent persistEvent(Params p, RainGrid grid, WatershedMapper.Weights weights,
                                   WeightSet ws, Map<String, Double> basinW,
                                   Map<String, Double> windowMax, EventSpan span, long runNo,
                                   RainEventStatus status, Integer candidateSetNo,
                                   String undecidableJson) {
        List<RainGrid.Cell> spanCells = grid.cells().subList(span.startIdx(), span.endIdx() + 1);
        Map<String, Double> eventMax = bounder.maxMeasured(spanCells, grid.stationCodes());

        List<MissingDataBounder.StationBounds> stationBounds = grid.stationCodes().stream()
                .map(st -> bounder.bounds(st, spanCells, windowMax.get(st), p.missingCeilingMm()))
                .toList();
        double accLower = 0.0, accUpper = 0.0;
        for (MissingDataBounder.StationBounds b : stationBounds) {
            double w = basinW.getOrDefault(b.stationCode(), 0.0);
            accLower += w * b.lowerMm();
            accUpper += w * b.upperMm();
        }
        double coverage = 0.0;
        for (RainGrid.Cell c : spanCells) {
            double cov = 0.0;
            for (String st : c.measured().keySet()) cov += basinW.getOrDefault(st, 0.0);
            coverage += cov;
        }
        coverage = spanCells.isEmpty() ? 0.0 : coverage / spanCells.size();

        Instant startAt = spanCells.get(0).bucketStart();
        Instant endAt = spanCells.get(spanCells.size() - 1).bucketStart().plusSeconds(600);
        boolean crossesDay = !startAt.atZone(ZoneOffset.UTC).toLocalDate()
                .equals(endAt.minusSeconds(1).atZone(ZoneOffset.UTC).toLocalDate());

        RainEvent event = new RainEvent();
        event.setScheme(p.scheme());
        event.setRunNo(runNo);
        event.setStartAt(startAt);
        event.setEndAt(endAt);
        event.setAccMmLower(MissingDataBounder.round3(accLower));
        event.setAccMmUpper(MissingDataBounder.round3(accUpper));
        event.setSpatialCoverage(MissingDataBounder.round3(coverage));
        event.setCrossesDay(crossesDay);
        event.setOpenEnded(span.openEnded());
        event.setStatus(status);
        event.setCandidateSetNo(candidateSetNo);
        event.setUndecidableJson(undecidableJson);
        event.setStationBoundsJson(writeJson(MissingDataBounder.toJsonList(stationBounds)));
        event.setWeightSetVersion(ws.getVersion());
        event.setParamsJson(writeJson(paramsMap(p)));
        event.setVersionNo(1);
        eventRepo.save(event);

        RainRiskWindow window = new RainRiskWindow();
        window.setScheme(p.scheme());
        window.setRunNo(runNo);
        window.setEventId(event.getId());
        window.setCandidateSetNo(candidateSetNo);
        window.setWindowStart(startAt);
        window.setWindowEnd(endAt);
        window.setAccMmLower(event.getAccMmLower());
        window.setAccMmUpper(event.getAccMmUpper());
        window.setRiskBandLower(band(accLower, p));
        window.setRiskBandUpper(band(accUpper, p));
        window.setStatus("ACTIVE");
        riskWindowRepo.save(window);

        persistSeries(p, ws, weights, spanCells, eventMax, windowMax, event.getId(), runNo);
        return event;
    }

    /** 流域映射器输出：事件跨度内各子流域 10 分钟降雨过程（上下界与实测覆盖率）。 */
    private void persistSeries(Params p, WeightSet ws, WatershedMapper.Weights weights,
                               List<RainGrid.Cell> spanCells, Map<String, Double> eventMax,
                               Map<String, Double> windowMax, Long eventId, long runNo) {
        for (String sb : weights.bySubbasin().keySet()) {
            for (RainGrid.Cell c : spanCells) {
                double lower = 0.0, upperMissing = 0.0, cov = 0.0;
                for (Map.Entry<String, Double> e : c.measured().entrySet()) {
                    double w = weights.weight(sb, e.getKey());
                    lower += w * e.getValue();
                    cov += w;
                }
                for (String st : c.missing()) {
                    upperMissing += weights.weight(sb, st) * bounder.estimateUpper(
                            c, eventMax.get(st), windowMax.get(st), p.missingCeilingMm());
                }
                SubbasinSeries row = new SubbasinSeries();
                row.setEventId(eventId);
                row.setScheme(p.scheme());
                row.setRunNo(runNo);
                row.setSubbasinCode(sb);
                row.setBucketStart(c.bucketStart());
                row.setMmLower(MissingDataBounder.round3(lower));
                row.setMmUpper(MissingDataBounder.round3(lower + upperMissing));
                row.setMeasuredCoverage(MissingDataBounder.round3(cov));
                row.setWeightSetVersion(ws.getVersion());
                seriesRepo.save(row);
            }
        }
    }

    private RiskLevel band(double accMm, Params p) {
        if (accMm >= p.riskHighMm()) return RiskLevel.HIGH;
        if (accMm >= p.riskMediumMm()) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    /** 重算时把窗口内未取代的旧事件置为 SUPERSEDED，其风险窗口一并失效。 */
    private void supersedeOverlapping(RainScheme scheme, Instant from, Instant to, long runNo) {
        List<RainEvent> old = eventRepo.findBySchemeAndStatusNotAndStartAtLessThanAndEndAtGreaterThan(
                scheme, RainEventStatus.SUPERSEDED, to, from);
        for (RainEvent e : old) {
            e.setStatus(RainEventStatus.SUPERSEDED);
            e.setSupersededByRunNo(runNo);
            eventRepo.save(e);
            for (RainRiskWindow w : riskWindowRepo.findByEventId(e.getId())) {
                w.setStatus("SUPERSEDED");
                riskWindowRepo.save(w);
            }
        }
    }

    private String undecidableJson(RainGrid grid, List<Gap> gaps, List<Integer> uncertain) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int idx : uncertain) {
            Gap g = gaps.get(idx);
            Instant gapStart = grid.cells().get(g.startIdx()).bucketStart();
            Instant gapEnd = grid.cells().get(g.startIdx() + g.length() - 1).bucketStart().plusSeconds(600);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gapStart", gapStart.toString());
            m.put("gapEnd", gapEnd.toString());
            m.put("gapLengthMinutes", g.length() * 10);
            m.put("missingStations", g.missingStations());
            m.put("missingBuckets", g.unknownCount());
            m.put("reason", "该段缺测，无法确认是否降雨：若实际无雨且达到拆场阈值则分为两场，若有雨则合并。"
                    + "保留候选区间，不强行定场。");
            list.add(m);
        }
        return writeJson(list);
    }

    private Map<String, Object> eventRow(RainEvent e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.getId());
        row.put("startAt", e.getStartAt());
        row.put("endAt", e.getEndAt());
        row.put("accMmLower", e.getAccMmLower());
        row.put("accMmUpper", e.getAccMmUpper());
        row.put("spatialCoverage", e.getSpatialCoverage());
        row.put("crossesDay", e.isCrossesDay());
        row.put("openEnded", e.isOpenEnded());
        row.put("candidateSetNo", e.getCandidateSetNo());
        return row;
    }

    private Map<String, Object> paramsMap(Params p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gapMinutes", p.gapMinutes());
        m.put("responseMinutes", p.responseMinutes());
        m.put("wetnessThresholdMm", p.wetnessThresholdMm());
        m.put("riskMediumMm", p.riskMediumMm());
        m.put("riskHighMm", p.riskHighMm());
        m.put("missingCeilingMm", p.missingCeilingMm());
        return m;
    }

    /** 解析事件落库的重建参数（版本账重算时复用同一组旋钮）。 */
    @SuppressWarnings("unchecked")
    public Params paramsFromJson(String json, Instant from, Instant to, RainScheme scheme) {
        try {
            Map<String, Object> m = om.readValue(json, Map.class);
            return new Params(from, to, scheme,
                    ((Number) m.get("gapMinutes")).intValue(),
                    ((Number) m.get("responseMinutes")).intValue(),
                    ((Number) m.get("wetnessThresholdMm")).doubleValue(),
                    ((Number) m.get("riskMediumMm")).doubleValue(),
                    ((Number) m.get("riskHighMm")).doubleValue(),
                    ((Number) m.get("missingCeilingMm")).doubleValue());
        } catch (Exception e) {
            throw new IllegalStateException("重建参数解析失败: " + e.getMessage(), e);
        }
    }

    private String writeJson(Object v) {
        try {
            return om.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    private Object readJson(String json) {
        try {
            return om.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
