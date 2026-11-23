package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 重建的降雨事件（事件求值器输出）。
 * startAt/endAt 为事件起止（首/末实测有雨时段的边界）；accMmLower/accMmUpper 为流域累计雨量上下界
 * （缺测界限器给出，缺测时段下界按 0、上界按该站事件内最大实测时段强度估计，不做插值）；
 * spatialCoverage 为空间覆盖（事件内各时段实测权重覆盖率的均值，0..1）；
 * crossesDay 标记事件是否跨自然日（UTC）；
 * status=CANDIDATE 时 candidateSetNo 标识所属候选方案组，undecidableJson 记录不可判区间原因；
 * openEnded 表示窗口内无雨段尚未达到方案阈值，事件在窗口末端未闭合。
 */
@Entity
@Table(name = "rain_event")
public class RainEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RainScheme scheme;

    /** 重建批次号（同一窗口同一方案每次重建递增） */
    @Column(nullable = false)
    private long runNo;

    @Column(nullable = false)
    private Instant startAt;

    @Column(nullable = false)
    private Instant endAt;

    @Column(nullable = false)
    private double accMmLower;

    @Column(nullable = false)
    private double accMmUpper;

    @Column(nullable = false)
    private double spatialCoverage;

    @Column(nullable = false)
    private boolean crossesDay;

    @Column(nullable = false)
    private boolean openEnded;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RainEventStatus status;

    /** 候选方案组内序号（1..K）；CONFIRMED 事件为 null */
    private Integer candidateSetNo;

    /** 缺测导致边界不可判的说明，如 [{"gapStart":...,"gapEnd":...,"stationCode":"ST-B","missingBuckets":3}] */
    @Column(columnDefinition = "TEXT")
    private String undecidableJson;

    /** 分站累计界限：[{"stationCode","measuredMm","missingBuckets","lowerMm","upperMm"}] */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String stationBoundsJson;

    @Column(nullable = false, length = 32)
    private String weightSetVersion;

    /** 本次重建使用的参数（拆场阈值/响应时间/湿润阈值/风险分档/缺测上限），供版本账重算复用 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String paramsJson;

    @Column(nullable = false)
    private int versionNo;

    /** 被取代时指向新版本事件组（同一批重建的任一事件 id 即可溯源 runNo） */
    private Long supersededByRunNo;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public RainScheme getScheme() { return scheme; }
    public void setScheme(RainScheme scheme) { this.scheme = scheme; }
    public long getRunNo() { return runNo; }
    public void setRunNo(long runNo) { this.runNo = runNo; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public double getAccMmLower() { return accMmLower; }
    public void setAccMmLower(double accMmLower) { this.accMmLower = accMmLower; }
    public double getAccMmUpper() { return accMmUpper; }
    public void setAccMmUpper(double accMmUpper) { this.accMmUpper = accMmUpper; }
    public double getSpatialCoverage() { return spatialCoverage; }
    public void setSpatialCoverage(double spatialCoverage) { this.spatialCoverage = spatialCoverage; }
    public boolean isCrossesDay() { return crossesDay; }
    public void setCrossesDay(boolean crossesDay) { this.crossesDay = crossesDay; }
    public boolean isOpenEnded() { return openEnded; }
    public void setOpenEnded(boolean openEnded) { this.openEnded = openEnded; }
    public RainEventStatus getStatus() { return status; }
    public void setStatus(RainEventStatus status) { this.status = status; }
    public Integer getCandidateSetNo() { return candidateSetNo; }
    public void setCandidateSetNo(Integer candidateSetNo) { this.candidateSetNo = candidateSetNo; }
    public String getUndecidableJson() { return undecidableJson; }
    public void setUndecidableJson(String undecidableJson) { this.undecidableJson = undecidableJson; }
    public String getStationBoundsJson() { return stationBoundsJson; }
    public void setStationBoundsJson(String stationBoundsJson) { this.stationBoundsJson = stationBoundsJson; }
    public String getWeightSetVersion() { return weightSetVersion; }
    public void setWeightSetVersion(String weightSetVersion) { this.weightSetVersion = weightSetVersion; }
    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }
    public Long getSupersededByRunNo() { return supersededByRunNo; }
    public void setSupersededByRunNo(Long supersededByRunNo) { this.supersededByRunNo = supersededByRunNo; }
    public Instant getCreatedAt() { return createdAt; }
}
