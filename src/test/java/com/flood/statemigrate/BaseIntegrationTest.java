package com.flood.statemigrate;

import com.flood.statemigrate.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
abstract class BaseIntegrationTest {

    @Autowired protected StreamDefinitionRepo streamRepo;
    @Autowired protected RulePackageRepo rulePackageRepo;
    @Autowired protected CheckpointManifestRepo checkpointRepo;
    @Autowired protected StreamEventRepo eventRepo;
    @Autowired protected StateProofRepo proofRepo;
    @Autowired protected MigrationPlanRepo planRepo;
    @Autowired protected DualTrackRunRepo runRepo;
    @Autowired protected DualTrackDiffRepo diffRepo;
    @Autowired protected SwitchGateRepo gateRepo;
    @Autowired protected RollbackRecordRepo rollbackRepo;
    @Autowired protected EvidenceEntryRepo evidenceRepo;
    @Autowired protected RainStationRepo rainStationRepo;
    @Autowired protected RainSampleRepo rainSampleRepo;
    @Autowired protected RainEventRepo rainEventRepo;
    @Autowired protected RainRiskWindowRepo rainRiskWindowRepo;
    @Autowired protected SubbasinSeriesRepo subbasinSeriesRepo;
    @Autowired protected WeightSetRepo weightSetRepo;
    @Autowired protected RainLedgerRepo rainLedgerRepo;

    protected ScenarioBuilder scenario;

    @BeforeEach
    void cleanDb() {
        rainLedgerRepo.deleteAll();
        subbasinSeriesRepo.deleteAll();
        rainRiskWindowRepo.deleteAll();
        rainEventRepo.deleteAll();
        rainSampleRepo.deleteAll();
        weightSetRepo.deleteAll();
        rainStationRepo.deleteAll();
        diffRepo.deleteAll();
        runRepo.deleteAll();
        evidenceRepo.deleteAll();
        planRepo.deleteAll();
        gateRepo.deleteAll();
        rollbackRepo.deleteAll();
        checkpointRepo.deleteAll();
        eventRepo.deleteAll();
        proofRepo.deleteAll();
        rulePackageRepo.deleteAll();
        streamRepo.deleteAll();
        scenario = new ScenarioBuilder(streamRepo, rulePackageRepo, eventRepo, checkpointRepo);
    }
}
