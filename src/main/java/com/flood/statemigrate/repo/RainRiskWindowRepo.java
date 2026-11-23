package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.RainRiskWindow;
import com.flood.statemigrate.domain.RainScheme;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface RainRiskWindowRepo extends JpaRepository<RainRiskWindow, Long> {
    List<RainRiskWindow> findByStatusAndWindowStartLessThanAndWindowEndGreaterThan(
            String status, Instant to, Instant from);
    List<RainRiskWindow> findByStatusAndSchemeAndWindowStartLessThanAndWindowEndGreaterThan(
            String status, RainScheme scheme, Instant to, Instant from);
    List<RainRiskWindow> findByEventId(Long eventId);
}
