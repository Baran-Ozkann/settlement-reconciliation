package com.baran.recon.application.ledger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.baran.recon.application.port.DuplicateLedgerEntryException;
import com.baran.recon.application.port.LedgerEntryBatchConflictException;
import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.domain.item.LedgerEntry;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.source.LedgerAccountSources;

/**
 * Projects ledger events into the local store (TDD 5.3). A batch is stored in one transaction, so
 * the caller can acknowledge the whole batch once this returns (FR-LED-4).
 *
 * <p>An entry id another event already carried fails the batch's statement (FR-LED-8). The batch is
 * then rolled back and its entries are stored one per transaction, which isolates the offending
 * ones: every other entry is stored, and only those are reported as {@code DUPLICATE_ENTRY_ID}.
 * Each of those transactions is idempotent, so a failure part-way through leaves nothing a retry of
 * the whole batch cannot repeat safely.
 */
public final class ProjectLedgerEvents {

    private final LedgerAccountSources sources;
    private final LedgerEntryStore store;
    private final Transactions transactions;
    private final Clock clock;
    private final ZoneId valueDateZone;

    public ProjectLedgerEvents(LedgerAccountSources sources, LedgerEntryStore store, Transactions transactions,
                               Clock clock, ZoneId valueDateZone) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.valueDateZone = Objects.requireNonNull(valueDateZone, "valueDateZone");
    }

    /** @return one outcome per event, in the order given */
    public List<ProjectionOutcome> project(List<LedgerEvent> events) {
        Instant receivedAt = clock.instant();
        ProjectionOutcome[] outcomes = new ProjectionOutcome[events.size()];
        List<Integer> positions = new ArrayList<>();
        List<LedgerEntry> entries = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            LedgerEvent event = events.get(i);
            Optional<SourceCode> source = sources.sourceOf(event.accountId());
            if (source.isEmpty()) {
                outcomes[i] = ProjectionOutcome.UNMAPPED_ACCOUNT;
            } else {
                positions.add(i);
                entries.add(toEntry(event, source.get(), receivedAt));
            }
        }
        List<ProjectionOutcome> stored = store(entries);
        for (int j = 0; j < positions.size(); j++) {
            outcomes[positions.get(j)] = stored.get(j);
        }
        return Arrays.asList(outcomes);
    }

    private List<ProjectionOutcome> store(List<LedgerEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        try {
            return transactions.inTransaction(() -> store.storeAllIfAbsent(entries)).stream()
                    .map(ProjectLedgerEvents::outcomeOf)
                    .toList();
        } catch (LedgerEntryBatchConflictException conflict) {
            return entries.stream().map(this::storeAlone).toList();
        }
    }

    private ProjectionOutcome storeAlone(LedgerEntry entry) {
        try {
            return outcomeOf(transactions.inTransaction(() -> store.storeIfAbsent(entry)));
        } catch (DuplicateLedgerEntryException duplicate) {
            return ProjectionOutcome.DUPLICATE_ENTRY_ID;
        }
    }

    /**
     * The value date comes from created_at alone. A five-field event has none, and it is never
     * back-dated from anything else, the Kafka record timestamp least of all (FR-LED-7).
     */
    private LedgerEntry toEntry(LedgerEvent event, SourceCode source, Instant receivedAt) {
        return new LedgerEntry(
                UUID.randomUUID(),
                event.eventId(),
                event.entryId(),
                event.transactionId(),
                event.accountId(),
                source,
                Money.of(event.amount(), event.currency()),
                event.txType(),
                event.createdAt(),
                event.createdAt().map(createdAt -> LedgerEntry.valueDateOf(createdAt, valueDateZone)),
                receivedAt);
    }

    private static ProjectionOutcome outcomeOf(boolean stored) {
        return stored ? ProjectionOutcome.STORED : ProjectionOutcome.DUPLICATE_EVENT;
    }
}
