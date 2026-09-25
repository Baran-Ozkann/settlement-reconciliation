package com.baran.recon.domain.item;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 7.1: PSP settlement lines keep the report format's rules")
class PspLineTest {

    private static final CurrencyCode TRY = CurrencyCode.of("TRY");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);

    @Test
    @DisplayName("a payment with net = gross - fee is accepted")
    void validPayment() {
        PspLine line = line(PspLineType.PAYMENT, 10_000, 250, 9_750);

        assertThat(line.currency()).isEqualTo(TRY);
        assertThat(line.batchKey()).isEqualTo(new BatchKey(SourceCode.of("PSP_ALPHA"), "B-001"));
    }

    @Test
    @DisplayName("refunds and chargebacks carry a negative gross")
    void negativeGrossForRefundAndChargeback() {
        assertThat(line(PspLineType.REFUND, -5_000, 0, -5_000).gross().isNegative()).isTrue();
        assertThat(line(PspLineType.CHARGEBACK, -5_000, 100, -5_100).net()).isEqualTo(Money.of(-5_100, TRY));
    }

    @Test
    @DisplayName("NET_AMOUNT_MISMATCH: net must equal gross - fee exactly")
    void netMustEqualGrossMinusFee() {
        assertThatThrownBy(() -> line(PspLineType.PAYMENT, 10_000, 250, 9_751))
                .isInstanceOf(InvalidItemException.class);
    }

    @Test
    @DisplayName("SIGN_TYPE_MISMATCH: a payment with a negative gross, or a refund with a positive one, is rejected")
    void signMustMatchType() {
        assertThatThrownBy(() -> line(PspLineType.PAYMENT, -10_000, 0, -10_000))
                .isInstanceOf(InvalidItemException.class);
        assertThatThrownBy(() -> line(PspLineType.REFUND, 10_000, 0, 10_000))
                .isInstanceOf(InvalidItemException.class);
        assertThatThrownBy(() -> line(PspLineType.PAYMENT, 0, 0, 0))
                .isInstanceOf(InvalidItemException.class);
    }

    @Test
    @DisplayName("a negative fee is rejected")
    void negativeFeeIsRejected() {
        assertThatThrownBy(() -> line(PspLineType.PAYMENT, 10_000, -1, 10_001))
                .isInstanceOf(InvalidItemException.class);
    }

    @Test
    @DisplayName("amounts in two currencies are rejected")
    void mixedCurrenciesAreRejected() {
        assertThatThrownBy(() -> new PspLine(UUID.randomUUID(), UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), "L-1",
                Optional.empty(), "B-001", PspLineType.PAYMENT, DAY, DAY, Money.of(100, TRY),
                Money.of(0, CurrencyCode.of("EUR")), Money.of(100, TRY)))
                .isInstanceOf(InvalidItemException.class);
    }

    @Test
    @DisplayName("DATE_ORDER: a transaction date after the value date is rejected")
    void transactionDateNotAfterValueDate() {
        assertThatThrownBy(() -> new PspLine(UUID.randomUUID(), UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), "L-1",
                Optional.empty(), "B-001", PspLineType.PAYMENT, DAY.plusDays(1), DAY, Money.of(100, TRY),
                Money.zero(TRY), Money.of(100, TRY)))
                .isInstanceOf(InvalidItemException.class);
    }

    @ParameterizedTest(name = "line_id \"{0}\" is rejected")
    @ValueSource(strings = {"", "has space", "semi;colon", "0123456789012345678901234567890123456789012345678901234567890123x"})
    @DisplayName("line_id must be 1-64 characters of [A-Za-z0-9_-]")
    void lineIdFormat(String lineId) {
        assertThatThrownBy(() -> new PspLine(UUID.randomUUID(), UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), lineId,
                Optional.empty(), "B-001", PspLineType.PAYMENT, DAY, DAY, Money.of(100, TRY),
                Money.zero(TRY), Money.of(100, TRY)))
                .isInstanceOf(InvalidItemException.class);
    }

    @Test
    @DisplayName("an empty reference is absent, never an empty string")
    void emptyReferenceIsRejected() {
        assertThatThrownBy(() -> new PspLine(UUID.randomUUID(), UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), "L-1",
                Optional.of(""), "B-001", PspLineType.PAYMENT, DAY, DAY, Money.of(100, TRY),
                Money.zero(TRY), Money.of(100, TRY)))
                .isInstanceOf(InvalidItemException.class);
    }

    private static PspLine line(PspLineType type, long gross, long fee, long net) {
        return new PspLine(UUID.randomUUID(), UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), "L-1",
                Optional.of("5f0c7a1e-0000-4000-8000-000000000001"), "B-001", type, DAY, DAY,
                Money.of(gross, TRY), Money.of(fee, TRY), Money.of(net, TRY));
    }
}
