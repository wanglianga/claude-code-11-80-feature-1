package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.RainSample;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RainSampleRepo extends JpaRepository<RainSample, Long> {
    List<RainSample> findByStationIdAndBucketStartBetweenOrderByBucketStartAsc(Long stationId, Instant from, Instant to);
    List<RainSample> findByBucketStartBetweenOrderByBucketStartAsc(Instant from, Instant to);
    List<RainSample> findByStationIdAndObservedAtGreaterThanEqual(Long stationId, Instant observedAt);
    Optional<RainSample> findByStationIdAndBucketStart(Long stationId, Instant bucketStart);
}
