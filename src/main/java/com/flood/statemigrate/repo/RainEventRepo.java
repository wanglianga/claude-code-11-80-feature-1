package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.RainEvent;
import com.flood.statemigrate.domain.RainEventStatus;
import com.flood.statemigrate.domain.RainScheme;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface RainEventRepo extends JpaRepository<RainEvent, Long> {
    List<RainEvent> findBySchemeAndStatusNotOrderByStartAtAsc(RainScheme scheme, RainEventStatus status);
    List<RainEvent> findByStatusNotAndStartAtLessThanAndEndAtGreaterThan(
            RainEventStatus status, Instant to, Instant from);
    List<RainEvent> findBySchemeAndStatusNotAndStartAtLessThanAndEndAtGreaterThan(
            RainScheme scheme, RainEventStatus status, Instant to, Instant from);
    List<RainEvent> findBySchemeOrderByRunNoDescStartAtAsc(RainScheme scheme);
}
