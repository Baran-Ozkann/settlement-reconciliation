package com.baran.recon.application.run;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.RunAlreadyRunningException;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.StageAStore;
import com.baran.recon.application.port.StageAStore.FallbackOutcome;
import com.baran.recon.application.port.StageAStore.ItemTotals;
import com.baran.recon.application.port.StageAStore.StageAPass;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.domain.calendar.BusinessCalendar;
import com.baran.recon.domain.calendar.BusinessDayIndex;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.match.StageARule;
import com.baran.recon.domain.run.ItemStatistics;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.source.ConfiguredSources;
import com.baran.recon.domain.source.SourceDefinition;
import com.baran.recon.domain.source.StageASettings;

/**
 * Runs matching for one source and value-date range (TDD 5.3). In order:
 * <ol>
 *   <li>the source must be configured and the range in order;</li>
 *   <li>the run is recorded RUNNING with its configuration snapshot (FR-MAT-8), in a transaction of
 *       its own. The database allows one RUNNING run per source, so a run of a busy source is
 *       refused here, naming the run that holds it;</li>
 *   <li>in one transaction at REPEATABLE READ: Stage A for a PSP source, the run's statistics
 *       (FR-MAT-10), and the run set COMPLETED. Every statement reads the snapshot the first one
 *       took, so an item committed while the run works is seen by none of its steps, never by
 *       some and not others; the next run takes it up;</li>
 *   <li>if that transaction fails it is rolled back, so nothing of the run's work remains
 *       (NFR-REL-2), and the run is set FAILED in a transaction of its own. A serialization
 *       failure is such a failure.</li>
 * </ol>
 *
 * <p>A run's scope is its source's items with a value date in the range. A ledger entry with no
 * value date is in no range (FR-MAT-9); the run counts how many its source has instead (FR-MAT-10).
 */
public final class RunMatching {

    /** FR-MAT-8: the zone a ledger entry's created_at was read in to give its value date (TDD 6). */
    public static final String VALUE_DATE_ZONE = "value_date_zone";
    /** FR-MAT-8, TDD 8.1: the weekend days of the business calendar, in week order, comma-separated. */
    public static final String BUSINESS_CALENDAR_WEEKEND = "business_calendar_weekend";
    /** FR-MAT-8, TDD 8.1: the holidays of the business calendar, ISO dates in order, comma-separated. */
    public static final String BUSINESS_CALENDAR_HOLIDAYS = "business_calendar_holidays";
    /** FR-MAT-8, TDD 8.1: the PSP source's value-date window, in business days. */
    public static final String VALUE_DATE_WINDOW_DAYS = "value_date_window_days";
    /** FR-MAT-8, TDD 8.1: business days a ledger entry may stay unmatched before it is MISSING_IN_PSP. */
    public static final String GRACE_DAYS_LEDGER_UNMATCHED = "grace_days_ledger_unmatched";
    /** FR-MAT-8, TDD 8.1: business days a PSP line may stay unmatched before it is MISSING_IN_LEDGER. */
    public static final String GRACE_DAYS_PSP_UNMATCHED = "grace_days_psp_unmatched";
    /** FR-MAT-8: followed by a Stage A rule id, the version of that rule the run ran. */
    public static final String RULE_VERSION_PREFIX = "rule_version.";

    private static final Pattern SOURCE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    /** The JDK's logger, so the application layer stays on java.* (TDD 5.2); it reaches the application's log. */
    private static final System.Logger LOG = System.getLogger(RunMatching.class.getName());

    private final ConfiguredSources sources;
    private final RunStore runs;
    private final LedgerEntryStore ledgerEntries;
    private final StageAStore stageA;
    private final Transactions transactions;
    private final Clock clock;
    private final ZoneId valueDateZone;
    private final BusinessCalendar calendar;

