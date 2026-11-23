package com.flood.statemigrate.service;

import com.flood.statemigrate.domain.RainSample;
import com.flood.statemigrate.domain.RainStation;
import com.flood.statemigrate.domain.SampleQuality;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.RainSampleRepo;
import com.flood.statemigrate.repo.RainStationRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 雨量站点与采样登记。采样写入时按站点当前校时误差对齐：
 * correctedAt = observedAt - clockOffsetSec，bucketStart 为 correctedAt 向下取整的 10 分钟时段。
 * 同一 (站点, 时段) 重复写入视为订正，覆盖旧值。
 */
@Service
public class RainStationService {

    private final RainStationRepo stationRepo;
    private final RainSampleRepo sampleRepo;

    public RainStationService(RainStationRepo stationRepo, RainSampleRepo sampleRepo) {
        this.stationRepo = stationRepo;
        this.sampleRepo = sampleRepo;
    }

    @Transactional
    public RainStation register(RainStation station) {
        stationRepo.findByStationCode(station.getStationCode()).ifPresent(s -> {
            throw ApiException.conflict("站码已存在: " + station.getStationCode());
        });
        return stationRepo.save(station);
    }

    public RainStation get(Long id) {
        return stationRepo.findById(id).orElseThrow(() -> ApiException.notFound("雨量站不存在: " + id));
    }

    public RainStation getByCode(String code) {
        return stationRepo.findByStationCode(code)
                .orElseThrow(() -> ApiException.notFound("雨量站不存在: " + code));
    }

    public List<RainStation> list() {
        return stationRepo.findAll();
    }

    /** 标准 10 分钟时段起点。 */
    public static Instant bucketOf(Instant t) {
        return Instant.ofEpochSecond(Math.floorDiv(t.getEpochSecond(), 600) * 600);
    }

    @Transactional
    public RainSample addSample(RainStation station, Instant observedAt, Double mm, SampleQuality quality) {
        if (quality == SampleQuality.MEASURED) {
            if (mm == null || mm < 0) {
                throw ApiException.badRequest("MEASURED 采样必须给出非负 mm");
            }
        } else if (mm != null) {
            throw ApiException.badRequest("MISSING 采样不应携带 mm（缺测不以插值冒充实测）");
        }
        Instant correctedAt = observedAt.minusSeconds(station.getClockOffsetSec());
        Instant bucket = bucketOf(correctedAt);
        RainSample sample = sampleRepo.findByStationIdAndBucketStart(station.getId(), bucket)
                .orElseGet(RainSample::new);
        sample.setStationId(station.getId());
        sample.setObservedAt(observedAt);
        sample.setCorrectedAt(correctedAt);
        sample.setBucketStart(bucket);
        sample.setMm(mm);
        sample.setQuality(quality);
        sample.setClockOffsetSecApplied(station.getClockOffsetSec());
        sample.setEquipmentId(station.getEquipmentId());
        return sampleRepo.save(sample);
    }

    public List<RainSample> samples(Long stationId, Instant from, Instant to) {
        return sampleRepo.findByStationIdAndBucketStartBetweenOrderByBucketStartAsc(
                stationId, from, to.minusSeconds(1));
    }
}
