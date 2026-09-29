package com.baran.recon.adapters.in.file;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baran.recon.adapters.in.file.FieldRules.LineRejected;
import com.baran.recon.application.port.ParsedLine;
import com.baran.recon.application.port.StatementContext;
import com.baran.recon.domain.item.BankLine;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.source.BatchIdPattern;
import com.baran.recon.domain.source.SourceType;
import com.baran.recon.domain.statement.ValidationCode;

/**
 * Bank statement CSV v1 (TDD 7.2). Columns are checked in header order, the amount's scale as soon
 * as the currency is known, and a zero amount is {@code INVALID_AMOUNT}. The batch id is extracted
 * from the reference with the source's {@code batch-id-pattern} and kept with the line; a reference
 * without one is still a valid line, which Stage B reports as an unexpected bank line.
 *
 * <p>A real ISO 4217 currency outside the supported set is read like any other, as for PSP lines
 * (TDD 7.3); TDD 7.2's "supported set" is proposed for the same correction.
 */
final class BankStatementParser extends CsvStatementParser {

    static final String HEADER = "line_id,booking_date,value_date,amount,currency,reference,description";

    private static final int MAX_TEXT_LENGTH = 140;

    BankStatementParser(int maxLineBytes, long maxLines) {
        super(HEADER, maxLineBytes, maxLines);
    }

    @Override
    public SourceType sourceType() {
        return SourceType.BANK_STATEMENT;
    }

    @Override
    ParsedLine read(long lineNumber, List<String> fields, StatementContext context) {
        String lineId = FieldRules.identifier(fields.get(0));
        LocalDate bookingDate = FieldRules.date(fields.get(1));
        LocalDate valueDate = FieldRules.date(fields.get(2));
        BigDecimal amountText = FieldRules.decimal(fields.get(3));
        CurrencyCode currency = FieldRules.currency(fields.get(4));
        long amount = FieldRules.minorUnits(amountText, currency);
        Optional<String> reference = FieldRules.optionalText(fields.get(5), MAX_TEXT_LENGTH);
        Optional<String> description = FieldRules.optionalText(fields.get(6), MAX_TEXT_LENGTH);
        if (amount == 0) {
            throw new LineRejected(ValidationCode.INVALID_AMOUNT);
        }
        BatchIdPattern batchIdPattern = context.source().batchIdPattern()
                .orElseThrow(() -> new IllegalStateException("a bank statement source always has a batch-id-pattern"));
        return new ParsedLine.Bank(lineNumber, new BankLine(UUID.randomUUID(), context.fileId(), context.source().code(),
                lineId, bookingDate, valueDate, Money.of(amount, currency), reference,
                batchIdPattern.extract(reference), description));
    }
}
