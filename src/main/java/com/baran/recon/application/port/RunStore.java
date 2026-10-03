package com.baran.recon.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;

/** Reconciliation runs and their lifecycle (TDD 5.3). A run is never deleted. */
public interface RunStore {

    /**
     * Records a run.
     *
     * @throws RunAlreadyRunningException if the run is RUNNING and its source already has a RUNNING
     *         run: the database allows one per source
     */
    void insert(ReconciliationRun run);

    Optional<ReconciliationRun> findById(UUID id);

    /** The source's RUNNING run, if it has one. */
    Optional<UUID> findRunning(SourceCode source);

    /**
     * Records how a RUNNING run finished: its status, its statistics and its finish time. Nothing
     * else about a run ever changes.
     *
     * @throws RunNotRunningException if the stored run is no longer RUNNING
     */
    void recordOutcome(ReconciliationRun finished);

    /**
     * Sets every RUNNING run FAILED, finishing it at {@code finishedAt}, or at its start should that
     * be later.
     *
     * @return the ids of the runs it set FAILED, in no particular order
     */
    List<UUID> failAllRunning(Instant finishedAt);
}
