package com.baran.recon.application.run;

import java.time.LocalDate;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.application.run.StageAFixture.BreakView;
import com.baran.recon.application.run.StageAFixture.MatchView;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The break proof for NFR-REL-2 (TDD 5.3, 9.1), kept as a test so it runs on every build.
 * {@code RunMatchingTest.failureAfterStageAWroteLeavesNoneOfIt} sees a run fail after Stage A wrote
 * a match and a break, and finds neither. Here the same failure meets a context whose transactions
 * commit what the work did even when it throws, and both remain, with the run COMPLETED: so the
 * rollback of the work transaction is what removes them, and nothing in the run stands in for it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_RUN_NO_ROLLBACK",
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@Import({FailingRunStore.Injection.class, WorkRollbackBreakProofTest.CommitDespiteFailure.class})
@DirtiesContext
@DisplayName("Break proof: without the work transaction's rollback, a failed run's match and break remain")
class WorkRollbackBreakProofTest {

    private static final SourceCode SOURCE = SourceCode.of("PSP_RUN_NO_ROLLBACK");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 24);
    private static final LocalDate TO = LocalDate.of(2026, 9, 25);
    /** Event ids no other test class uses; the shared database outlives each class. */
    private static final long FIRST_EVENT_ID = 9_800_000_000L;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private RunMatching matching;

    @Autowired
    private RunStore runs;

    @Autowired
    private LedgerEntryStore ledgerEntries;

    @Autowired
    private StatementStore statements;

    @Autowired
    private Transactions transactions;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("the failed run's A1 match and A2 break are committed, and the run is left COMPLETED")
    void withoutTheRollbackTheWorkRemains() {
        StageAFixture fixture = new StageAFixture(ledgerEntries, statements, transactions, jdbc, FIRST_EVENT_ID);
        UUID exact = UUID.randomUUID();
        UUID conflicting = UUID.randomUUID();
        fixture.ledger(SOURCE, exact, 1_000, "TRY", FROM);
        fixture.psp(SOURCE, "L-EXACT", exact.toString(), 1_000, "TRY", FROM);
        fixture.ledger(SOURCE, conflicting, 2_000, "TRY", FROM);
        fixture.psp(SOURCE, "L-CONFLICT", conflicting.toString(), 2_500, "TRY", FROM);
        FailingRunStore.of(runs).failTheNextCompletionOf(SOURCE);

        assertThatThrownBy(() -> matching.run(SOURCE.value(), FROM, TO, "operator-001"))
                .isInstanceOf(IllegalStateException.class).hasMessage(FailingRunStore.FAILURE);

        UUID runId = jdbc.sql("SELECT id FROM reconciliation_runs WHERE source_code = :source")
                .param("source", SOURCE.value()).query(UUID.class).single();
        assertThat(runs.findById(runId).orElseThrow().status()).as("FAILED could not replace the committed COMPLETED")
                .isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.matches(SOURCE)).extracting(MatchView::psp, MatchView::runId)
                .containsExactly(tuple("PSP L-EXACT", runId));
        assertThat(fixture.breaks(SOURCE)).extracting(BreakView::type, BreakView::subject, BreakView::openedRunId)
                .containsExactly(tuple("AMOUNT_MISMATCH", "PSP L-CONFLICT", runId));
    }

    /** A transaction that commits what the work did even when the work throws, then rethrows. */
    @TestConfiguration(proxyBeanMethods = false)
    static class CommitDespiteFailure {

        @Bean
        @Primary
        Transactions commitDespiteFailure(PlatformTransactionManager transactionManager) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            return new Transactions() {
                @Override
                public <T> T inTransaction(Supplier<T> work) {
                    RuntimeException[] failure = new RuntimeException[1];
                    T result = template.execute(status -> {
                        try {
                            return work.get();
                        } catch (RuntimeException thrown) {
                            failure[0] = thrown;
                            return null;
                        }
                    });
                    if (failure[0] != null) {
                        throw failure[0];
                    }
                    return result;
                }
            };
        }
    }
}
