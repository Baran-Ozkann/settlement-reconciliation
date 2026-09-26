package com.baran.recon.adapters.in.kafka;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.baran.recon.application.ledger.LedgerEvent;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.SupportedCurrencies;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("FR-LED-9, TDD 6: a projected value this service does not know is warned about once and counted")
class UnfamiliarValueReporterTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UnfamiliarValueReporter reporter =
            new UnfamiliarValueReporter(meters, new SupportedCurrencies(Set.of(CurrencyCode.of("TRY"))));

    @Test
    @DisplayName("FR-LED-9: an unmapped tx_type is logged at WARN the first time only, and counted every time")
    void unmappedTxTypeWarnsOnceCountsEachTime(CapturedOutput output) {
        reporter.projected(event("FEE"));
        reporter.projected(event("FEE"));
        reporter.projected(event("ADJUSTMENT"));

        assertThat(count("FEE")).isEqualTo(2);
        assertThat(count("ADJUSTMENT")).isEqualTo(1);
        assertThat(output.getOut().split("with tx_type FEE,", -1)).hasSize(2);
        assertThat(output.getOut()).contains("with tx_type ADJUSTMENT,");
    }

    @Test
    @DisplayName("a known tx_type is neither logged nor counted")
    void knownTxTypeIsQuiet(CapturedOutput output) {
        reporter.projected(event("TRANSFER"));
        reporter.projected(event("FUNDING"));
        reporter.projected(event("REVERSAL"));

        assertThat(meters.find(UnfamiliarValueReporter.UNMAPPED_TX_TYPE).counters()).isEmpty();
        assertThat(output.getOut()).doesNotContain("does not map");
    }

    @Test
    @DisplayName("a value that is no enum name is logged and tagged as other, so it can forge no log line and no series")
    void hostileValueIsCollapsed(CapturedOutput output) {
        reporter.projected(event("fee\nERROR forged line"));
        reporter.projected(event("X".repeat(200)));

        assertThat(count("other")).isEqualTo(2);
        assertThat(output.getOut()).doesNotContain("forged line").doesNotContain("X".repeat(33));
        assertThat(meters.find(UnfamiliarValueReporter.UNMAPPED_TX_TYPE).counters()).hasSize(1);
    }

    @Test
    @DisplayName("a real ISO 4217 currency outside the supported set is logged at WARN the first time only, and counted every time")
    void unsupportedCurrencyWarnsOnceCountsEachTime(CapturedOutput output) {
        reporter.projected(event("TRANSFER", "USD"));
        reporter.projected(event("TRANSFER", "USD"));
        reporter.projected(event("TRANSFER", "EUR"));

        assertThat(currencyCount("USD")).isEqualTo(2);
        assertThat(currencyCount("EUR")).isEqualTo(1);
        assertThat(output.getOut().split("ledger entry in USD,", -1)).hasSize(2);
        assertThat(meters.find(UnfamiliarValueReporter.UNMAPPED_TX_TYPE).counters()).isEmpty();
    }

    @Test
    @DisplayName("a supported currency is neither logged nor counted")
    void supportedCurrencyIsQuiet(CapturedOutput output) {
        reporter.projected(event("TRANSFER", "TRY"));

        assertThat(meters.find(UnfamiliarValueReporter.UNSUPPORTED_CURRENCY).counters()).isEmpty();
        assertThat(output.getOut()).doesNotContain("not a supported currency");
    }

    private double currencyCount(String currency) {
        return meters.get(UnfamiliarValueReporter.UNSUPPORTED_CURRENCY).tag("currency", currency).counter().count();
    }

    private double count(String txType) {
        return meters.get(UnfamiliarValueReporter.UNMAPPED_TX_TYPE).tag("tx_type", txType).counter().count();
    }

    private static LedgerEvent event(String txType) {
        return event(txType, "TRY");
    }

    private static LedgerEvent event(String txType, String currency) {
        return new LedgerEvent(1, Optional.of(2L), Optional.of(Instant.parse("2026-09-24T08:15:42.318204Z")),
                UUID.randomUUID(), UUID.randomUUID(), 100, CurrencyCode.of(currency), txType);
    }
}
