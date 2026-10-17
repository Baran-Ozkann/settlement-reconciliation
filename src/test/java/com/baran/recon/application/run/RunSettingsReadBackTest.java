package com.baran.recon.application.run;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.MatchStore;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.application.run.StageAFixture.BreakView;
import com.baran.recon.domain.calendar.BusinessCalendar;
import com.baran.recon.domain.item.ItemRef;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-MAT-8 (v1.19): a run applies the settings its snapshot holds. Every setting here differs from
 * the application's: the value-date zone is Tokyo, the calendar has a holiday on Friday 2026-10-09,
 * and the PSP source's window and grace periods and the bank source's batch grace are not TDD 8.1's.
 * The clock stands at 01:00 on Tuesday 2026-10-13 in Tokyo, still Monday in Istanbul and in UTC, so
 * the zone decides which day grace periods are counted up to.
 *
 * <p>Each item's outcome is checked against the values read from the run's own snapshot, not from
 * configuration, and the items straddle every boundary those values draw. Within one application the
 * two cannot differ, so this test alone cannot tell which a run reads; the recorded one-off in
 * docs/break-proofs.md writes a snapshot that differs from configuration and shows that the outcomes
 * follow the snapshot, and that they would not if Stage A read configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.value-date-zone=Asia/Tokyo",
        "recon.business-calendar.holidays[0]=2026-10-09",
        "recon.sources[0].code=PSP_READ_BACK",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[0].value-date-window-days=1",
        "recon.sources[0].grace-days-ledger-unmatched=2",
        "recon.sources[0].grace-days-psp-unmatched=4",
        "recon.sources[0].settles-to=BANK_READ_BACK_STAGE_A",
        "recon.sources[1].code=BANK_READ_BACK_STAGE_A",
        "recon.sources[1].type=BANK_STATEMENT",
        "recon.sources[1].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
        "recon.sources[2].code=PSP_READ_BACK_BATCHES",
        "recon.sources[2].type=PSP_SETTLEMENT",
        "recon.sources[2].settles-to=BANK_READ_BACK",
        "recon.sources[3].code=BANK_READ_BACK",
        "recon.sources[3].type=BANK_STATEMENT",
        "recon.sources[3].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
        "recon.sources[3].grace-days-batch-unpaid=3"})
@ActiveProfiles("test")
@Import(RunSettingsReadBackTest.TokyoMorning.class)
@DisplayName("FR-MAT-8 (v1.19): a run applies the zone, calendar, window and grace periods its snapshot holds")
class RunSettingsReadBackTest {

    private static final SourceCode PSP = SourceCode.of("PSP_READ_BACK");
    private static final SourceCode BATCHES = SourceCode.of("PSP_READ_BACK_BATCHES");
    private static final SourceCode BANK = SourceCode.of("BANK_READ_BACK");
    /** Event ids no other test class uses; the shared database outlives each class. */
    private static final long FIRST_EVENT_ID = 9_900_000_000L;

    /** 01:00 on Tuesday 2026-10-13 in Tokyo; 19:00 on Monday 2026-10-12 in Istanbul. */
    private static final Instant NOW = Instant.parse("2026-10-12T16:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 28);
    private static final LocalDate TO = LocalDate.of(2026, 10, 13);
    /** A3 pairs are made around this Thursday, the day before the holiday. */
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 8);
    private static final String TRY = "TRY";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private RunMatching matching;

    @Autowired
    private LedgerEntryStore ledgerEntries;

    @Autowired
    private StatementStore statements;

    @Autowired
    private MatchStore matches;

    @Autowired
    private BreakStore breaks;

    @Autowired
    private RunStore runs;

