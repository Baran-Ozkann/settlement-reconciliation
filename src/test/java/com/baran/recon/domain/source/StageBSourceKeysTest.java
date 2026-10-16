package com.baran.recon.domain.source;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baran.recon.domain.item.SourceCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 8.1: a PSP source settles to a bank source, which holds the batch grace period")
class StageBSourceKeysTest {

    private static final BatchIdPattern PATTERN = BatchIdPattern.of("BATCH[-_]?([A-Za-z0-9_-]{1,64})");
    private static final SourceCode PSP = SourceCode.of("PSP_ALPHA");
    private static final SourceCode BANK = SourceCode.of("BANK_MAIN");

    @Test
    @DisplayName("TDD 8.1: a PSP source names its bank source, and a bank source's batch grace defaults to 2 business days")
    void pspSettlesToItsBankSource() {
        SourceDefinition psp = SourceDefinition.psp(PSP, Set.of(), BANK);
        SourceDefinition bank = SourceDefinition.bank(BANK, PATTERN);

        assertThat(psp.settlesTo()).contains(BANK);
        assertThat(psp.graceDaysBatchUnpaid()).isEmpty();
        assertThat(bank.settlesTo()).isEmpty();
        assertThat(bank.graceDaysBatchUnpaid()).hasValue(2);
        assertThat(ConfiguredSources.of(List.of(psp, bank)).all()).containsExactly(psp, bank);
    }

    @Test
    @DisplayName("TDD 5.3: a bank source's run reads the batches of every PSP source that settles to it, in "
            + "configuration order, and of no other")
    void settlingToNamesThePspSourcesOfABank() {
        SourceCode other = SourceCode.of("BANK_OTHER");
        ConfiguredSources sources = ConfiguredSources.of(List.of(
                SourceDefinition.psp(SourceCode.of("PSP_GAMMA"), Set.of(), BANK),
                SourceDefinition.bank(BANK, PATTERN),
                SourceDefinition.psp(SourceCode.of("PSP_ALPHA"), Set.of(), other),
                SourceDefinition.psp(SourceCode.of("PSP_BETA"), Set.of(), BANK),
                SourceDefinition.bank(other, PATTERN),
                SourceDefinition.bank(SourceCode.of("BANK_UNSETTLED"), PATTERN)));

        assertThat(sources.settlingTo(BANK)).containsExactly(SourceCode.of("PSP_GAMMA"), SourceCode.of("PSP_BETA"));
        assertThat(sources.settlingTo(other)).containsExactly(SourceCode.of("PSP_ALPHA"));
        assertThat(sources.settlingTo(SourceCode.of("BANK_UNSETTLED"))).isEmpty();
        assertThat(sources.settlingTo(SourceCode.of("PSP_BETA"))).isEmpty();
    }

    @Test
    @DisplayName("TDD 8.1: a PSP source without settles-to is refused, naming the source and the key")
    void pspSourceNeedsSettlesTo() {
        assertThatThrownBy(() -> new SourceDefinition(PSP, SourceType.PSP_SETTLEMENT, Set.of(), Optional.empty(),
                Optional.of(StageASettings.TDD_DEFAULTS), Optional.empty(), OptionalInt.empty()))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("PSP_ALPHA").hasMessageContaining("settles-to");
    }

    @Test
    @DisplayName("TDD 8.1: settles-to on a bank source is refused, naming the source and the key")
    void bankSourceCannotSettleTo() {
        assertThatThrownBy(() -> new SourceDefinition(BANK, SourceType.BANK_STATEMENT, Set.of(), Optional.of(PATTERN),
                Optional.empty(), Optional.of(SourceCode.of("BANK_OTHER")), OptionalInt.of(2)))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("BANK_MAIN").hasMessageContaining("settles-to");
    }

    @Test
    @DisplayName("TDD 8.1: settles-to must name a configured bank statement source; a missing or PSP source is refused")
    void settlesToMustNameAConfiguredBankSource() {
        SourceDefinition psp = SourceDefinition.psp(PSP, Set.of(), BANK);
        SourceDefinition otherPsp = SourceDefinition.psp(SourceCode.of("PSP_BETA"), Set.of(), PSP);

        assertThatThrownBy(() -> ConfiguredSources.of(List.of(psp)))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("PSP_ALPHA").hasMessageContaining("settles-to")
                .hasMessageContaining("BANK_MAIN, which is not a configured source");
        assertThatThrownBy(() -> ConfiguredSources.of(List.of(psp, SourceDefinition.bank(BANK, PATTERN), otherPsp)))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("PSP_BETA").hasMessageContaining("settles-to")
                .hasMessageContaining("PSP_ALPHA, which is not a bank statement source");
    }

    @Test
    @DisplayName("TDD 8.1: zero batch grace days is allowed; a negative number is refused, naming the source and the key")
    void negativeBatchGraceIsRefused() {
        assertThat(SourceDefinition.bank(BANK, PATTERN, 0).graceDaysBatchUnpaid()).hasValue(0);
        assertThatThrownBy(() -> SourceDefinition.bank(BANK, PATTERN, -1))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("BANK_MAIN").hasMessageContaining("grace-days-batch-unpaid");
    }

    @Test
    @DisplayName("TDD 8.1: a bank source needs its batch grace, and a PSP source cannot have one")
    void batchGraceBelongsToBankSourcesOnly() {
        assertThatThrownBy(() -> new SourceDefinition(BANK, SourceType.BANK_STATEMENT, Set.of(), Optional.of(PATTERN),
                Optional.empty(), Optional.empty(), OptionalInt.empty()))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("BANK_MAIN").hasMessageContaining("grace-days-batch-unpaid");
        assertThatThrownBy(() -> new SourceDefinition(PSP, SourceType.PSP_SETTLEMENT, Set.of(), Optional.empty(),
                Optional.of(StageASettings.TDD_DEFAULTS), Optional.of(BANK), OptionalInt.of(2)))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("PSP_ALPHA").hasMessageContaining("grace-days-batch-unpaid");
    }
}
