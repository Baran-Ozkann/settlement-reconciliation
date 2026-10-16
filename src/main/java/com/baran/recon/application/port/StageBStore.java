package com.baran.recon.application.port;

import java.time.LocalDate;
import java.util.List;

import com.baran.recon.domain.item.SourceCode;

/**
 * Stage B (TDD 8.2) as the database applies it to a bank source's run, on the bank source's lines
 * and on the batches of the PSP sources that settle to it, each as one set, so a run never holds a
 * source's items in memory. Every step runs inside the run's work transaction.
 *
 * <p>A batch has no row of its own: it is a PSP source's lines with one batch id, whatever file they
 * came from, as the run's snapshot holds them. Its date is the latest value date of its lines, and
 * its item id is {@link com.baran.recon.domain.item.BatchKey#itemId()}.
 */
public interface StageBStore {

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
}
