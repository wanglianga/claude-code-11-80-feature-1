package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.RainStation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RainStationRepo extends JpaRepository<RainStation, Long> {
    Optional<RainStation> findByStationCode(String stationCode);
    List<RainStation> findByActiveTrue();
}