    @Autowired
    private Transactions transactions;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("FR-MAT-8: the snapshot holds the configured zone, calendar, window and grace periods")
    void snapshotHoldsTheConfiguredSettings() {
        ReconciliationRun psp = matching.run("PSP_READ_BACK", TO, TO, "operator-001");
        ReconciliationRun bank = matching.run("BANK_READ_BACK", TO, TO, "operator-001");

        for (ReconciliationRun run : List.of(psp, bank)) {
            assertThat(run.configSnapshot())
                    .containsEntry(RunMatching.VALUE_DATE_ZONE, "Asia/Tokyo")
                    .containsEntry(RunMatching.BUSINESS_CALENDAR_WEEKEND, "SATURDAY,SUNDAY")
                    .containsEntry(RunMatching.BUSINESS_CALENDAR_HOLIDAYS, "2026-10-09");
        }
        assertThat(psp.configSnapshot())
                .containsEntry(RunMatching.VALUE_DATE_WINDOW_DAYS, "1")
                .containsEntry(RunMatching.GRACE_DAYS_LEDGER_UNMATCHED, "2")
                .containsEntry(RunMatching.GRACE_DAYS_PSP_UNMATCHED, "4");
        assertThat(bank.configSnapshot()).containsEntry(RunMatching.GRACE_DAYS_BATCH_UNPAID, "3");
    }

    @Test
    @DisplayName("FR-MAT-8: Stage A matches within the window and opens grace breaks past the grace periods its "
            + "snapshot holds, counted in the snapshot's calendar up to today in the snapshot's zone")
    void stageAAppliesTheSettingsItsSnapshotHolds() {
        StageAFixture fixture = new StageAFixture(ledgerEntries, statements, transactions, jdbc, FIRST_EVENT_ID);
        // Every item's key and value date. Each amount is used by one line and at most one entry.
        Map<String, LocalDate> items = new HashMap<>();
        List<StageAFixture.Psp> lines = new ArrayList<>();
        long amount = 10_000;
        // A3 pairs: a PSP line on the Thursday and one ledger entry of its amount on each of these days,
        // the line's reference unknown, so only the window decides whether they match.
        List<LocalDate> pairedOn = List.of(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7),
                LocalDate.of(2026, 10, 12), TO);
        for (LocalDate day : pairedOn) {
            amount++;
            items.put(fixture.ledger(PSP, UUID.randomUUID(), amount, TRY, day), day);
            lines.add(new StageAFixture.Psp("PAIR-" + day, null, amount, TRY, THURSDAY));
            items.put("PSP PAIR-" + day, THURSDAY);
        }
        // Unpaired items on every day of two weeks, on both sides, for the grace periods.
        for (LocalDate day = LocalDate.of(2026, 9, 30); !day.isAfter(TO); day = day.plusDays(1)) {
            amount++;
            items.put(fixture.ledger(PSP, UUID.randomUUID(), amount, TRY, day), day);
            amount++;
            lines.add(new StageAFixture.Psp("ALONE-" + day, null, amount, TRY, day));
            items.put("PSP ALONE-" + day, day);
        }
        fixture.pspLines(PSP, lines);

        ReconciliationRun run = matching.run("PSP_READ_BACK", FROM, TO, "operator-001");

        Snapshot snapshot = Snapshot.of(run);
        Set<String> matched = fixture.matches(PSP).stream()
                .flatMap(match -> Stream.of(match.psp(), match.ledger())).collect(Collectors.toSet());
        int window = snapshot.days(RunMatching.VALUE_DATE_WINDOW_DAYS);
        List<Boolean> withinWindow = new ArrayList<>();
        for (LocalDate day : pairedOn) {
            boolean within = Math.abs(snapshot.calendar().businessDaysBetween(THURSDAY, day)) <= window;
            assertThat(matched.contains("PSP PAIR-" + day)).as("the line paired with an entry on %s", day)
                    .isEqualTo(within);
            withinWindow.add(within);
        }
        assertThat(withinWindow).as("pairs on both sides of the window").contains(true, false);

