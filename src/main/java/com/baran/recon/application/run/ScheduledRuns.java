package com.baran.recon.application.run;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.source.ConfiguredSources;
import com.baran.recon.domain.source.SourceDefinition;
import com.baran.recon.domain.source.SourceType;

/**
 * The daily scheduled run (FR-MAT-1, TDD 5.3, v1.19): a run of every configured source over
 * {@code [today - look-back-days, today]}. A file's runs cover only the file's own dates, so without
 * this a grace break (MISSING_IN_PSP, MISSING_IN_LEDGER, MISSING_SETTLEMENT) would open only when a
 * later file happened to cover the item's date, and a batch a PSP never pays would never be reported.
 *
 * <p>The PSP sources' runs are queued first, then the bank sources', each in configuration order,
 * so a bank run reads the batches its PSP sources' runs have just reconciled. Each source takes one
 * place in the automatic trigger's queue and runs on its thread as the system, with its busy wait and
 * give-up bound; a full queue refuses a source with a WARN, as it refuses a file.
 *
 * <p>"Today" is the injected clock's day in the value-date zone. When to call this is the scheduler's
 * business, kept apart in configuration; a day the application was down at that time is not run
 * later, since the next day's look-back covers it.
 */
public final class ScheduledRuns {

    /** The JDK's logger, so the application layer stays on java.* (TDD 5.2); it reaches the application's log. */
    private static final System.Logger LOG = System.getLogger(ScheduledRuns.class.getName());

    private final ConfiguredSources sources;
    private final AutomaticRunTrigger trigger;
    private final Clock clock;
    private final ZoneId valueDateZone;
    private final int lookBackDays;

    public ScheduledRuns(ConfiguredSources sources, AutomaticRunTrigger trigger, Clock clock, ZoneId valueDateZone,
                         int lookBackDays) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.valueDateZone = Objects.requireNonNull(valueDateZone, "valueDateZone");
        if (lookBackDays < 1) {
            throw new IllegalArgumentException("the look-back is at least one day");
        }
        this.lookBackDays = lookBackDays;
    }

    /** Queues today's run of every configured source and returns without waiting for them. */
    public void queueDailyRuns() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), valueDateZone);
        LocalDate from = today.minusDays(lookBackDays);
        List<SourceCode> order = Stream.concat(codes(SourceType.PSP_SETTLEMENT), codes(SourceType.BANK_STATEMENT))
                .toList();
        LOG.log(System.Logger.Level.INFO, "Scheduled runs of {0}: queueing {1} sources over {2} to {3}", today,
                Integer.toString(order.size()), from, today);
        for (SourceCode source : order) {
            trigger.scheduledRun(today, source, from, today);
        }
    }

    private Stream<SourceCode> codes(SourceType type) {
        return sources.all().stream().filter(source -> source.type() == type).map(SourceDefinition::code);
    }
}
