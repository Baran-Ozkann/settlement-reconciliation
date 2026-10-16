package com.baran.recon.domain.breaks;

import java.util.Objects;
import java.util.UUID;

import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.money.Money;

/**
 * The figures a BATCH_AMOUNT_MISMATCH records when it opens (TDD 8.2, v1.15). A batch's lines may
 * still be arriving, so its sum changes from run to run; the break keeps the figures that disagreed
 * when it was opened, and a later run never changes them (INV-7 keeps the one open break).
 *
 * <p>Two kinds, each about one side of item:
 * <ul>
 *   <li>{@link BatchTotalConflict}, on a bank line (rule B2): the bank line's amount against the
 *       batch it names;</li>
 *   <li>{@link BatchChangedAfterMatch}, on a batch: a line arrived after the batch was matched, so
 *       what the match recorded no longer holds.</li>
 * </ul>
 */
public sealed interface BreakDetails permits BreakDetails.BatchTotalConflict, BreakDetails.BatchChangedAfterMatch {

    /** The side of the item a break with these details is about. */
    ItemSide subjectSide();

    /** B2: the bank line's amount, and the batch it names with its line count and sums. */
    record BatchTotalConflict(Money bank, BatchFigures batch) implements BreakDetails {

        public BatchTotalConflict {
            Objects.requireNonNull(bank, "bank");
            Objects.requireNonNull(batch, "batch");
        }

        @Override
        public ItemSide subjectSide() {
            return ItemSide.BANK;
        }
    }

    /**
     * A matched batch whose lines changed: what the match recorded - its line count, and the bank
     * line's amount, which B1 made equal to the batch's sum - and what the batch is now.
     */
    record BatchChangedAfterMatch(UUID matchId, BatchFigures matched, BatchFigures current) implements BreakDetails {

        public BatchChangedAfterMatch {
            Objects.requireNonNull(matchId, "matchId");
            Objects.requireNonNull(matched, "matched");
            Objects.requireNonNull(current, "current");
            if (matched.sums().size() != 1) {
                throw new InvalidBreakException("a match recorded one amount, its bank line's");
            }
        }

        @Override
        public ItemSide subjectSide() {
            return ItemSide.BATCH;
        }
    }
}
