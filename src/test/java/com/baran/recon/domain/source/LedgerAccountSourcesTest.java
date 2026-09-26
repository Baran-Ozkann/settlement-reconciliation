package com.baran.recon.domain.source;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baran.recon.domain.item.SourceCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FR-LED-2: ledger accounts mapped to configured sources")
class LedgerAccountSourcesTest {

    private static final UUID CLEARING = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_CLEARING = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID UNMAPPED = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    @Test
    @DisplayName("FR-LED-2: a mapped account resolves to its source, an unmapped one to nothing")
    void resolvesMappedAccountsOnly() {
        LedgerAccountSources sources = LedgerAccountSources.of(List.of(
                psp("PSP_ALPHA", CLEARING),
                psp("PSP_BETA", OTHER_CLEARING),
                new SourceDefinition(SourceCode.of("BANK_MAIN"), SourceType.BANK_STATEMENT, Set.of())));

        assertThat(sources.sourceOf(CLEARING)).contains(SourceCode.of("PSP_ALPHA"));
        assertThat(sources.sourceOf(OTHER_CLEARING)).contains(SourceCode.of("PSP_BETA"));
        assertThat(sources.sourceOf(UNMAPPED)).isEmpty();
    }

    @Test
    @DisplayName("no configured source maps nothing")
    void emptyConfigurationMapsNothing() {
        assertThat(LedgerAccountSources.of(List.of()).sourceOf(CLEARING)).isEmpty();
    }

    @Test
    @DisplayName("an account mapped to two sources is refused, not resolved")
    void accountMappedTwiceIsRefused() {
        assertThatThrownBy(() -> LedgerAccountSources.of(List.of(psp("PSP_ALPHA", CLEARING), psp("PSP_BETA", CLEARING))))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("PSP_ALPHA").hasMessageContaining("PSP_BETA");
    }

    @Test
    @DisplayName("a source code configured twice is refused")
    void sourceConfiguredTwiceIsRefused() {
        assertThatThrownBy(() -> LedgerAccountSources.of(List.of(psp("PSP_ALPHA", CLEARING), psp("PSP_ALPHA", OTHER_CLEARING))))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("configured twice");
    }

    @Test
    @DisplayName("a bank statement source cannot map ledger accounts")
    void bankSourceWithLedgerAccountsIsRefused() {
        assertThatThrownBy(() -> new SourceDefinition(SourceCode.of("BANK_MAIN"), SourceType.BANK_STATEMENT, Set.of(CLEARING)))
                .isInstanceOf(InvalidSourceConfigurationException.class);
    }

    private static SourceDefinition psp(String code, UUID account) {
        return new SourceDefinition(SourceCode.of(code), SourceType.PSP_SETTLEMENT, Set.of(account));
    }
}
