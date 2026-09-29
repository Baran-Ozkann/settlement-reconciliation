package com.baran.recon.application.statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FR-ING-7: the invalid-line threshold, in basis points, exact in integers")
class IngestionLimitsTest {

    @ParameterizedTest(name = "{0} bp, {1} invalid of {2}: rejected {3}")
    @CsvSource({
            "0, 0, 0, false",
            "0, 0, 1000, false",
            "0, 1, 1000000, true",
            "100, 1, 100, false",
            "100, 2, 100, true",
            "100, 10000, 1000000, false",
            "100, 10001, 1000000, true",
            "10000, 100, 100, false",
            "1, 1, 10000, false",
            "1, 1, 9999, true",
    })
    @DisplayName("FR-ING-7: rejected only when the ratio exceeds the threshold; at the threshold it is ingested")
    void rejectsAboveTheThresholdOnly(int basisPoints, long invalid, long lines, boolean rejected) {
        assertThat(new IngestionLimits(4096, 2_000_000, basisPoints).rejects(invalid, lines)).isEqualTo(rejected);
    }

    @Test
    @DisplayName("limits that cannot hold are refused")
    void impossibleLimitsAreRefused() {
        assertThatThrownBy(() -> new IngestionLimits(0, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionLimits(1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionLimits(1, 1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestionLimits(1, 1, 10_001)).isInstanceOf(IllegalArgumentException.class);
    }
}
