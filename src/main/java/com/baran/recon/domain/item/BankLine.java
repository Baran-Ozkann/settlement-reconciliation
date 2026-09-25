package com.baran.recon.domain.item;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.baran.recon.domain.money.Money;

/**
 * One row of a bank statement (TDD 7.2). Positive amounts are credits to our account. The batch id
 * the source's pattern extracted from the reference, if any, is kept with the line so Stage B does
 * not re-run the pattern. The description is stored and never used for matching.
 */
public record BankLine(
        UUID id,
        UUID fileId,
        SourceCode source,
        String lineId,
        LocalDate bookingDate,
        LocalDate valueDate,
        Money amount,
        Optional<String> reference,
        Optional<String> extractedBatchId,
        Optional<String> description) {

    public BankLine {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(source, "source");
        ItemRules.identifier("line_id", lineId);
        Objects.requireNonNull(bookingDate, "bookingDate");
        Objects.requireNonNull(valueDate, "valueDate");
        if (amount.isZero()) {
            throw new InvalidItemException("amount is not zero");
        }
        ItemRules.optionalText("reference", reference, 140);
        extractedBatchId.ifPresent(batchId -> ItemRules.identifier("extracted batch id", batchId));
        ItemRules.optionalText("description", description, 140);
    }
}
