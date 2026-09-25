package com.baran.recon.domain.item;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;

/**
 * One transaction row of a PSP settlement report (TDD 7.1). The line keeps the rules the report
 * format states: one currency across its three amounts, a non-negative fee,
 * {@code net = gross - fee} exactly, a gross sign that matches the type, and a transaction date
 * no later than the value date.
 *
 * <p>An empty {@code transaction_reference} is {@code Optional.empty()}; such a line can only be
 * matched by the fallback rule A3.
 */
public record PspLine(
        UUID id,
        UUID fileId,
        SourceCode source,
        String lineId,
        Optional<String> reference,
        String batchId,
        PspLineType type,
        LocalDate transactionDate,
        LocalDate valueDate,
        Money gross,
        Money fee,
        Money net) {

    public PspLine {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(source, "source");
        ItemRules.identifier("line_id", lineId);
        ItemRules.optionalText("transaction_reference", reference, 64);
        ItemRules.identifier("batch_id", batchId);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(transactionDate, "transactionDate");
        Objects.requireNonNull(valueDate, "valueDate");
        if (transactionDate.isAfter(valueDate)) {
            throw new InvalidItemException("transaction_date is after value_date");
        }
        if (!gross.hasCurrency(fee.currency()) || !gross.hasCurrency(net.currency())) {
            throw new InvalidItemException("gross, fee and net share one currency");
        }
        if (fee.isNegative()) {
            throw new InvalidItemException("fee_amount is not negative");
        }
        if (type.hasPositiveGross() ? !gross.isPositive() : !gross.isNegative()) {
            throw new InvalidItemException("gross_amount sign does not match type " + type);
        }
        if (!gross.minus(fee).equals(net)) {
            throw new InvalidItemException("net_amount is not gross_amount - fee_amount");
        }
    }

    public CurrencyCode currency() {
        return gross.currency();
    }

    public BatchKey batchKey() {
        return new BatchKey(source, batchId);
    }
}
