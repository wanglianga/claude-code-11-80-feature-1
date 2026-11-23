package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 站点 10 分钟雨量采样。
 * observedAt 为站钟原始观测时间；correctedAt 为按写入时校时误差校正后的标准时间；
 * bucketStart 为 correctedAt 向下取整的 10 分钟时段起点（事件求值器按此时段对齐多站采样）。
 * quality=MISSING 表示该时段缺测：mm 为 null，缺测界限器只给上下界，不以插值冒充实测。
 */
@Entity
@Table(name = "rain_sample",
        uniqueConstraints = @UniqueConstraint(columnNames = {"stationId", "bucketStart"}))
public class RainSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long stationId;

    @Column(nullable = false)
    private Instant observedAt;

    @Column(nullable = false)
    private Instant correctedAt;

    @Column(nullable = false)
    private Instant bucketStart;

    /** 10 分钟雨量（mm）；quality=MISSING 时为 null */
    private Double mm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SampleQuality quality;

    /** 写入时使用的校时误差（秒），便于审计与设备更换后比对 */
    @Column(nullable = false)
    private long clockOffsetSecApplied;

    @Column(nullable = false, length = 64)
    private String equipmentId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStationId() { return stationId; }
    public void setStationId(Long stationId) { this.stationId = stationId; }
    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }
    public Instant getCorrectedAt() { return correctedAt; }
    public void setCorrectedAt(Instant correctedAt) { this.correctedAt = correctedAt; }
    public Instant getBucketStart() { return bucketStart; }
    public void setBucketStart(Instant bucketStart) { this.bucketStart = bucketStart; }
    public Double getMm() { return mm; }
    public void setMm(Double mm) { this.mm = mm; }
    public SampleQuality getQuality() { return quality; }
    public void setQuality(SampleQuality quality) { this.quality = quality; }
    public long getClockOffsetSecApplied() { return clockOffsetSecApplied; }
    public void setClockOffsetSecApplied(long v) { this.clockOffsetSecApplied = v; }
    public String getEquipmentId() { return equipmentId; }
    public void setEquipmentId(String equipmentId) { this.equipmentId = equipmentId; }
    public Instant getCreatedAt() { return createdAt; }
}
