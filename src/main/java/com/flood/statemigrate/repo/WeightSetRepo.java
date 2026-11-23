package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.WeightSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WeightSetRepo extends JpaRepository<WeightSet, Long> {
    Optional<WeightSet> findByVersion(String version);
    Optional<WeightSet> findByStatus(String status);
}
