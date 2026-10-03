package com.baran.recon.application.run;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.RunAlreadyRunningException;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.source.ConfiguredSources;
import com.baran.recon.domain.source.SourceDefinition;
import com.baran.recon.domain.source.SourceType;

/**
 * Runs matching for one source and value-date range (TDD 5.3). In order:
 * <ol>
 *   <li>the source must be configured and the range in order;</li>
 *   <li>the run is recorded RUNNING with its configuration snapshot (FR-MAT-8), in a transaction of
 *       its own. The database allows one RUNNING run per source, so a run of a busy source is
 *       refused here, naming the run that holds it;</li>
 *   <li>in one transaction: Stage A for a PSP source, the run's statistics (FR-MAT-10), and the run
 *       set COMPLETED;</li>
 *   <li>if that transaction fails it is rolled back, so nothing of the run's work remains
 *       (NFR-REL-2), and the run is set FAILED in a transaction of its own.</li>
 * </ol>
 *
 * <p>A run's scope is its source's items with a value date in the range. A ledger entry with no
 * value date is in no range (FR-MAT-9); the run counts how many its source has instead (FR-MAT-10).
 */
public final class RunMatching {

    /** FR-MAT-8: the zone a ledger entry's created_at was read in to give its value date (TDD 6). */
    public static final String VALUE_DATE_ZONE = "value_date_zone";

    private static final Pattern SOURCE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final ConfiguredSources sources;
    private final RunStore runs;
    private final LedgerEntryStore ledgerEntries;
    private final Transactions transactions;
    private final Clock clock;
    private final ZoneId valueDateZone;

    public RunMatching(ConfiguredSources sources, RunStore runs, LedgerEntryStore ledgerEntries,
                       Transactions transactions, Clock clock, ZoneId valueDateZone) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.ledgerEntries = Objects.requireNonNull(ledgerEntries, "ledgerEntries");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.valueDateZone = Objects.requireNonNull(valueDateZone, "valueDateZone");
    }

    /**
     * @return the COMPLETED run, with its statistics
     * @throws RunRefusedException if the run was refused before it was recorded
     * @throws RuntimeException    whatever made the run's work fail. The run is then FAILED, unless
     *                             recording that failed too, which is attached as suppressed
     */
    public ReconciliationRun run(String sourceCode, LocalDate valueDateFrom, LocalDate valueDateTo, String triggeredBy) {
        SourceDefinition source = sourceNamed(sourceCode);
        if (valueDateFrom.isAfter(valueDateTo)) {
            throw RunRefusedException.invalidDateRange();
        }
        ReconciliationRun running = start(source, valueDateFrom, valueDateTo, triggeredBy);
        try {
            return transactions.inTransaction(() -> work(source, running));
        } catch (RuntimeException failure) {
            recordFailure(running, failure);
            throw failure;
        }
    }

    private SourceDefinition sourceNamed(String code) {
        if (code == null || !SOURCE_CODE.matcher(code).matches()) {
            throw RunRefusedException.unknownSource();
        }
        return sources.find(SourceCode.of(code)).orElseThrow(RunRefusedException::unknownSource);
    }

    private ReconciliationRun start(SourceDefinition source, LocalDate from, LocalDate to, String triggeredBy) {
        ReconciliationRun running = ReconciliationRun.start(UUID.randomUUID(), source.code(), from, to,
                configSnapshot(), now(), triggeredBy);
        try {
            transactions.inTransaction(() -> {
                runs.insert(running);
                return null;
            });
        } catch (RunAlreadyRunningException busy) {
            throw RunRefusedException.sourceBusy(runs.findRunning(source.code()), busy);
        }
        return running;
    }

    private Map<String, String> configSnapshot() {
        return Map.of(VALUE_DATE_ZONE, valueDateZone.getId());
    }

    private ReconciliationRun work(SourceDefinition source, ReconciliationRun running) {
        if (source.type() == SourceType.PSP_SETTLEMENT) {
            matchStageA(running);
        }
        long inScope = ledgerEntries.countInScope(running.source(), running.valueDateFrom(), running.valueDateTo());
        long withoutValueDate = ledgerEntries.countWithoutValueDate(running.source());
        ReconciliationRun completed = running.complete(Map.of(
                ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE, inScope,
                ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, withoutValueDate), now());
        runs.recordOutcome(completed);
        return completed;
    }

    /**
     * Stage A (TDD 8.2) runs here, inside the work transaction, so whatever it matches and opens
     * commits with the run's COMPLETED or not at all. Its rules are not built yet, so a run matches
     * nothing and opens no break.
     */
    private void matchStageA(ReconciliationRun running) {
    }

    /**
     * The work transaction was rolled back; the run is set FAILED in one of its own. If that fails
     * too, the run stays RUNNING until the next startup sets it FAILED, and the caller still sees
     * the failure that stopped the run.
     */
    private void recordFailure(ReconciliationRun running, RuntimeException failure) {
        try {
            transactions.inTransaction(() -> {
                runs.recordOutcome(running.fail(now()));
                return null;
            });
        } catch (RuntimeException notRecorded) {
            failure.addSuppressed(notRecorded);
        }
    }

    /** PostgreSQL keeps microseconds, so a run reads back exactly as it was recorded. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
