package com.baran.recon.adapters.out.persistence;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.ItemAlreadyHasOpenBreakException;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.StaleBreakTransitionException;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.breaks.Break;
import com.baran.recon.domain.breaks.BreakEvent;
import com.baran.recon.domain.breaks.BreakStatus;
import com.baran.recon.domain.breaks.BreakType;
import com.baran.recon.domain.breaks.ResolutionCode;
import com.baran.recon.domain.breaks.Transition;
import com.baran.recon.domain.item.ItemRef;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Breaks and their history through the application's DataSource, as recon_app. Item ids are fresh
 * per test: the database is shared and recon_app can delete nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("Breaks are stored with every state change as an event, as recon_app")
class BreakStoreTest {

    private static final Instant OPENED = Instant.parse("2026-09-26T10:00:45.000001Z");
    private static final Actor OPERATOR = Actor.operator("operator-001");
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private BreakStore breaks;

    @Autowired
    private RunStore runs;

    @Autowired
    private JdbcClient jdbc;

    private UUID runId;

    @BeforeEach
    void startRun() {
        ReconciliationRun run = ReconciliationRun.start(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"),
                LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), Map.of(), OPENED.minusSeconds(45), "system");
        runs.insert(run);
        runId = run.id();
    }

    @Test
    @DisplayName("an opened break is read back unchanged, with its related items and opening event")
    void openedBreakRoundTrips() {
        Transition opened = open(item(ItemSide.PSP));

        breaks.open(opened);

        assertThat(breaks.findById(opened.result().id())).contains(opened.result());
        assertThat(breaks.eventsOf(opened.result().id())).containsExactly(opened.event());
    }

    @Test
    @DisplayName("FR-BRK-6: every state change is stored with its event, in order")
    void everyChangeIsAnEvent() {
        Transition opened = open(item(ItemSide.PSP));
        breaks.open(opened);
        Transition investigating = opened.result().startInvestigation(OPERATOR, Optional.of("Asked the PSP"),
                OPENED.plusSeconds(60));
        breaks.apply(investigating);
        Transition resolved = investigating.result().resolve(ResolutionCode.PSP_ERROR_CONFIRMED,
                "The PSP confirmed a wrong amount", OPERATOR, OPENED.plusSeconds(120));
        breaks.apply(resolved);

        assertThat(breaks.findById(opened.result().id())).contains(resolved.result());
        assertThat(breaks.eventsOf(opened.result().id()))
                .containsExactly(opened.event(), investigating.event(), resolved.event())
                .extracting(BreakEvent::to)
                .containsExactly(BreakStatus.OPEN, BreakStatus.INVESTIGATING, BreakStatus.RESOLVED);
    }

    @Test
    @DisplayName("INV-7: a second unresolved break on the same item is refused")
    void oneUnresolvedBreakPerItem() {
        ItemRef item = item(ItemSide.LEDGER);
        breaks.open(open(item));
        Transition second = open(item);

        assertThatThrownBy(() -> breaks.open(second))
                .isInstanceOf(ItemAlreadyHasOpenBreakException.class)
                .satisfies(e -> assertThat(((ItemAlreadyHasOpenBreakException) e).item()).isEqualTo(item));
        assertThat(breaks.findById(second.result().id())).isEmpty();
        assertThat(breaks.eventsOf(second.result().id())).isEmpty();
    }

    @Test
    @DisplayName("INV-7, FR-BRK-3: a resolved break can be reopened as a new break on the same item")
    void reopenedBreakIsANewBreak() {
        Transition opened = open(item(ItemSide.BANK));
        breaks.open(opened);
        Transition resolved = opened.result().resolveAsMatchedLate("Matched by a later run", OPENED.plusSeconds(60));
        breaks.apply(resolved);

        Transition reopened = resolved.result().reopen(UUID.randomUUID(), OPERATOR, "The late match was wrong",
                OPENED.plusSeconds(120));
        breaks.open(reopened);

        assertThat(breaks.findById(reopened.result().id())).hasValueSatisfying(found -> {
            assertThat(found.status()).isEqualTo(BreakStatus.OPEN);
            assertThat(found.previousBreakId()).contains(opened.result().id());
        });
        assertThat(breaks.findById(opened.result().id())).contains(resolved.result());
    }

    @Test
    @DisplayName("a change made from a status the break no longer has is refused, and no event is written")
    void staleChangeIsRefused() {
        Transition opened = open(item(ItemSide.PSP));
        breaks.open(opened);
        breaks.apply(opened.result().resolve(ResolutionCode.WRITTEN_OFF, "Below threshold", OPERATOR,
                OPENED.plusSeconds(30)));

        Transition stale = opened.result().startInvestigation(OPERATOR, Optional.empty(), OPENED.plusSeconds(60));

        assertThatThrownBy(() -> breaks.apply(stale)).isInstanceOf(StaleBreakTransitionException.class);
        assertThat(breaks.eventsOf(opened.result().id())).hasSize(2);
    }

    @Test
    @DisplayName("INV-6: recon_app is refused updating or deleting a break event for lack of privilege")
    void breakEventsAreAppendOnlyForTheApplication() {
        Transition opened = open(item(ItemSide.PSP));
        breaks.open(opened);
        UUID id = opened.result().id();

        assertRefusedForLackOfPrivilege("UPDATE break_events SET reason = 'Rewritten' WHERE break_id = :id", id);
        assertRefusedForLackOfPrivilege("DELETE FROM break_events WHERE break_id = :id", id);
        assertThat(breaks.eventsOf(id)).containsExactly(opened.event());
    }

    @Test
    @DisplayName("a break's type and item cannot be rewritten through the application's connection")
    void onlyTheTransitionColumnsCanBeUpdated() {
        Transition opened = open(item(ItemSide.PSP));
        breaks.open(opened);
        UUID id = opened.result().id();

        assertRefusedForLackOfPrivilege("UPDATE breaks SET break_type = 'MISSING_IN_PSP' WHERE id = :id", id);
        assertRefusedForLackOfPrivilege("DELETE FROM breaks WHERE id = :id", id);
    }

    private void assertRefusedForLackOfPrivilege(String sql, UUID id) {
        assertThatThrownBy(() -> jdbc.sql(sql).param("id", id).update())
                .hasRootCauseInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(rootCause(e).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
    }

    private Transition open(ItemRef item) {
        return Break.open(UUID.randomUUID(), BreakType.AMOUNT_MISMATCH, item, List.of(item(ItemSide.LEDGER)),
                Optional.of(runId), Actor.SYSTEM, OPENED);
    }

    private static ItemRef item(ItemSide side) {
        return new ItemRef(side, UUID.randomUUID());
    }

    private static SQLException rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return (SQLException) cause;
    }
}
