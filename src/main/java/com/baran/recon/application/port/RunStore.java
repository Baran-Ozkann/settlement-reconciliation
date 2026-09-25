package com.baran.recon.application.port;

import java.util.Optional;
import java.util.UUID;

import com.baran.recon.domain.run.ReconciliationRun;

/** Reconciliation runs. Recording a run's outcome is Phase 5 work, together with its grant. */
public interface RunStore {

    void insert(ReconciliationRun run);

    Optional<ReconciliationRun> findById(UUID id);
}
