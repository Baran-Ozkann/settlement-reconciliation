package com.baran.recon.adapters.out.persistence;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.ItemAlreadyMatchedException;
import com.baran.recon.application.port.MatchStore;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.match.Match;
import com.baran.recon.domain.match.MatchItem;
import com.baran.recon.domain.match.RuleId;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Matches through the application's DataSource, as recon_app. Item ids are fresh per test: the
 * database is shared and recon_app can delete nothing, so no test relies on an empty table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("Matches are recorded with their items and creation event, as recon_app")
class MatchStoreTest {

    private static final Instant AT = Instant.parse("2026-09-26T10:00:30Z");
    private static final Money NO_DIFFERENCE = Money.zero(CurrencyCode.of("TRY"));
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private MatchStore matches;

    @Autowired
    private RunStore runs;

    @Autowired
    private JdbcClient jdbc;

    private UUID runId;

    @BeforeEach
    void startRun() {
        ReconciliationRun run = ReconciliationRun.start(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"),
                LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), Map.of(), AT.minusSeconds(30), "system");
        runs.insert(run);
        runId = run.id();
    }

    @Test
    @DisplayName("FR-MAT-6: a Stage A match is read back with its rule, version, run and items")
    void oneToOneMatchRoundTrips() {
        Match match = Match.active(UUID.randomUUID(), runId, RuleId.A3_FALLBACK_UNIQUE, 2, NO_DIFFERENCE, AT,
                List.of(item(ItemSide.LEDGER), item(ItemSide.PSP)));

        matches.record(match);

        assertThat(matches.findById(match.id())).contains(match);
        assertThat(creationEvents(match.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-MAT-6: a Stage B match is read back with every line of the batch")
    void manyToOneMatchRoundTrips() {
        Match match = Match.active(UUID.randomUUID(), runId, RuleId.B1_BATCH_TOTAL, 1, NO_DIFFERENCE, AT,
                List.of(item(ItemSide.PSP), item(ItemSide.PSP), item(ItemSide.PSP), item(ItemSide.BANK)));

        matches.record(match);

        assertThat(matches.findById(match.id())).contains(match);
    }

    @Test
    @DisplayName("INV-2: an item already in an active match is refused, and the new match leaves nothing behind")
    void itemInAnActiveMatchIsRefused() {
        MatchItem shared = item(ItemSide.PSP);
        Match first = Match.active(UUID.randomUUID(), runId, RuleId.A1_EXACT_REFERENCE, 1, NO_DIFFERENCE, AT,
                List.of(item(ItemSide.LEDGER), shared));
        Match second = Match.active(UUID.randomUUID(), runId, RuleId.A1_EXACT_REFERENCE, 1, NO_DIFFERENCE, AT,
                List.of(item(ItemSide.LEDGER), shared));
        matches.record(first);

        assertThatThrownBy(() -> matches.record(second))
                .isInstanceOf(ItemAlreadyMatchedException.class)
                .satisfies(e -> assertThat(((ItemAlreadyMatchedException) e).matchId()).isEqualTo(second.id()));
        assertThat(matches.findById(second.id())).isEmpty();
        assertThat(creationEvents(second.id())).isZero();
    }

    @Test
    @DisplayName("INV-6: recon_app is refused updating or deleting a match event for lack of privilege")
    void matchEventsAreAppendOnlyForTheApplication() {
        Match match = Match.active(UUID.randomUUID(), runId, RuleId.A1_EXACT_REFERENCE, 1, NO_DIFFERENCE, AT,
                List.of(item(ItemSide.LEDGER), item(ItemSide.PSP)));
        matches.record(match);

        assertRefusedForLackOfPrivilege("UPDATE match_events SET reason = 'Rewritten' WHERE match_id = :matchId", match.id());
        assertRefusedForLackOfPrivilege("DELETE FROM match_events WHERE match_id = :matchId", match.id());
        assertThat(creationEvents(match.id())).isEqualTo(1);
    }

    private void assertRefusedForLackOfPrivilege(String sql, UUID matchId) {
        assertThatThrownBy(() -> jdbc.sql(sql).param("matchId", matchId).update())
                .hasRootCauseInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(rootCause(e).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
    }

    private int creationEvents(UUID matchId) {
        return jdbc.sql("SELECT count(*) FROM match_events WHERE match_id = :matchId AND event_type = 'CREATED'")
                .param("matchId", matchId).query(Integer.class).single();
    }

    private static SQLException rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return (SQLException) cause;
    }

    private static MatchItem item(ItemSide side) {
        return new MatchItem(side, UUID.randomUUID());
    }
}
