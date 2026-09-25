package com.baran.recon.domain.item;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 7.2, 8.1, 10: bank lines, source codes and batch identity")
class BankLineAndIdentityTest {

    private static final CurrencyCode TRY = CurrencyCode.of("TRY");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);
    private static final SourceCode BANK = SourceCode.of("BANK_MAIN");

    @Test
    @DisplayName("a bank credit with an extracted batch id is accepted")
    void validBankLine() {
        BankLine line = bankLine(Money.of(97_500, TRY), Optional.of("BATCH-B-001"), Optional.of("B-001"));

        assertThat(line.extractedBatchId()).contains("B-001");
    }

    @Test
    @DisplayName("a zero bank amount is rejected")
    void zeroAmountIsRejected() {
        assertThatThrownBy(() -> bankLine(Money.zero(TRY), Optional.empty(), Optional.empty()))
                .isInstanceOf(InvalidItemException.class);
    }

    @Test
    @DisplayName("a reference longer than 140 characters is rejected")
    void longReferenceIsRejected() {
        assertThatThrownBy(() -> bankLine(Money.of(1, TRY), Optional.of("r".repeat(141)), Optional.empty()))
                .isInstanceOf(InvalidItemException.class);
    }

    @Test
    @DisplayName("an extracted batch id must be a valid batch id")
    void extractedBatchIdFormat() {
        assertThatThrownBy(() -> bankLine(Money.of(1, TRY), Optional.empty(), Optional.of("not valid")))
                .isInstanceOf(InvalidItemException.class);
    }

    @Test
    @DisplayName("TDD 10: a batch resolves to the same UUID every time")
    void batchIdIsStable() {
        BatchKey batch = new BatchKey(SourceCode.of("PSP_ALPHA"), "B-001");

        assertThat(batch.itemId()).isEqualTo(new BatchKey(SourceCode.of("PSP_ALPHA"), "B-001").itemId());
        assertThat(batch.itemId().version()).isEqualTo(3);
    }

    @Test
    @DisplayName("TDD 8.2: the same batch id under two PSP sources is two batches")
    void batchIdIsPerSource() {
        assertThat(new BatchKey(SourceCode.of("PSP_ALPHA"), "B-001").itemId())
                .isNotEqualTo(new BatchKey(SourceCode.of("PSP_BETA"), "B-001").itemId())
                .isNotEqualTo(new BatchKey(SourceCode.of("PSP_ALPHA"), "B-002").itemId());
    }

    @ParameterizedTest(name = "source code \"{0}\" is rejected")
    @NullSource
    @ValueSource(strings = {"", "psp_alpha", "1PSP", "PSP-ALPHA", "PSP ALPHA"})
    @DisplayName("a source code is upper-case letters, digits and underscores, starting with a letter")
    void sourceCodeFormat(String code) {
        assertThatThrownBy(() -> SourceCode.of(code)).isInstanceOf(InvalidItemException.class);
    }

    @Test
    @DisplayName("a source code prints as itself")
    void sourceCodePrintsAsItself() {
        assertThat(BANK).hasToString("BANK_MAIN");
    }

    private static BankLine bankLine(Money amount, Optional<String> reference, Optional<String> batchId) {
        return new BankLine(UUID.randomUUID(), UUID.randomUUID(), BANK, "S-1", DAY, DAY, amount,
                reference, batchId, Optional.of("Settlement Test Merchant 001"));
    }
}
