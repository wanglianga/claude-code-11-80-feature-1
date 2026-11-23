package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 事件版本账：站点增补、设备更换、权重变动对已计算风险窗口的影响。
 * detailJson 记录变更内容（新站点/新设备/新权重集版本）；
 * impactedJson 记录受影响风险窗口的前后对照：
 * [{"scheme","windowStart","windowEnd","before":{"events","accMmLower","accMmUpper","riskBand"},
 *   "after":{...},"note"}]。
 */
@Entity
@Table(name = "rain_ledger_entry")
public class RainLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RainChangeKind changeType;

    /** 变更生效时间（设备更换/站点增补自该时刻起影响采样与重建） */
    @Column(nullable = false)
    private Instant effectiveAt;

    /** 本次变更触发的重算窗口 [reprocessFrom, reprocessTo) */
    @Column(nullable = false)
    private Instant reprocessFrom;

    @Column(nullable = false)
    private Instant reprocessTo;

    @Column(nullable = false, length = 256)
    private String summary;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String detailJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String impactedJson;

    /** 权重变动时的新权重集版本，其余变更为 null */
    @Column(length = 32)
    private String newWeightSetVersion;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public RainChangeKind getChangeType() { return changeType; }
    public void setChangeType(RainChangeKind changeType) { this.changeType = changeType; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant effectiveAt) { this.effectiveAt = effectiveAt; }
    public Instant getReprocessFrom() { return reprocessFrom; }
    public void setReprocessFrom(Instant reprocessFrom) { this.reprocessFrom = reprocessFrom; }
    public Instant getReprocessTo() { return reprocessTo; }
    public void setReprocessTo(Instant reprocessTo) { this.reprocessTo = reprocessTo; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public String getImpactedJson() { return impactedJson; }
    public void setImpactedJson(String impactedJson) { this.impactedJson = impactedJson; }
    public String getNewWeightSetVersion() { return newWeightSetVersion; }
    public void setNewWeightSetVersion(String newWeightSetVersion) { this.newWeightSetVersion = newWeightSetVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
