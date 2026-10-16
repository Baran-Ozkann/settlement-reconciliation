package com.baran.recon.config;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.baran.recon.domain.calendar.BusinessCalendar;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.source.ConfiguredSources;
import com.baran.recon.domain.source.InvalidSourceConfigurationException;
import com.baran.recon.domain.source.LedgerAccountSources;
import com.baran.recon.domain.source.SourceDefinition;
import com.baran.recon.domain.source.SourceType;
import com.baran.recon.domain.source.StageASettings;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FR-LED-2, FR-ING-2: recon.sources binds to the configured sources")
class SourcesConfigurationTest {

    private static final String CLEARING = "00000000-0000-4000-8000-000000000001";
    private static final String PATTERN = "BATCH[-_]?([A-Za-z0-9_-]{1,64})";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SourcesConfiguration.class);

    @Test
    @DisplayName("the TDD 8.1 layout binds, every key of it")
    void bindsTheDocumentedLayout() {
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[0].ledger-accounts[0]=" + CLEARING,
                        "recon.sources[0].value-date-window-days=2",
                        "recon.sources[0].settles-to=BANK_MAIN",
                        "recon.sources[1].code=BANK_MAIN",
                        "recon.sources[1].type=BANK_STATEMENT",
                        "recon.sources[1].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
                        "recon.sources[1].grace-days-batch-unpaid=2")
                .run(started -> {
                    LedgerAccountSources accounts = started.getBean(LedgerAccountSources.class);
                    assertThat(accounts.sourceOf(UUID.fromString(CLEARING))).contains(SourceCode.of("PSP_ALPHA"));

                    ConfiguredSources sources = started.getBean(ConfiguredSources.class);
                    assertThat(sources.find(SourceCode.of("PSP_ALPHA"))).map(SourceDefinition::type)
                            .contains(SourceType.PSP_SETTLEMENT);
                    assertThat(sources.find(SourceCode.of("BANK_MAIN")).flatMap(SourceDefinition::batchIdPattern)
                            .flatMap(pattern -> pattern.extract(Optional.of("PAYOUT BATCH-B-001"))))
                            .contains("B-001");
                    assertThat(sources.find(SourceCode.of("PSP_ALPHA")).flatMap(SourceDefinition::settlesTo))
                            .contains(SourceCode.of("BANK_MAIN"));
                    assertThat(sources.find(SourceCode.of("BANK_MAIN")).map(SourceDefinition::graceDaysBatchUnpaid))
                            .contains(OptionalInt.of(2));
                });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("TDD 8.1 (v1.15): a bank source no PSP source settles to starts, and is logged once as a WARN naming it")
    void bankSourceNothingSettlesToIsWarnedOnce(CapturedOutput output) {
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[0].settles-to=BANK_MAIN",
                        "recon.sources[1].code=BANK_MAIN",
                        "recon.sources[1].type=BANK_STATEMENT",
                        "recon.sources[1].batch-id-pattern=" + PATTERN,
                        "recon.sources[2].code=BANK_UNSETTLED",
                        "recon.sources[2].type=BANK_STATEMENT",
                        "recon.sources[2].batch-id-pattern=" + PATTERN)
                .run(started -> {
                    assertThat(started).hasNotFailed();
                    assertThat(started.getBean(ConfiguredSources.class).find(SourceCode.of("BANK_UNSETTLED")))
                            .isPresent();
                });

        assertThat(output.getAll().lines().filter(line -> line.contains("WARN") && line.contains("BANK_UNSETTLED")))
                .singleElement().asString()
                .contains("Bank statement source BANK_UNSETTLED has no PSP source settling to it");
        assertThat(output.getAll()).doesNotContain("source BANK_MAIN has no PSP source");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("TDD 8.1 (v1.15): when a PSP source settles to every bank source, no such WARN is logged")
    void settledBankSourceIsNotWarned(CapturedOutput output) {
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[0].settles-to=BANK_MAIN",
                        "recon.sources[1].code=BANK_MAIN",
                        "recon.sources[1].type=BANK_STATEMENT",
                        "recon.sources[1].batch-id-pattern=" + PATTERN)
                .run(started -> assertThat(started).hasNotFailed());

        assertThat(output.getAll()).doesNotContain("has no PSP source settling to it");
    }

    @Test
    @DisplayName("no recon.sources at all maps no account and knows no source")
    void noSourcesMapsNothing() {
        context.run(started -> {
            assertThat(started.getBean(LedgerAccountSources.class).sourceOf(UUID.fromString(CLEARING))).isEmpty();
            assertThat(started.getBean(ConfiguredSources.class).all()).isEmpty();
        });
    }

    @Test
    @DisplayName("a contradictory configuration stops the application at startup")
    void contradictoryConfigurationFailsStartup() {
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[0].ledger-accounts[0]=" + CLEARING,
                        "recon.sources[0].settles-to=BANK_MAIN",
                        "recon.sources[1].code=PSP_BETA",
                        "recon.sources[1].type=PSP_SETTLEMENT",
                        "recon.sources[1].ledger-accounts[0]=" + CLEARING,
                        "recon.sources[1].settles-to=BANK_MAIN",
                        "recon.sources[2].code=BANK_MAIN",
                        "recon.sources[2].type=BANK_STATEMENT",
                        "recon.sources[2].batch-id-pattern=" + PATTERN)
                .run(started -> assertThat(started).hasFailed()
                        .getFailure().rootCause().isInstanceOf(InvalidSourceConfigurationException.class)
                        .hasMessageContaining("PSP_ALPHA").hasMessageContaining("PSP_BETA"));
    }

    @Test
    @DisplayName("a bank source without a batch-id-pattern stops the application at startup")
    void bankSourceWithoutPatternFailsStartup() {
        context.withPropertyValues(
                        "recon.sources[0].code=BANK_MAIN",
                        "recon.sources[0].type=BANK_STATEMENT")
                .run(started -> assertThat(started).hasFailed()
                        .getFailure().rootCause().isInstanceOf(InvalidSourceConfigurationException.class)
                        .hasMessageContaining("batch-id-pattern"));
    }

    @Test
    @DisplayName("FR-MAT-8: a PSP source binds its Stage A windows, and one it leaves out takes TDD 8.1's value")
    void bindsStageAWindows() {
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[0].value-date-window-days=4",
                        "recon.sources[0].grace-days-ledger-unmatched=5",
                        "recon.sources[0].grace-days-psp-unmatched=0",
                        "recon.sources[0].settles-to=BANK_MAIN",
                        "recon.sources[1].code=PSP_BETA",
                        "recon.sources[1].type=PSP_SETTLEMENT",
                        "recon.sources[1].grace-days-psp-unmatched=6",
                        "recon.sources[1].settles-to=BANK_MAIN",
                        "recon.sources[2].code=BANK_MAIN",
                        "recon.sources[2].type=BANK_STATEMENT",
                        "recon.sources[2].batch-id-pattern=" + PATTERN)
                .run(started -> {
                    ConfiguredSources sources = started.getBean(ConfiguredSources.class);
                    assertThat(sources.find(SourceCode.of("PSP_ALPHA")).flatMap(SourceDefinition::stageA))
                            .contains(new StageASettings(4, 5, 0));
                    assertThat(sources.find(SourceCode.of("PSP_BETA")).flatMap(SourceDefinition::stageA))
                            .contains(new StageASettings(2, 3, 6));
                });
    }

    @Test
    @DisplayName("a bank source with a Stage A window, or a negative window, stops the application at startup")
    void misplacedOrNegativeWindowFailsStartup() {
        context.withPropertyValues(
                        "recon.sources[0].code=BANK_MAIN",
                        "recon.sources[0].type=BANK_STATEMENT",
                        "recon.sources[0].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
                        "recon.sources[0].value-date-window-days=2")
                .run(started -> assertThat(started).hasFailed()
                        .getFailure().rootCause().isInstanceOf(InvalidSourceConfigurationException.class)
                        .hasMessageContaining("only a PSP source"));
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[0].grace-days-ledger-unmatched=-1",
                        "recon.sources[0].settles-to=BANK_MAIN",
                        "recon.sources[1].code=BANK_MAIN",
                        "recon.sources[1].type=BANK_STATEMENT",
                        "recon.sources[1].batch-id-pattern=" + PATTERN)
                .run(started -> assertThat(started).hasFailed()
                        .getFailure().rootCause().isInstanceOf(InvalidSourceConfigurationException.class)
                        .hasMessageContaining("grace-days-ledger-unmatched"));
    }

    @Test
    @DisplayName("TDD 8.1: a bank source binds its grace-days-batch-unpaid, and one that leaves it out takes TDD 8.1's 2")
    void bindsBatchGrace() {
        context.withPropertyValues(
                        "recon.sources[0].code=BANK_MAIN",
                        "recon.sources[0].type=BANK_STATEMENT",
                        "recon.sources[0].batch-id-pattern=" + PATTERN,
                        "recon.sources[0].grace-days-batch-unpaid=0",
                        "recon.sources[1].code=BANK_OTHER",
                        "recon.sources[1].type=BANK_STATEMENT",
                        "recon.sources[1].batch-id-pattern=" + PATTERN)
                .run(started -> {
                    ConfiguredSources sources = started.getBean(ConfiguredSources.class);
                    assertThat(sources.find(SourceCode.of("BANK_MAIN")).map(SourceDefinition::graceDaysBatchUnpaid))
                            .contains(OptionalInt.of(0));
                    assertThat(sources.find(SourceCode.of("BANK_OTHER")).map(SourceDefinition::graceDaysBatchUnpaid))
                            .contains(OptionalInt.of(2));
                });
    }

    @Test
    @DisplayName("TDD 8.1: a negative grace-days-batch-unpaid, or one set on a PSP source, stops startup naming the "
            + "source and the key")
    void negativeOrMisplacedBatchGraceFailsStartup() {
        context.withPropertyValues(
                        "recon.sources[0].code=BANK_MAIN",
                        "recon.sources[0].type=BANK_STATEMENT",
                        "recon.sources[0].batch-id-pattern=" + PATTERN,
                        "recon.sources[0].grace-days-batch-unpaid=-1")
                .run(started -> assertFailsNaming(started, "BANK_MAIN", "grace-days-batch-unpaid"));
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[0].settles-to=BANK_MAIN",
                        "recon.sources[0].grace-days-batch-unpaid=2",
                        "recon.sources[1].code=BANK_MAIN",
                        "recon.sources[1].type=BANK_STATEMENT",
                        "recon.sources[1].batch-id-pattern=" + PATTERN)
                .run(started -> assertFailsNaming(started, "PSP_ALPHA", "grace-days-batch-unpaid"));
    }

    @Test
    @DisplayName("TDD 8.1: a PSP source whose settles-to names a configured bank source starts")
    void settlesToAConfiguredBankSourceStarts() {
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[0].settles-to=BANK_MAIN",
                        "recon.sources[1].code=PSP_BETA",
                        "recon.sources[1].type=PSP_SETTLEMENT",
                        "recon.sources[1].settles-to=BANK_MAIN",
                        "recon.sources[2].code=BANK_MAIN",
                        "recon.sources[2].type=BANK_STATEMENT",
                        "recon.sources[2].batch-id-pattern=" + PATTERN)
                .run(started -> {
                    assertThat(started).hasNotFailed();
                    ConfiguredSources sources = started.getBean(ConfiguredSources.class);
                    assertThat(sources.find(SourceCode.of("PSP_BETA")).flatMap(SourceDefinition::settlesTo))
                            .contains(SourceCode.of("BANK_MAIN"));
                });
    }

    @Test
    @DisplayName("TDD 8.1: a PSP source without settles-to stops startup naming the source and the key")
    void missingSettlesToFailsStartup() {
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[1].code=BANK_MAIN",
                        "recon.sources[1].type=BANK_STATEMENT",
                        "recon.sources[1].batch-id-pattern=" + PATTERN)
                .run(started -> assertFailsNaming(started, "PSP_ALPHA", "settles-to"));
    }

    @Test
    @DisplayName("TDD 8.1: settles-to naming no configured source, a PSP source, or no source code at all stops "
            + "startup naming the source and the key")
    void settlesToNotNamingABankSourceFailsStartup() {
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[0].settles-to=BANK_ELSEWHERE",
                        "recon.sources[1].code=BANK_MAIN",
                        "recon.sources[1].type=BANK_STATEMENT",
                        "recon.sources[1].batch-id-pattern=" + PATTERN)
                .run(started -> assertFailsNaming(started, "PSP_ALPHA", "settles-to"));
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[0].settles-to=PSP_BETA",
                        "recon.sources[1].code=PSP_BETA",
                        "recon.sources[1].type=PSP_SETTLEMENT",
                        "recon.sources[1].settles-to=BANK_MAIN",
                        "recon.sources[2].code=BANK_MAIN",
                        "recon.sources[2].type=BANK_STATEMENT",
                        "recon.sources[2].batch-id-pattern=" + PATTERN)
                .run(started -> assertFailsNaming(started, "PSP_ALPHA", "settles-to"));
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[0].settles-to=bank main",
                        "recon.sources[1].code=BANK_MAIN",
                        "recon.sources[1].type=BANK_STATEMENT",
                        "recon.sources[1].batch-id-pattern=" + PATTERN)
                .run(started -> assertFailsNaming(started, "PSP_ALPHA", "settles-to"));
    }

    @Test
    @DisplayName("TDD 8.1: settles-to on a bank source stops startup naming the source and the key")
    void settlesToOnABankSourceFailsStartup() {
        context.withPropertyValues(
                        "recon.sources[0].code=BANK_MAIN",
                        "recon.sources[0].type=BANK_STATEMENT",
                        "recon.sources[0].batch-id-pattern=" + PATTERN,
                        "recon.sources[0].settles-to=BANK_OTHER",
                        "recon.sources[1].code=BANK_OTHER",
                        "recon.sources[1].type=BANK_STATEMENT",
                        "recon.sources[1].batch-id-pattern=" + PATTERN)
                .run(started -> assertFailsNaming(started, "BANK_MAIN", "settles-to"));
    }

    @Test
    @DisplayName("TDD 8.1: the business calendar binds its weekend and holidays")
    void bindsBusinessCalendar() {
        context.withPropertyValues(
                        "recon.business-calendar.weekend[0]=FRIDAY",
                        "recon.business-calendar.weekend[1]=SATURDAY",
                        "recon.business-calendar.holidays[0]=2026-10-29",
                        "recon.business-calendar.holidays[1]=2027-01-01")
                .run(started -> assertThat(started.getBean(BusinessCalendar.class)).isEqualTo(new BusinessCalendar(
                        Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
                        Set.of(LocalDate.of(2026, 10, 29), LocalDate.of(2027, 1, 1)))));
    }

    @Test
    @DisplayName("TDD 8.1: with no business calendar configured, the weekend is Saturday and Sunday and there are no holidays")
    void businessCalendarDefaultsToTheTddWeekend() {
        context.run(started -> assertThat(started.getBean(BusinessCalendar.class))
                .isEqualTo(new BusinessCalendar(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.of())));
    }

    @Test
    @DisplayName("a holiday that is not an ISO date stops the application at startup")
    void malformedHolidayFailsStartup() {
        context.withPropertyValues("recon.business-calendar.holidays[0]=29.10.2026")
                .run(started -> assertThat(started).hasFailed()
                        .getFailure().rootCause().isInstanceOf(DateTimeParseException.class));
    }

    private static void assertFailsNaming(AssertableApplicationContext started, String source, String key) {
        assertThat(started).hasFailed()
                .getFailure().rootCause().isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining(source).hasMessageContaining(key);
    }
}
