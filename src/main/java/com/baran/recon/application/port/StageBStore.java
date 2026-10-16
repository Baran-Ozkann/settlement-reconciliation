package com.baran.recon.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.baran.recon.domain.item.SourceCode;

/**
 * Stage B (TDD 8.2) as the database applies it to a bank source's run, on the bank source's lines
 * and on the batches of the PSP sources that settle to it, each as one set, so a run never holds a
 * source's items in memory. Every step runs inside the run's work transaction.
 *
 * <p>A batch has no row of its own: it is a PSP source's lines with one batch id, whatever file they
 * came from, as the run's snapshot holds them. Its date is the latest value date of its lines, and
 * its item id is {@link com.baran.recon.domain.item.BatchKey#itemId()}.
 *
 * <p>The rules consider only bank lines and batches without an active match (FR-MAT-2). Each
 * unmatched bank line of the source gets one outcome, in TDD 8.2's precedence: no extracted batch
 * id, or one naming no batch of the PSP sources settling to the bank; an id naming batches of
 * several of them; an id naming one batch that another unmatched bank line also names, or that is
 * already matched; otherwise B1 when the batch's totals agree with the line, else B2. No step picks
 * among candidates, and nothing depends on the order rows are stored or read in (FR-MAT-4, FR-MAT-5).
 */
public interface StageBStore {

    /**
     * B1: matches each bank line to the batch its extracted id names, when the id names exactly one
     * batch among the settling PSP sources, no other unmatched bank line names that batch, the batch
     * is unmatched, has lines in one currency only, equal to the line's, and its net sum equals the
     * line's amount. The bank line or the batch must be in the run's range; the other may lie
     * anywhere, as no value-date window applies: the batch id is identity (TDD 8.2). Each match holds
     * the BATCH item and the BANK item, records the batch's line count, and has no amount difference.
     *
     * @return the matches made
     * @throws RuntimeException if a batch's sum does not fit in a BIGINT (INV-8): the run fails
     */
    int matchBatchTotals(StageBPass pass);

    /**
     * The run's items, totalled per side and currency twice over, in one statement and so on one
     * snapshot: by status (INV-1, in the order of {@link com.baran.recon.domain.run.ItemStatus}) and
     * of every status.
     * <ul>
     *   <li>BANK: the bank source's lines with a value date in the range, each counting its
     *       amount;</li>
     *   <li>BATCH: the batches of {@code pspSources} dated in the range, each counting its net sum in
     *       each of its currencies, so a mixed-currency batch is counted once in each.</li>
     * </ul>
     * Only breaks about a BANK or a BATCH item make an item BROKEN, as subject or as related item.
     *
     * @throws RuntimeException if a sum does not fit in a BIGINT (INV-8): the run fails
     */
    ItemTotals itemTotals(SourceCode bank, List<SourceCode> pspSources, LocalDate valueDateFrom,
                          LocalDate valueDateTo);

    /**
     * What a step works on: the run, its bank source and the PSP sources settling to it as the run
     * snapshotted them, its value-date range, and the time its writes are recorded at.
     */
    record StageBPass(UUID runId, SourceCode bank, List<SourceCode> pspSources, LocalDate valueDateFrom,
                      LocalDate valueDateTo, Instant at) {

        public StageBPass {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(bank, "bank");
            pspSources = List.copyOf(pspSources);
            Objects.requireNonNull(valueDateFrom, "valueDateFrom");
            Objects.requireNonNull(valueDateTo, "valueDateTo");
            Objects.requireNonNull(at, "at");
        }
    }
}
