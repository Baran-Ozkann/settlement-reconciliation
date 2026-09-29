package com.baran.recon.domain.source;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.baran.recon.domain.item.SourceCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 8.1: a bank source's batch-id-pattern and the source rules around it")
class BatchIdPatternTest {

    /** The pattern TDD 8.1 gives as its example. */
    private static final BatchIdPattern TDD_EXAMPLE = BatchIdPattern.of("BATCH[-_]?([A-Za-z0-9_-]{1,64})");

    @ParameterizedTest(name = "\"{0}\" yields \"{1}\"")
    @CsvSource({
            "BATCH-B-001,B-001",
            "BATCH_2026_09_24,2026_09_24",
            "BATCHX1,X1",
            "Settlement PSP ALPHA BATCH-77 payout,77",
    })
    @DisplayName("the first capturing group is the batch id, wherever the pattern is found")
    void extractsTheFirstGroup(String reference, String batchId) {
        assertThat(TDD_EXAMPLE.extract(Optional.of(reference))).contains(batchId);
    }

    @ParameterizedTest(name = "\"{0}\" yields nothing")
    @ValueSource(strings = {"no batch here", "batch-lowercase-is-not-batch", "BATCH"})
    void noMatchYieldsNothing(String reference) {
        assertThat(TDD_EXAMPLE.extract(Optional.of(reference))).isEmpty();
    }

    @Test
    @DisplayName("a line without a reference has no batch id")
    void absentReferenceYieldsNothing() {
        assertThat(TDD_EXAMPLE.extract(Optional.empty())).isEmpty();
    }

    @Test
    @DisplayName("a captured text that is not a valid batch id is not extracted")
    void invalidCaptureIsNotExtracted() {
        BatchIdPattern loose = BatchIdPattern.of("REF:(.+)");

        assertThat(loose.extract(Optional.of("REF:has space"))).isEmpty();
        assertThat(loose.extract(Optional.of("REF:" + "x".repeat(65)))).isEmpty();
        assertThat(loose.extract(Optional.of("REF:B-9"))).contains("B-9");
    }

    @Test
    @DisplayName("an optional group that did not take part in the match extracts nothing")
    void unmatchedGroupIsNothing() {
        assertThat(BatchIdPattern.of("PAYOUT(?:-([A-Z0-9]+))?").extract(Optional.of("PAYOUT"))).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @ValueSource(strings = {"", "BATCH-[A-Z0-9]+", "BATCH-(unclosed"})
    @DisplayName("an empty pattern, one without a capturing group, or an invalid one stops startup")
    void unusablePatternIsRefused(String regex) {
        assertThatThrownBy(() -> BatchIdPattern.of(regex)).isInstanceOf(InvalidSourceConfigurationException.class);
    }

    @Test
    @DisplayName("two patterns with the same text are equal")
    void equalityByText() {
        assertThat(BatchIdPattern.of("B-(\\d+)")).isEqualTo(BatchIdPattern.of("B-(\\d+)"))
                .hasSameHashCodeAs(BatchIdPattern.of("B-(\\d+)"))
                .isNotEqualTo(BatchIdPattern.of("C-(\\d+)"))
                .hasToString("B-(\\d+)");
    }

    @Test
    @DisplayName("a bank source needs a pattern, and a PSP source cannot have one")
    void patternBelongsToBankSourcesOnly() {
        assertThatThrownBy(() -> new SourceDefinition(SourceCode.of("BANK_MAIN"), SourceType.BANK_STATEMENT, Set.of(),
                Optional.empty()))
                .isInstanceOf(InvalidSourceConfigurationException.class).hasMessageContaining("batch-id-pattern");
        assertThatThrownBy(() -> new SourceDefinition(SourceCode.of("PSP_ALPHA"), SourceType.PSP_SETTLEMENT, Set.of(),
                Optional.of(TDD_EXAMPLE)))
                .isInstanceOf(InvalidSourceConfigurationException.class).hasMessageContaining("batch-id-pattern");
    }

    @Test
    @DisplayName("FR-ING-2: sources are found by code; an unknown code finds nothing; a repeated code is refused")
    void configuredSourcesByCode() {
        SourceDefinition psp = SourceDefinition.psp(SourceCode.of("PSP_ALPHA"), Set.of());
        SourceDefinition bank = SourceDefinition.bank(SourceCode.of("BANK_MAIN"), TDD_EXAMPLE);
        ConfiguredSources sources = ConfiguredSources.of(List.of(psp, bank));

        assertThat(sources.find(SourceCode.of("BANK_MAIN"))).contains(bank);
        assertThat(sources.find(SourceCode.of("PSP_BETA"))).isEmpty();
        assertThat(sources.all()).containsExactly(psp, bank);
        assertThatThrownBy(() -> ConfiguredSources.of(List.of(psp, psp)))
                .isInstanceOf(InvalidSourceConfigurationException.class).hasMessageContaining("configured twice");
    }
}
