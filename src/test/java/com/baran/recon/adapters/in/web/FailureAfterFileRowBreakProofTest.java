package com.baran.recon.adapters.in.web;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.application.statement.StatementFiles.psp;
import static com.baran.recon.application.statement.StatementFiles.pspLine;
import static com.baran.recon.application.statement.StatementFiles.unique;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The break proof for FR-ING-6's rollback, kept as a test so it runs on every build (TDD 9.1). The
 * ingestion fails right after its file row is written, when every line has its file, so the deferred
 * line-to-file check has nothing to refuse and only the rollback can remove the failed ingestion.
 * {@code FailureMidFileTest} sees nothing left. Here, in a context of its own, the use case is given
 * a {@link Transactions} that commits even when the work throws; no file is edited. The same failure
 * then leaves the file row and every line behind, so the rollback is what removes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.sources[0].code=PSP_NO_ROLLBACK",
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@Import({FailingStatementStore.Injection.class, FailureAfterFileRowBreakProofTest.CommitDespiteFailure.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Break proof: without the rollback, an ingestion that failed after its file row is kept")
class FailureAfterFileRowBreakProofTest {

    private static final String PSP = "PSP_NO_ROLLBACK";
    private static final int LINES = 2_500;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StatementStore store;

    private StatementApi api;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @BeforeAll
    void createClient() {
        api = new StatementApi(port);
    }

    @AfterAll
    void closeClient() {
        api.close();
    }

    @Test
    @DisplayName("the failure is still a 500, but the file row and all its lines were committed")
    void withoutTheRollbackTheFailedIngestionIsKept() throws Exception {
        String id = unique();
        FailingStatementStore failing = (FailingStatementStore) store;

        failing.failAfterStoringFile();
        StatementApi.Response failed = api.upload(PSP, "STMT-" + id,
                psp(IntStream.rangeClosed(1, LINES).mapToObj(n -> pspLine(id + "-" + n)).toList()));
        failing.disarm();

        assertThat(failed.status()).isEqualTo(500);
        assertThat(jdbc.sql("SELECT status FROM statement_files WHERE statement_reference = :reference")
                .param("reference", "STMT-" + id).query(String.class).list()).isEqualTo(List.of("INGESTED"));
        assertThat(jdbc.sql("SELECT count(*) FROM psp_lines WHERE line_id LIKE :prefix").param("prefix", id + "-%")
                .query(Long.class).single()).isEqualTo(LINES);
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
