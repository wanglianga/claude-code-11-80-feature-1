package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 站点→子流域权重集（版本化）。weightsJson 形如：
 * [{"stationCode":"ST-A","subbasinCode":"SB-1","weight":0.7},
 *  {"stationCode":"ST-B","subbasinCode":"SB-1","weight":0.3},
 *  {"stationCode":"ST-A","subbasinCode":"SB-2","weight":0.2},
 *  {"stationCode":"ST-B","subbasinCode":"SB-2","weight":0.8}]
 * areasJson 记录子流域面积（km²），用于流域面雨量：{"SB-1":5.2,"SB-2":3.8}。
 * 每个子流域的站点权重之和须为 1。权重变动产生新版本并使旧版本 SUPERSEDED。
 */
@Entity
@Table(name = "rain_weight_set")
public class WeightSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String version;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String weightsJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String areasJson;

    /** ACTIVE / SUPERSEDED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getWeightsJson() { return weightsJson; }
    public void setWeightsJson(String weightsJson) { this.weightsJson = weightsJson; }
    public String getAreasJson() { return areasJson; }
    public void setAreasJson(String areasJson) { this.areasJson = areasJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
