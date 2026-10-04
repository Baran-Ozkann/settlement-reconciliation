package com.baran.recon.adapters.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.SortedMap;
import java.util.UUID;

import com.baran.recon.domain.run.ReconciliationRun;

/**
 * A run as the API shows it: its source and value-date range, its status, the configuration it ran
 * with (FR-MAT-8) and, once COMPLETED, its statistics (FR-MAT-10). A run that is still RUNNING, or
 * that FAILED, has no statistics; a RUNNING one has no finish time either.
 */
record RunView(
        UUID id,
        String source,
        LocalDate valueDateFrom,
        LocalDate valueDateTo,
        String status,
        SortedMap<String, String> configSnapshot,
        Optional<SortedMap<String, Long>> stats,
        Instant startedAt,
        Optional<Instant> finishedAt,
        String triggeredBy) {

    static RunView of(ReconciliationRun run) {
        return new RunView(run.id(), run.source().value(), run.valueDateFrom(), run.valueDateTo(), run.status().name(),
                run.configSnapshot(), run.stats(), run.startedAt(), run.finishedAt(), run.triggeredBy());
    }
}
