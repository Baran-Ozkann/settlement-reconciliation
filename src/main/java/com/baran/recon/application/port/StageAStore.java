package com.baran.recon.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.baran.recon.domain.calendar.BusinessDayIndex;
import com.baran.recon.domain.item.SourceCode;

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
