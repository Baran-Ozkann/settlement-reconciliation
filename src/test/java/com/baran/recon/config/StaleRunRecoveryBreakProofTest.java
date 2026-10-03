package com.baran.recon.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.run.RunMatching;
import com.baran.recon.application.run.RunRefusedException;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The break proof for the startup recovery of TDD 5.3 (9.1), kept as a test so it runs on every
 * build. {@code StaleRunRecoveryTest} sees a run a stopped instance left RUNNING set FAILED when the
 * context starts, and its source free. Here the same leftover meets a context from which the
 * recovery bean alone has been removed: the run stays RUNNING and its source refuses every new run,
 * so the recovery is what frees it. No file is edited; the bean's definition is removed by a post
 * processor of this test's own context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_STALE_KEPT",
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@Import(StaleRunRecoveryBreakProofTest.WithoutStartupRecovery.class)
@DirtiesContext
@DisplayName("Break proof: without the startup recovery, a run left RUNNING keeps its source busy")
class StaleRunRecoveryBreakProofTest {

    private static final String RECOVERY_BEAN = "failRunsLeftRunning";
    private static final UUID STALE = UUID.fromString("c0000000-0000-4000-8000-0000000005b1");
    private static final SourceCode SOURCE = SourceCode.of("PSP_STALE_KEPT");

    private static ReconPostgres database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        database = ReconPostgres.throwaway();
        try (Connection connection = database.connectAsMigrator(); Statement jdbc = connection.createStatement()) {
            jdbc.execute("""
                    INSERT INTO recon.reconciliation_runs (id, source_code, value_date_from, value_date_to, status,
                                                           config_snapshot, stats, started_at, finished_at, triggered_by)
                    VALUES ('c0000000-0000-4000-8000-0000000005b1', 'PSP_STALE_KEPT', '2026-09-24', '2026-09-25',
                            'RUNNING', '{"value_date_zone": "Europe/Istanbul"}', NULL, '2026-09-26T10:00:00Z', NULL,
                            'system')
                    """);
        }
        database.registerIn(registry);
    }

    @AfterAll
    static void stopDatabase() {
        database.close();
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RunStore runs;

    @Autowired
    private RunMatching matching;

    @Test
    @DisplayName("the leftover run is still RUNNING, and a new run of its source is refused as busy, naming it")
    void withoutTheRecoveryTheSourceStaysBusy() {
        assertThat(context.containsBeanDefinition(RECOVERY_BEAN)).as("the recovery is absent from this context")
                .isFalse();
        assertThat(runs.findById(STALE).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
        assertThat(runs.findRunning(SOURCE)).contains(STALE);

        assertThatThrownBy(() -> matching.run(SOURCE.value(), LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25),
                "system"))
                .isInstanceOfSatisfying(RunRefusedException.class, refusal -> {
                    assertThat(refusal.reason()).isEqualTo(RunRefusedException.Reason.SOURCE_BUSY);
                    assertThat(refusal.runningRunId()).contains(STALE);
                });
    }

    /** Removes the startup recovery's bean definition before any bean is created. */
    @TestConfiguration(proxyBeanMethods = false)
    static class WithoutStartupRecovery {

        @Bean
        static BeanDefinitionRegistryPostProcessor removeStartupRecovery() {
            return new BeanDefinitionRegistryPostProcessor() {
                @Override
                public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                    registry.removeBeanDefinition(RECOVERY_BEAN);
                }

                @Override
                public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
                }
            };
        }
    }
}
