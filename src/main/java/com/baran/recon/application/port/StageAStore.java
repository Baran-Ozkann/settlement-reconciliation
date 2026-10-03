package com.baran.recon.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.baran.recon.domain.calendar.BusinessDayIndex;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ItemTotal;

/**
 * The rules of Stage A (TDD 8.2), each applied by the database to a source's items as one set, so
 * a run never holds a source's items in memory. Every step runs inside the run's work transaction
 * and considers only items without an active match (FR-MAT-2).
 *
 * <p>No step picks among candidates: a pair is matched because it is the only one, and nothing
 * depends on the order rows are stored or read in, or on generated ids (FR-MAT-4, FR-MAT-5).
 */
public interface StageAStore {

    /**
     * A1: matches a PSP line to the one unmatched ledger entry whose transaction id its reference
     * names, with the same currency and amount and a value date within the window. The line's
     * reference must be a UUID no other unmatched PSP line of the source carries. The line or the
     * entry must be in the run's range; the other may lie outside it, within the window.
     *
     * @return the matches made
     */
    int matchByReference(StageAPass pass);

    /**
     * After A1, opens a break on each unmatched PSP line in the run's range whose reference is a
     * UUID:
     * <ul>
     *   <li>DUPLICATE_LINE when another unmatched line of the source carries the same reference;
     *       neither is matched, and each names the others as related items;</li>
     *   <li>otherwise, among the unmatched entries with that transaction id within the window:
     *       AMBIGUOUS_MATCH when more than one has the line's currency and amount (A1 found several),
     *       or when none has and several conflict; CURRENCY_MISMATCH or AMOUNT_MISMATCH when exactly
     *       one conflicts (A2). The entries are the related items.</li>
     * </ul>
     * A line with no such entry gets nothing here. A line that already has an unresolved break gets
     * no second one (INV-7).
     *
     * @return the breaks opened
     */
    int openReferenceBreaks(StageAPass pass);

    /**
     * A3, after the reference rules: a PSP line in the run's range with no reference A1 could use -
     * none, one that is not a UUID, or one no ledger entry of the source carries, matched or not -
     * is matched, low confidence, to the only unmatched entry with its currency and amount within
     * the window, provided no other such line has that entry as a candidate. A line whose reference
     * is repeated by another unmatched line was given DUPLICATE_LINE and is not offered.
     *
     * <p>A line with several candidates, or whose only candidate another line also has, gets
     * AMBIGUOUS_MATCH naming its candidates (FR-MAT-4). Matches and breaks are decided on one
     * snapshot, so a match made here never changes what another line is found to be.
     */
    FallbackOutcome matchByAmount(StageAPass pass);

    /**
     * FR-BRK-5: resolves, as MATCHED_LATE by the system, every unresolved break whose subject is an
     * item the run matched, appending each resolution's event with the given reason. A break that
     * merely names a matched item among its related items is left alone.
     *
     * @return the breaks resolved
     */
    int resolveMatchedLate(UUID runId, String reason, Instant at);

    /**
     * The grace-period breaks, last (TDD 8.2): MISSING_IN_PSP on each ledger entry and
     * MISSING_IN_LEDGER on each PSP line in the run's range that is still pending - in no active
     * match, the subject of no unresolved break and named among the related items of none - and
     * whose value date is before the first day still inside its side's grace period.
     *
     * @return the breaks opened
     */
    int openGraceBreaks(StageAPass pass, LocalDate ledgerGraceStart, LocalDate pspGraceStart);

    /**
     * The source's ledger entries and PSP lines with a value date in the range, totalled per side
     * and currency twice over, in one statement and so on one snapshot: by status (INV-1, in the
     * order of {@link com.baran.recon.domain.run.ItemStatus}) and of every status. The two agree
     * unless an item was counted twice or not at all.
     */
    ItemTotals itemTotals(SourceCode source, LocalDate valueDateFrom, LocalDate valueDateTo);

    /** A run's in-scope items, totalled by status and of every status. */
    record ItemTotals(List<ItemTotal> byStatus, List<ItemTotal> inScope) {

        public ItemTotals {
            byStatus = List.copyOf(byStatus);
            inScope = List.copyOf(inScope);
        }
    }

    /** What A3 did: the matches it made and the ambiguity breaks it opened. */
    record FallbackOutcome(int matched, int breaksOpened) {
    }

    /**
     * What a step works on: the run, its source and value-date range, the window in business days
     * with the business days that measure it, and the time its writes are recorded at.
     */
    record StageAPass(UUID runId, SourceCode source, LocalDate valueDateFrom, LocalDate valueDateTo,
                      int valueDateWindowDays, BusinessDayIndex businessDays, Instant at) {

        public StageAPass {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(valueDateFrom, "valueDateFrom");
            Objects.requireNonNull(valueDateTo, "valueDateTo");
            Objects.requireNonNull(businessDays, "businessDays");
            Objects.requireNonNull(at, "at");
        }
    }
}
