package com.baran.recon.domain.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FR-LED-9: the tx_type values this service knows")
class LedgerTxTypeTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"TRANSFER", "FUNDING", "REVERSAL"})
    @DisplayName("the three types the ledger's producer emits today are known")
    void producerTypesAreKnown(String txType) {
        assertThat(LedgerTxType.isKnown(txType)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"FEE", "ADJUSTMENT", "transfer", "TRANSFER ", ""})
    @DisplayName("FR-LED-9: anything else, including the two the ledger's CHECK admits, is not")
    void everythingElseIsUnknown(String txType) {
        assertThat(LedgerTxType.isKnown(txType)).isFalse();
    }
}
