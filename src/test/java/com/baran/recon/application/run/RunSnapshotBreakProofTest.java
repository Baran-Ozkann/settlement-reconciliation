package com.baran.recon.application.run;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.StageAStore;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.application.run.StageAFixture.BreakView;
import com.baran.recon.application.run.StageAFixture.MatchView;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The break proof for the run's snapshot (TDD 5.3, 9.1), kept as a test so it runs on every build.
 * {@code RunSnapshotTest.itemsCommittedDuringTheRunAreSeenByNoneOfItsStatements} holds a run after
 * A1 while an entry and a line are committed, and the run does nothing about them. Here the same
 * run meets a context whose work transaction is READ COMMITTED: A1 did not see the two items, so
 * they stay unmatched, and the grace statement, which started after the commit, sees them and opens
 * a MISSING_IN_PSP and a MISSING_IN_LEDGER break on a pair the next run matches. So the REPEATABLE
 * READ snapshot is what keeps a run's statements from disagreeing; nothing else in the run does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_SNAPSHOT_READ_COMMITTED",
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@Import({FixedRunClock.class, HeldStageAStore.Injection.class, RunSnapshotBreakProofTest.ReadCommittedWork.class})
@DirtiesContext
@DisplayName("Break proof: at READ COMMITTED, a run's later statement sees what its first did not")
class RunSnapshotBreakProofTest {

    private static final SourceCode SOURCE = SourceCode.of("PSP_SNAPSHOT_READ_COMMITTED");
    /** Event ids no other test class uses; the shared database outlives each class. */
    private static final long FIRST_EVENT_ID = 9_870_000_000L;

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private RunMatching matching;

    @Autowired
    private StageAStore stageA;

    @Autowired
    private LedgerEntryStore ledgerEntries;

    @Autowired
    private StatementStore statements;

    @Autowired
    private Transactions transactions;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("an entry and a line committed while the run works are left unmatched by A1 and given grace "
            + "breaks by the same run")
    void withoutTheSnapshotALaterStatementSeesTheCommit() throws Exception {
        StageAFixture fixture = new StageAFixture(ledgerEntries, statements, transactions, jdbc, FIRST_EVENT_ID);
        UUID before = UUID.randomUUID();
        UUID during = UUID.randomUUID();
        String entryBefore = fixture.ledger(SOURCE, before, 500, "TRY", MONDAY);
        fixture.psp(SOURCE, "L-BEFORE", before.toString(), 500, "TRY", MONDAY);
        String[] entryDuring = new String[1];

        ReconciliationRun held = HeldStageAStore.of(stageA).runHeldWhile(SOURCE, () -> run(SOURCE), () -> {
            entryDuring[0] = fixture.ledger(SOURCE, during, 1_000, "TRY", MONDAY);
            fixture.psp(SOURCE, "L-DURING", during.toString(), 1_000, "TRY", MONDAY);
        });

        assertThat(held.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.matches(SOURCE)).extracting(MatchView::ledger, MatchView::psp)
                .as("A1 ran before the commit").containsExactly(tuple(entryBefore, "PSP L-BEFORE"));
        assertThat(fixture.breaks(SOURCE)).extracting(BreakView::type, BreakView::subject, BreakView::openedRunId)
                .as("the grace statement ran after it")
                .containsExactlyInAnyOrder(
                        tuple("MISSING_IN_PSP", entryDuring[0], held.id()),
                        tuple("MISSING_IN_LEDGER", "PSP L-DURING", held.id()));
        assertThat(held.stats()).hasValueSatisfying(stats -> assertThat(stats).containsAllEntriesOf(Map.of(
                "ledger.TRY.broken.count", 1L, "psp.TRY.broken.count", 1L,
                ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE, 2L)));

        run(SOURCE);

        assertThat(fixture.breaks(SOURCE)).extracting(BreakView::status, BreakView::resolutionCode)
                .as("the next run matches the pair the held run called missing")
                .containsOnly(tuple("RESOLVED", Optional.of("MATCHED_LATE")));
        assertThat(fixture.matches(SOURCE)).extracting(MatchView::psp)
                .containsExactlyInAnyOrderElementsOf(Set.of("PSP L-BEFORE", "PSP L-DURING"));
    }

    private ReconciliationRun run(SourceCode source) {
        return matching.run(source.value(), MONDAY, FRIDAY, "operator-001");
    }

    /** The application's transactions, except that the snapshot transaction is READ COMMITTED. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ReadCommittedWork {

        @Bean
        @Primary
        Transactions readCommittedWork(PlatformTransactionManager transactionManager) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            return new Transactions() {
                @Override
                public <T> T inTransaction(Supplier<T> work) {
                    return template.execute(status -> work.get());
                }

                @Override
                public <T> T inSnapshotTransaction(Supplier<T> work) {
                    return template.execute(status -> work.get());
                }
            };
        }
    }
}
