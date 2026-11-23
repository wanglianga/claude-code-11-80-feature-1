package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 雨量站点登记：站码、名称、当前校时误差（站钟比标准时快的秒数，可为负）、当前设备编号。
 * 校时误差用于事件求值器把站点采样对齐到标准 10 分钟时段：correctedAt = observedAt - clockOffsetSec。
 * 设备更换会改变校时误差，历史采样已按当时误差校正并落库，不受后续更换影响。
 */
@Entity
@Table(name = "rain_station")
public class RainStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String stationCode;

    @Column(nullable = false, length = 128)
    private String name;

    /** 站钟比标准时快的秒数（负数为慢）；correctedAt = observedAt - clockOffsetSec */
    @Column(nullable = false)
    private long clockOffsetSec;

    @Column(nullable = false, length = 64)
    private String equipmentId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStationCode() { return stationCode; }
    public void setStationCode(String stationCode) { this.stationCode = stationCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getClockOffsetSec() { return clockOffsetSec; }
    public void setClockOffsetSec(long clockOffsetSec) { this.clockOffsetSec = clockOffsetSec; }
    public String getEquipmentId() { return equipmentId; }
    public void setEquipmentId(String equipmentId) { this.equipmentId = equipmentId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
