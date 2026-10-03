package com.baran.recon.domain.source;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baran.recon.domain.item.SourceCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 8.1: a PSP source's Stage A windows and grace periods")
class StageASettingsTest {

    private static final BatchIdPattern PATTERN = BatchIdPattern.of("BATCH[-_]?([A-Za-z0-9_-]{1,64})");

    @Test
    @DisplayName("the defaults are TDD 8.1's: a window of 2 and grace periods of 3 and 1 business days")
    void defaultsAreTheTddValues() {
        assertThat(StageASettings.TDD_DEFAULTS).isEqualTo(new StageASettings(2, 3, 1));
        assertThat(SourceDefinition.psp(SourceCode.of("PSP_ALPHA"), Set.of()).stageA())
                .contains(StageASettings.TDD_DEFAULTS);
    }

    @Test
    @DisplayName("zero business days is allowed; a negative number is refused, naming its key")
    void negativeDaysAreRefused() {
        assertThat(new StageASettings(0, 0, 0).valueDateWindowDays()).isZero();
        assertThatThrownBy(() -> new StageASettings(-1, 3, 1))
                .isInstanceOf(InvalidSourceConfigurationException.class).hasMessageContaining("value-date-window-days");
        assertThatThrownBy(() -> new StageASettings(2, -1, 1))
                .isInstanceOf(InvalidSourceConfigurationException.class).hasMessageContaining("grace-days-ledger-unmatched");
        assertThatThrownBy(() -> new StageASettings(2, 3, -1))
                .isInstanceOf(InvalidSourceConfigurationException.class).hasMessageContaining("grace-days-psp-unmatched");
    }

    @Test
    @DisplayName("a PSP source needs its Stage A settings, and a bank source cannot have them")
    void settingsBelongToPspSourcesOnly() {
        assertThatThrownBy(() -> new SourceDefinition(SourceCode.of("PSP_ALPHA"), SourceType.PSP_SETTLEMENT, Set.of(),
                Optional.empty(), Optional.empty()))
                .isInstanceOf(InvalidSourceConfigurationException.class).hasMessageContaining("Stage A");
        assertThatThrownBy(() -> new SourceDefinition(SourceCode.of("BANK_MAIN"), SourceType.BANK_STATEMENT, Set.of(),
                Optional.of(PATTERN), Optional.of(StageASettings.TDD_DEFAULTS)))
                .isInstanceOf(InvalidSourceConfigurationException.class).hasMessageContaining("only a PSP source");
    }
}
