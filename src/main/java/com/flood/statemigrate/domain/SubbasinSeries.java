package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 流域映射器输出：某事件跨度内子流域的 10 分钟分区降雨过程。
 * mmLower 只累计实测贡献（缺测站按 0）；mmUpper 叠加缺测站上界估计；
 * measuredCoverage 为该时段实测站点权重占比（0..1）。缺测时段不以插值冒充实测。
 */
@Entity
@Table(name = "subbasin_series")
public class SubbasinSeries {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RainScheme scheme;

    @Column(nullable = false)
    private long runNo;

    @Column(nullable = false, length = 64)
    private String subbasinCode;

    @Column(nullable = false)
    private Instant bucketStart;

    @Column(nullable = false)
    private double mmLower;

    @Column(nullable = false)
    private double mmUpper;

    @Column(nullable = false)
    private double measuredCoverage;

    @Column(nullable = false, length = 32)
    private String weightSetVersion;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public RainScheme getScheme() { return scheme; }
    public void setScheme(RainScheme scheme) { this.scheme = scheme; }
    public long getRunNo() { return runNo; }
    public void setRunNo(long runNo) { this.runNo = runNo; }
    public String getSubbasinCode() { return subbasinCode; }
    public void setSubbasinCode(String subbasinCode) { this.subbasinCode = subbasinCode; }
    public Instant getBucketStart() { return bucketStart; }
    public void setBucketStart(Instant bucketStart) { this.bucketStart = bucketStart; }
    public double getMmLower() { return mmLower; }
    public void setMmLower(double mmLower) { this.mmLower = mmLower; }
    public double getMmUpper() { return mmUpper; }
    public void setMmUpper(double mmUpper) { this.mmUpper = mmUpper; }
    public double getMeasuredCoverage() { return measuredCoverage; }
    public void setMeasuredCoverage(double measuredCoverage) { this.measuredCoverage = measuredCoverage; }
    public String getWeightSetVersion() { return weightSetVersion; }
    public void setWeightSetVersion(String weightSetVersion) { this.weightSetVersion = weightSetVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
