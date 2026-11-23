package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.RainChangeKind;
import com.flood.statemigrate.domain.RainLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RainLedgerRepo extends JpaRepository<RainLedgerEntry, Long> {
    List<RainLedgerEntry> findByChangeTypeOrderByIdAsc(RainChangeKind changeType);
    List<RainLedgerEntry> findAllByOrderByIdAsc();
}
