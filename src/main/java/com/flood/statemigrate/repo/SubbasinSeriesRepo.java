package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.SubbasinSeries;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SubbasinSeriesRepo extends JpaRepository<SubbasinSeries, Long> {
    List<SubbasinSeries> findBySubbasinCodeAndBucketStartBetweenOrderByBucketStartAsc(
            String subbasinCode, Instant from, Instant to);
    List<SubbasinSeries> findByEventIdOrderBySubbasinCodeAscBucketStartAsc(Long eventId);
    List<SubbasinSeries> findByEventIdIn(List<Long> eventIds);
}
