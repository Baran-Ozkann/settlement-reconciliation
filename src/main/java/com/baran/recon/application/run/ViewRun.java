package com.baran.recon.application.run;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.baran.recon.application.port.RunStore;
import com.baran.recon.domain.run.ReconciliationRun;

/** A run as recorded: its status, configuration snapshot and statistics (TDD 11, for a VIEWER). */
public final class ViewRun {

    private final RunStore runs;

    public ViewRun(RunStore runs) {
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    public Optional<ReconciliationRun> find(UUID id) {
        return runs.findById(id);
    }
}