    public RunMatching(ConfiguredSources sources, RunStore runs, LedgerEntryStore ledgerEntries, StageAStore stageA,
                       Transactions transactions, Clock clock, ZoneId valueDateZone, BusinessCalendar calendar) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.ledgerEntries = Objects.requireNonNull(ledgerEntries, "ledgerEntries");
        this.stageA = Objects.requireNonNull(stageA, "stageA");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.valueDateZone = Objects.requireNonNull(valueDateZone, "valueDateZone");
        this.calendar = Objects.requireNonNull(calendar, "calendar");
    }

    /**
     * @return the COMPLETED run, with its statistics
     * @throws RunRefusedException if the run was refused before it was recorded
     * @throws RunFailedException  if the run's work failed and the run was recorded FAILED; the
     *                             failure is its cause
     * @throws RuntimeException    whatever made the run's work fail, when recording FAILED failed too
     *                             (attached as suppressed): the run is then still RUNNING
     */
    public ReconciliationRun run(String sourceCode, LocalDate valueDateFrom, LocalDate valueDateTo, String triggeredBy) {
        SourceDefinition source = sourceNamed(sourceCode);
        if (valueDateFrom.isAfter(valueDateTo)) {
            throw RunRefusedException.invalidDateRange();
        }
        ReconciliationRun running = start(source, valueDateFrom, valueDateTo, triggeredBy);
        try {
            return transactions.inSnapshotTransaction(() -> work(source, running));
        } catch (RuntimeException failure) {
            if (recordFailure(running, failure)) {
                throw new RunFailedException(running.id(), failure);
            }
            throw failure;
        }
    }

    /**
     * Sets FAILED every run a stopped JVM left RUNNING (TDD 5.3). Called at startup, before this
     * instance can start a run of its own; v1 runs one instance, so any RUNNING run is a leftover.
     * Its work transaction never committed, so nothing of its work remains.
     *
     * @return the ids of the runs it set FAILED
     */
    public List<UUID> failRunsLeftRunning() {
        return transactions.inTransaction(() -> runs.failAllRunning(now()));
    }

    private SourceDefinition sourceNamed(String code) {
        if (code == null || !SOURCE_CODE.matcher(code).matches()) {
            throw RunRefusedException.unknownSource();
        }
        return sources.find(SourceCode.of(code)).orElseThrow(RunRefusedException::unknownSource);
    }

    private ReconciliationRun start(SourceDefinition source, LocalDate from, LocalDate to, String triggeredBy) {
        ReconciliationRun running = ReconciliationRun.start(UUID.randomUUID(), source.code(), from, to,
                configSnapshot(source), now(), triggeredBy);
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

    /**
     * FR-MAT-8: everything the run reads from configuration. A PSP source's run adds what Stage A
     * reads: the business calendar, the source's window and grace periods, and the rule versions.
     */
    private Map<String, String> configSnapshot(SourceDefinition source) {
        Map<String, String> snapshot = new TreeMap<>();
        snapshot.put(VALUE_DATE_ZONE, valueDateZone.getId());
        source.stageA().ifPresent(stageA -> {
            snapshot.put(BUSINESS_CALENDAR_WEEKEND, calendar.weekend().stream().sorted()
                    .map(DayOfWeek::name).collect(Collectors.joining(",")));
            snapshot.put(BUSINESS_CALENDAR_HOLIDAYS, calendar.holidays().stream().sorted()
                    .map(LocalDate::toString).collect(Collectors.joining(",")));
            snapshot.put(VALUE_DATE_WINDOW_DAYS, Integer.toString(stageA.valueDateWindowDays()));
            snapshot.put(GRACE_DAYS_LEDGER_UNMATCHED, Integer.toString(stageA.graceDaysLedgerUnmatched()));
            snapshot.put(GRACE_DAYS_PSP_UNMATCHED, Integer.toString(stageA.graceDaysPspUnmatched()));
            for (StageARule rule : StageARule.values()) {
                snapshot.put(RULE_VERSION_PREFIX + rule.name(), Integer.toString(rule.version()));
            }
        });
        return snapshot;
    }

    /**
     * The run's statistics (FR-MAT-10, FR-API-4). After Stage A they hold its items per side,
     * currency and status, which must add up to its scope (INV-1, INV-4): if they do not, the run
     * fails here, inside its work transaction, and nothing it wrote is kept.
     */
    private ReconciliationRun work(SourceDefinition source, ReconciliationRun running) {
        Map<String, Long> stats = new TreeMap<>();
        source.stageA().ifPresent(settings -> {
            matchStageA(running, settings);
            ItemTotals totals = stageA.itemTotals(running.source(), running.valueDateFrom(), running.valueDateTo());
            stats.putAll(ItemStatistics.conserved(totals.byStatus(), totals.inScope()));
        });
        stats.put(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE,
                ledgerEntries.countInScope(running.source(), running.valueDateFrom(), running.valueDateTo()));
        stats.put(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE,
                ledgerEntries.countWithoutValueDate(running.source()));
        ReconciliationRun completed = running.complete(stats, now());
        runs.recordOutcome(completed);
        return completed;
    }

    /**
     * Stage A (TDD 8.2) for a PSP source, inside the work transaction, so whatever it matches and
     * opens commits with the run's COMPLETED or not at all. Its rules run in their fixed order
     * (FR-MAT-3), each a statement over the source's unmatched items, and each sees what the ones
     * before it wrote. Only ids and counts are logged.
     */
    private void matchStageA(ReconciliationRun running, StageASettings settings) {
        StageAPass pass = new StageAPass(running.id(), running.source(), running.valueDateFrom(), running.valueDateTo(),
                settings.valueDateWindowDays(),
                BusinessDayIndex.forRange(calendar, running.valueDateFrom(), running.valueDateTo(),
                        settings.valueDateWindowDays()),
                now());
        int byReference = stageA.matchByReference(pass);
        int referenceBreaks = stageA.openReferenceBreaks(pass);
        FallbackOutcome byAmount = stageA.matchByAmount(pass);
        int matchedLate = stageA.resolveMatchedLate(running.id(), "Matched by run " + running.id(), pass.at());
        LocalDate today = LocalDate.ofInstant(pass.at(), valueDateZone);
        int graceBreaks = stageA.openGraceBreaks(pass,
                calendar.firstDayWithinGrace(today, settings.graceDaysLedgerUnmatched()),
                calendar.firstDayWithinGrace(today, settings.graceDaysPspUnmatched()));
        LOG.log(System.Logger.Level.INFO, "Run {0} on source {1}: Stage A matched {2} by A1 and {3} by A3, opened "
                        + "{4} reference, {5} ambiguity and {6} grace breaks, and resolved {7} as MATCHED_LATE",
                running.id(), running.source().value(), Integer.toString(byReference),
                Integer.toString(byAmount.matched()), Integer.toString(referenceBreaks),
                Integer.toString(byAmount.breaksOpened()), Integer.toString(graceBreaks), Integer.toString(matchedLate));
    }

    /**
     * The work transaction was rolled back; the run is set FAILED in one of its own. If that fails
     * too, the run stays RUNNING until the next startup sets it FAILED, and the caller still sees
     * the failure that stopped the run.
     *
     * @return whether the run was recorded FAILED
     */
    private boolean recordFailure(ReconciliationRun running, RuntimeException failure) {
        try {
            transactions.inTransaction(() -> {
                runs.recordOutcome(running.fail(now()));
                return null;
            });
            return true;
        } catch (RuntimeException notRecorded) {
            failure.addSuppressed(notRecorded);
            return false;
        }
    }

    /** PostgreSQL keeps microseconds, so a run reads back exactly as it was recorded. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
