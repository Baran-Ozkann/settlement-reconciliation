package com.baran.recon.domain.run;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import com.baran.recon.domain.item.SourceCode;

/**
 * One execution of the matching engine for a source and a value-date window (TDD 3, 10).
 *
 * <p>The configuration it ran with is snapshotted onto it (FR-MAT-8), and a completed run carries
 * its statistics (FR-MAT-10), so a past run can be explained after the configuration and the data
 * have moved on. Both are kept as sorted key-value maps; Phase 5 fixes which keys a run records.
 */
public record ReconciliationRun(
        UUID id,
        SourceCode source,
        LocalDate valueDateFrom,
        LocalDate valueDateTo,
        RunStatus status,
        SortedMap<String, String> configSnapshot,
        Optional<SortedMap<String, Long>> stats,
        Instant startedAt,
        Optional<Instant> finishedAt,
        String triggeredBy) {

    /** FR-MAT-10: entries with no value date, which no run's scope can include (FR-MAT-9). */
    public static final String LEDGER_ENTRIES_WITHOUT_VALUE_DATE = "ledger_entries_without_value_date";

    private static final int MAX_TRIGGERED_BY_LENGTH = 100;

    public ReconciliationRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        if (valueDateFrom.isAfter(valueDateTo)) {
            throw new InvalidRunException("the value-date window ends before it starts");
        }
        configSnapshot = sortedCopy(configSnapshot);
        stats = stats.map(ReconciliationRun::sortedCopy);
        if ((status == RunStatus.RUNNING) == finishedAt.isPresent()) {
            throw new InvalidRunException("a run has a finish time exactly when it is no longer running");
        }
        if (finishedAt.filter(startedAt::isAfter).isPresent()) {
            throw new InvalidRunException("a run cannot finish before it starts");
        }
        if (status == RunStatus.COMPLETED && stats.isEmpty()) {
            throw new InvalidRunException("a completed run records its statistics");
        }
        if (triggeredBy == null || triggeredBy.isBlank() || triggeredBy.length() > MAX_TRIGGERED_BY_LENGTH) {
            throw new InvalidRunException("triggered_by is 1-" + MAX_TRIGGERED_BY_LENGTH + " characters");
        }
    }

    /** A run as it begins: RUNNING, with its configuration and no results yet. */
    public static ReconciliationRun start(UUID id, SourceCode source, LocalDate valueDateFrom, LocalDate valueDateTo,
                                          Map<String, String> configSnapshot, Instant startedAt, String triggeredBy) {
        return new ReconciliationRun(id, source, valueDateFrom, valueDateTo, RunStatus.RUNNING,
                new TreeMap<>(configSnapshot), Optional.empty(), startedAt, Optional.empty(), triggeredBy);
    }

    private static <V> SortedMap<String, V> sortedCopy(Map<String, V> map) {
        return Collections.unmodifiableSortedMap(new TreeMap<>(map));
    }
}
