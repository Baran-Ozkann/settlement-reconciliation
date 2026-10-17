package com.baran.recon.application.run;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.RunTrigger;
import com.baran.recon.application.statement.IngestStatement;
import com.baran.recon.domain.item.BatchKey;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.source.ConfiguredSources;
import com.baran.recon.domain.statement.StatementFile;
import com.baran.recon.domain.statement.StatementFileStatus;
import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.application.statement.StatementFiles.psp;
import static com.baran.recon.application.statement.StatementFiles.pspLine;
import static com.baran.recon.application.statement.StatementFiles.unique;
import static com.baran.recon.application.statement.StatementFiles.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * TDD 14, Milestone 6's exit criterion: the scheduled run opens a grace break no file-triggered run
 * would (TDD 5.3, v1.19). A PSP file arrives on Monday 2026-10-12 and the automatic trigger runs its
 * PSP source and then its bank source over the file's one date; the line and its batch are then
 * within their grace periods, so neither run opens anything. No file arrives afterwards. A week later
 * the line is past grace-days-psp-unmatched and the batch past grace-days-batch-unpaid, and only the
 * daily run, over its look-back, covers their date: it opens MISSING_IN_LEDGER on the line (Stage A)
 * and MISSING_SETTLEMENT on the batch (Stage B).
 *
 * <p>The automatic trigger is on in this context, as in the application; the scheduler is not, so the
 * test calls the daily run's use case itself, on the context's trigger, with the context's clock,
 * which the test moves. Its sources are its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.matching.automatic-trigger.enabled=true",
        "recon.matching.automatic-trigger.busy-retry-interval=50ms",
        "recon.sources[0].code=PSP_SCHEDULED_GRACE",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[0].settles-to=BANK_SCHEDULED_GRACE",
        "recon.sources[1].code=BANK_SCHEDULED_GRACE",
        "recon.sources[1].type=BANK_STATEMENT",
        "recon.sources[1].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})"})
@ActiveProfiles("test")
@Import(ScheduledRunGraceTest.MovableClockInjection.class)
@DisplayName("TDD 14 (Milestone 6): the scheduled run opens the grace breaks no file-triggered run would")
class ScheduledRunGraceTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final SourceCode PSP = SourceCode.of("PSP_SCHEDULED_GRACE");
    private static final SourceCode BANK = SourceCode.of("BANK_SCHEDULED_GRACE");
    /** Noon in Istanbul on Monday 2026-10-12, when the file arrives. */
    private static final Instant FILE_ARRIVES = Instant.parse("2026-10-12T09:00:00Z");
    /** Noon in Istanbul on Monday 2026-10-19, a week later, with no file since. */
    private static final Instant WEEK_LATER = Instant.parse("2026-10-19T09:00:00Z");
    private static final LocalDate LINE_DATE = LocalDate.of(2026, 10, 12);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private IngestStatement ingestion;

    @Autowired
    private RunTrigger runTrigger;

    @Autowired
    private ConfiguredSources sources;

    @Autowired
    private MovableClock clock;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("TDD 5.3 (v1.19): a line and its batch left past their grace with no later file get MISSING_IN_LEDGER "
            + "and MISSING_SETTLEMENT from the scheduled run, and from no file-triggered run")
    void scheduledRunOpensTheGraceBreaksNoFileTriggeredRunOpens() throws Exception {
        clock.set(FILE_ARRIVES);
        String lineId = unique() + "-1";
        StatementFile file = ingestion.ingest(upload(PSP.value(), "STMT-" + unique(),
                psp(pspLine(lineId, LINE_DATE.toString()))));
        assertThat(file.status()).isEqualTo(StatementFileStatus.INGESTED);
        await().atMost(WAIT).untilAsserted(() -> assertThat(completedRuns()).containsExactlyInAnyOrder(
                "PSP_SCHEDULED_GRACE 2026-10-12 2026-10-12 system",
                "BANK_SCHEDULED_GRACE 2026-10-12 2026-10-12 system"));
        UUID line = pspLineId(lineId);
        UUID batch = new BatchKey(PSP, "B-001").itemId();
        assertThat(breaksOn("PSP", line)).as("the line is within its grace on the day it arrives").isEmpty();
        assertThat(breaksOn("BATCH", batch)).as("the batch is within its grace on the day it arrives").isEmpty();

        clock.set(WEEK_LATER);
        assertThat(breaksOn("PSP", line)).as("no file arrived, so no run looked at the line again").isEmpty();
        assertThat(breaksOn("BATCH", batch)).as("no file arrived, so no run looked at the batch again").isEmpty();

        new ScheduledRuns(sources, (AutomaticRunTrigger) runTrigger, clock, ISTANBUL, 30).queueDailyRuns();

        // A break commits with its run's COMPLETED, so each wait ends when its run has finished.
        await().atMost(WAIT).untilAsserted(() -> assertThat(breaksOn("PSP", line)).as("Stage A's grace break")
                .containsExactly("MISSING_IN_LEDGER by PSP_SCHEDULED_GRACE 2026-09-19 2026-10-19"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(breaksOn("BATCH", batch)).as("Stage B's grace break")
                .containsExactly("MISSING_SETTLEMENT by BANK_SCHEDULED_GRACE 2026-09-19 2026-10-19"));
        assertThat(completedRuns()).containsExactlyInAnyOrder(
                "PSP_SCHEDULED_GRACE 2026-10-12 2026-10-12 system",
                "BANK_SCHEDULED_GRACE 2026-10-12 2026-10-12 system",
                "PSP_SCHEDULED_GRACE 2026-09-19 2026-10-19 system",
                "BANK_SCHEDULED_GRACE 2026-09-19 2026-10-19 system");
    }

    @Test
    @DisplayName("TDD 5.3 (v1.19): the test profile switches the scheduled run off, so nothing is scheduled behind a "
            + "test's back")
    void testProfileSwitchesTheScheduledRunOff() {
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    /** Each completed run of the two sources, as {@code source from to triggered_by}. */
    private List<String> completedRuns() {
        return jdbc.sql("""
                        SELECT source_code || ' ' || value_date_from || ' ' || value_date_to || ' ' || triggered_by
                          FROM reconciliation_runs
                         WHERE source_code IN (:psp, :bank) AND status = 'COMPLETED'
                        """)
                .param("psp", PSP.value()).param("bank", BANK.value()).query(String.class).list();
    }

    /** Each break on the item, as {@code type by <source from to> of the run that opened it}. */
    private List<String> breaksOn(String side, UUID item) {
        return jdbc.sql("""
                        SELECT b.break_type || ' by ' || r.source_code || ' ' || r.value_date_from || ' '
                               || r.value_date_to
                          FROM breaks b
                          JOIN reconciliation_runs r ON r.id = b.opened_run_id
                         WHERE b.item_side = :side AND b.item_id = :item
                        """)
                .param("side", side).param("item", item).query(String.class).list();
    }

    private UUID pspLineId(String lineId) {
        return jdbc.sql("SELECT id FROM psp_lines WHERE source_code = :source AND line_id = :lineId")
                .param("source", PSP.value()).param("lineId", lineId).query(UUID.class).single();
    }

    /** The context's clock, which the test sets: the day a file arrives, then a week later. */
    static final class MovableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(FILE_ARRIVES);

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("the application reads instants only");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MovableClockInjection {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }
}
