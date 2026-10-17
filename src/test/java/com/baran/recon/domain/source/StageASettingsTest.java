package com.baran.recon.domain.source;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baran.recon.domain.item.SourceCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 8.1: a PSP source's Stage A windows and grace periods")
class StageASettingsTest {

    private static final SourceCode ALPHA = SourceCode.of("PSP_ALPHA");
    private static final SourceType PSP = SourceType.PSP_SETTLEMENT;
    private static final BatchIdPattern PATTERN = BatchIdPattern.of("BATCH[-_]?([A-Za-z0-9_-]{1,64})");

    @Test
    @DisplayName("the defaults are TDD 8.1's: a window of 2 and grace periods of 3 and 1 business days")
    void defaultsAreTheTddValues() {
        assertThat(StageASettings.TDD_DEFAULTS).isEqualTo(new StageASettings(2, 3, 1));
        assertThat(SourceDefinition.psp(SourceCode.of("PSP_ALPHA"), Set.of(), SourceCode.of("BANK_MAIN")).stageA())
                .contains(StageASettings.TDD_DEFAULTS);
    }

    @Test
    @DisplayName("the settings alone allow zero business days and refuse a negative number, naming its key, so no "
            + "code path can build negative ones")
    void settingsAloneRefuseNegativeDays() {
        assertThat(new StageASettings(0, 0, 0).valueDateWindowDays()).isZero();
        assertThatThrownBy(() -> new StageASettings(-1, 3, 1))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessage("value-date-window-days is a number of business days, not negative");
        assertThatThrownBy(() -> new StageASettings(2, -1, 1))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessage("grace-days-ledger-unmatched is a number of business days, not negative");
        assertThatThrownBy(() -> new StageASettings(2, 3, -1))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessage("grace-days-psp-unmatched is a number of business days, not negative");
    }

    @Test
    @DisplayName("built for a source, the settings allow zero business days and refuse a negative number before the "
            + "record does, naming the source and the key; on a bank source they are misplaced whatever their values")
    void sourceRefusesNegativeDaysNamingItself() {
        assertThat(SourceDefinition.stageASettings(ALPHA, PSP, 0, 0, 0)).isEqualTo(new StageASettings(0, 0, 0));
        assertThatThrownBy(() -> SourceDefinition.stageASettings(ALPHA, PSP, -1, 3, 1))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessage("value-date-window-days of PSP source PSP_ALPHA is a number of business days, not negative");
        assertThatThrownBy(() -> SourceDefinition.stageASettings(ALPHA, PSP, 2, -1, 1))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessage("grace-days-ledger-unmatched of PSP source PSP_ALPHA is a number of business days, not "
                        + "negative");
        assertThatThrownBy(() -> SourceDefinition.stageASettings(ALPHA, PSP, 2, 3, -1))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessage("grace-days-psp-unmatched of PSP source PSP_ALPHA is a number of business days, not negative");
        assertThatThrownBy(() -> SourceDefinition.stageASettings(SourceCode.of("BANK_MAIN"),
                SourceType.BANK_STATEMENT, -1, 3, 1))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessage("source BANK_MAIN is a bank statement source; only a PSP source has value-date-window-days, "
                        + "grace-days-ledger-unmatched and grace-days-psp-unmatched");
    }

    @Test
    @DisplayName("a PSP source needs its Stage A settings, and a bank source cannot have them; each refusal names the "
            + "source and the keys")
    void settingsBelongToPspSourcesOnly() {
        assertThatThrownBy(() -> new SourceDefinition(SourceCode.of("PSP_ALPHA"), SourceType.PSP_SETTLEMENT, Set.of(),
                Optional.empty(), Optional.empty(), Optional.of(SourceCode.of("BANK_MAIN")),
                OptionalInt.empty()))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessage("PSP source PSP_ALPHA needs value-date-window-days, grace-days-ledger-unmatched and "
                        + "grace-days-psp-unmatched");
        assertThatThrownBy(() -> new SourceDefinition(SourceCode.of("BANK_MAIN"), SourceType.BANK_STATEMENT, Set.of(),
                Optional.of(PATTERN), Optional.of(StageASettings.TDD_DEFAULTS), Optional.empty(),
                OptionalInt.of(2)))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessage("source BANK_MAIN is a bank statement source; only a PSP source has value-date-window-days, "
                        + "grace-days-ledger-unmatched and grace-days-psp-unmatched");
    }
}