        Map<String, String> breakTypes = fixture.breaks(PSP).stream()
                .collect(Collectors.toMap(BreakView::subject, BreakView::type));
        LocalDate ledgerGraceStart = snapshot.firstDayWithinGrace(RunMatching.GRACE_DAYS_LEDGER_UNMATCHED);
        LocalDate pspGraceStart = snapshot.firstDayWithinGrace(RunMatching.GRACE_DAYS_PSP_UNMATCHED);
        List<Boolean> ledgerPastGrace = new ArrayList<>();
        List<Boolean> pspPastGrace = new ArrayList<>();
        items.forEach((key, day) -> {
            boolean ledger = key.startsWith("LEDGER ");
            boolean pastGrace = !matched.contains(key) && day.isBefore(ledger ? ledgerGraceStart : pspGraceStart);
            assertThat(Optional.ofNullable(breakTypes.get(key))).as("the break on %s, dated %s", key, day)
                    .isEqualTo(pastGrace ? Optional.of(ledger ? "MISSING_IN_PSP" : "MISSING_IN_LEDGER")
                            : Optional.empty());
            (ledger ? ledgerPastGrace : pspPastGrace).add(pastGrace);
        });
        assertThat(ledgerPastGrace).as("ledger entries on both sides of their grace").contains(true, false);
        assertThat(pspPastGrace).as("PSP lines on both sides of their grace").contains(true, false);
    }

    @Test
    @DisplayName("FR-MAT-8: the bank run opens MISSING_SETTLEMENT past the batch grace its snapshot holds, counted in "
            + "the snapshot's calendar up to today in the snapshot's zone")
    void bankRunAppliesTheSettingsItsSnapshotHolds() {
        StageBFixture fixture = new StageBFixture(statements, matches, breaks, runs, transactions);
        List<StageBFixture.Psp> lines = new ArrayList<>();
        Map<ItemRef, LocalDate> batches = new HashMap<>();
        for (LocalDate day = LocalDate.of(2026, 9, 30); !day.isAfter(TO); day = day.plusDays(1)) {
            String batchId = "READBACK" + day.toString().replace("-", "");
            lines.add(new StageBFixture.Psp("LINE-" + day, batchId, 5_000, 0, TRY, day));
            batches.put(StageBFixture.batch(BATCHES, batchId), day);
        }
        fixture.pspFile(BATCHES, lines);

        ReconciliationRun run = matching.run("BANK_READ_BACK", FROM, TO, "operator-001");

        LocalDate graceStart = Snapshot.of(run).firstDayWithinGrace(RunMatching.GRACE_DAYS_BATCH_UNPAID);
        List<Boolean> outcomes = new ArrayList<>();
        batches.forEach((batch, day) -> {
            boolean pastGrace = day.isBefore(graceStart);
            assertThat(breakTypesOn(batch)).as("the breaks on the batch dated %s", day)
                    .isEqualTo(pastGrace ? List.of("MISSING_SETTLEMENT") : List.of());
            outcomes.add(pastGrace);
        });
        assertThat(outcomes).as("batches on both sides of their grace").contains(true, false);
    }

    private List<String> breakTypesOn(ItemRef item) {
        return jdbc.sql("SELECT break_type FROM breaks WHERE item_side = :side AND item_id = :id ORDER BY break_type")
                .param("side", item.side().name()).param("id", item.id()).query(String.class).list();
    }

    /**
     * A run's settings as its snapshot recorded them, parsed here from the stored strings, so the
     * expected outcomes come from the record and not from what the test configured.
     */
    private record Snapshot(ZoneId zone, BusinessCalendar calendar, Map<String, String> values) {

        static Snapshot of(ReconciliationRun run) {
            Map<String, String> values = run.configSnapshot();
            return new Snapshot(ZoneId.of(values.get(RunMatching.VALUE_DATE_ZONE)),
                    new BusinessCalendar(
                            listed(values.get(RunMatching.BUSINESS_CALENDAR_WEEKEND)).stream()
                                    .map(DayOfWeek::valueOf).collect(Collectors.toSet()),
                            listed(values.get(RunMatching.BUSINESS_CALENDAR_HOLIDAYS)).stream()
                                    .map(LocalDate::parse).collect(Collectors.toSet())),
                    values);
        }

        int days(String key) {
            return Integer.parseInt(values.get(key));
        }

        LocalDate firstDayWithinGrace(String key) {
            return calendar.firstDayWithinGrace(LocalDate.ofInstant(NOW, zone), days(key));
        }

        private static List<String> listed(String value) {
            return value.isEmpty() ? List.of() : Arrays.asList(value.split(","));
        }
    }

    /** The clock at {@link #NOW}, where Tokyo's day is a day ahead of Istanbul's and UTC's. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TokyoMorning {

        @Bean
        @Primary
        Clock tokyoMorningClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
