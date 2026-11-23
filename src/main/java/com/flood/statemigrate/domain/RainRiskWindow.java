package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 由降雨事件计算的风险窗口（流域面雨量累计定级）。
 * 窗口即事件跨度；accMmLower/accMmUpper 为流域累计上下界；
 * riskBandLower/riskBandUpper 分别按下界/上界累计定级（LOW/MEDIUM/HIGH），
 * 上下界跨档时两级并存，不强行取单值。版本账记录变更前后风险窗口的差异。
 */
@Entity
@Table(name = "rain_risk_window")
public class RainRiskWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RainScheme scheme;

    @Column(nullable = false)
    private long runNo;

    @Column(nullable = false)
    private Long eventId;

    /** 候选组序号（事件为 CONFIRMED 时为 null） */
    private Integer candidateSetNo;

    @Column(nullable = false)
    private Instant windowStart;

    @Column(nullable = false)
    private Instant windowEnd;

    @Column(nullable = false)
    private double accMmLower;

    @Column(nullable = false)
    private double accMmUpper;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private RiskLevel riskBandLower;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private RiskLevel riskBandUpper;

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
    public RainScheme getScheme() { return scheme; }
    public void setScheme(RainScheme scheme) { this.scheme = scheme; }
    public long getRunNo() { return runNo; }
    public void setRunNo(long runNo) { this.runNo = runNo; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Integer getCandidateSetNo() { return candidateSetNo; }
    public void setCandidateSetNo(Integer candidateSetNo) { this.candidateSetNo = candidateSetNo; }
    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }
    public double getAccMmLower() { return accMmLower; }
    public void setAccMmLower(double accMmLower) { this.accMmLower = accMmLower; }
    public double getAccMmUpper() { return accMmUpper; }
    public void setAccMmUpper(double accMmUpper) { this.accMmUpper = accMmUpper; }
    public RiskLevel getRiskBandLower() { return riskBandLower; }
    public void setRiskBandLower(RiskLevel riskBandLower) { this.riskBandLower = riskBandLower; }
    public RiskLevel getRiskBandUpper() { return riskBandUpper; }
    public void setRiskBandUpper(RiskLevel riskBandUpper) { this.riskBandUpper = riskBandUpper; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
