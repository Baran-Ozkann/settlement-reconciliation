package com.baran.recon.adapters.in.file;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.baran.recon.adapters.in.file.FieldRules.LineRejected;
import com.baran.recon.application.port.ParsedLine;
import com.baran.recon.application.port.StatementContext;
import com.baran.recon.application.statement.IngestionLimits;
import com.baran.recon.domain.item.PspLine;
import com.baran.recon.domain.item.PspLineType;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.source.SourceType;
import com.baran.recon.domain.statement.ValidationCode;

/**
 * PSP settlement report CSV v1 (TDD 7.1). Rules are applied in this order, and a line is refused
 * with the first it breaks:
 * <ol>
 *   <li>each column in header order; the amounts' shape here, their scale once the currency, the
 *       last column, is known;</li>
 *   <li>the scale of gross, fee and net, then fee not negative;</li>
 *   <li>the gross sign against the type ({@code SIGN_TYPE_MISMATCH});</li>
 *   <li>net equal to gross minus fee, exactly ({@code NET_AMOUNT_MISMATCH});</li>
 *   <li>transaction date not after value date ({@code DATE_ORDER}).</li>
 * </ol>
 * A currency that is real ISO 4217 but not a supported one is read like any other (TDD 7.3).
 */
@Component
final class PspSettlementParser extends CsvStatementParser {

    static final String HEADER =
            "line_id,transaction_reference,batch_id,transaction_date,value_date,type,gross_amount,fee_amount,net_amount,currency";

    private static final int MAX_REFERENCE_LENGTH = 64;

    @Autowired
    PspSettlementParser(IngestionLimits limits) {
        this(limits.maxLineBytes(), limits.maxLines());
    }

    PspSettlementParser(int maxLineBytes, long maxLines) {
        super(HEADER, maxLineBytes, maxLines);
    }

    @Override
    public SourceType sourceType() {
        return SourceType.PSP_SETTLEMENT;
    }

    @Override
    ParsedLine read(long lineNumber, List<String> fields, StatementContext context) {
        String lineId = FieldRules.identifier(fields.get(0));
        Optional<String> reference = FieldRules.optionalText(fields.get(1), MAX_REFERENCE_LENGTH);
        String batchId = FieldRules.identifier(fields.get(2));
        LocalDate transactionDate = FieldRules.date(fields.get(3));
        LocalDate valueDate = FieldRules.date(fields.get(4));
        PspLineType type = type(fields.get(5));
        BigDecimal grossText = FieldRules.decimal(fields.get(6));
        BigDecimal feeText = FieldRules.decimal(fields.get(7));
        BigDecimal netText = FieldRules.decimal(fields.get(8));
        CurrencyCode currency = FieldRules.currency(fields.get(9));

        long gross = FieldRules.minorUnits(grossText, currency);
        long fee = FieldRules.minorUnits(feeText, currency);
        long net = FieldRules.minorUnits(netText, currency);
        if (fee < 0) {
            throw new LineRejected(ValidationCode.INVALID_AMOUNT);
        }
        if (type.hasPositiveGross() ? gross <= 0 : gross >= 0) {
            throw new LineRejected(ValidationCode.SIGN_TYPE_MISMATCH);
        }
        if (net != grossMinusFee(gross, fee)) {
            throw new LineRejected(ValidationCode.NET_AMOUNT_MISMATCH);
        }
        if (transactionDate.isAfter(valueDate)) {
            throw new LineRejected(ValidationCode.DATE_ORDER);
        }
        return new ParsedLine.Psp(lineNumber, new PspLine(UUID.randomUUID(), context.fileId(), context.source().code(),
                lineId, reference, batchId, type, transactionDate, valueDate,
                Money.of(gross, currency), Money.of(fee, currency), Money.of(net, currency)));
    }

    private static PspLineType type(String field) {
        FieldRules.required(field);
        return switch (field) {
            case "PAYMENT" -> PspLineType.PAYMENT;
            case "REFUND" -> PspLineType.REFUND;
            case "CHARGEBACK" -> PspLineType.CHARGEBACK;
            default -> throw new LineRejected(ValidationCode.INVALID_FORMAT);
        };
    }

    /** A difference that does not fit a long cannot equal any net amount the file could state. */
    private static long grossMinusFee(long gross, long fee) {
        try {
            return Math.subtractExact(gross, fee);
        } catch (ArithmeticException overflow) {
            throw new LineRejected(ValidationCode.INVALID_AMOUNT);
        }
    }
}
