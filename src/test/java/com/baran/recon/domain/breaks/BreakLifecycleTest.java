package com.baran.recon.domain.breaks;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.baran.recon.domain.item.ItemRef;
import com.baran.recon.domain.item.ItemSide;

import static com.baran.recon.domain.breaks.BreakAction.AUTO_RESOLVE;
import static com.baran.recon.domain.breaks.BreakAction.INVESTIGATE;
import static com.baran.recon.domain.breaks.BreakAction.REOPEN;
import static com.baran.recon.domain.breaks.BreakAction.RESOLVE;
import static com.baran.recon.domain.breaks.BreakStatus.INVESTIGATING;
import static com.baran.recon.domain.breaks.BreakStatus.OPEN;
import static com.baran.recon.domain.breaks.BreakStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-BRK-3: every action from every status. The expected outcome is written out here as a table,
 * independently of {@link BreakAction}, so the test does not merely agree with the code it checks.
 */
@DisplayName("FR-BRK-3: the break lifecycle, every legal and illegal transition")
class BreakLifecycleTest {

    private static final Instant OPENED = Instant.parse("2026-09-24T10:00:00Z");
    private static final Instant LATER = OPENED.plusSeconds(3600);
    private static final Actor OPERATOR = Actor.operator("operator-001");
    private static final ItemRef PSP_LINE = new ItemRef(ItemSide.PSP, UUID.fromString("00000000-0000-4000-8000-000000000010"));
    private static final ItemRef LEDGER_ENTRY = new ItemRef(ItemSide.LEDGER, UUID.fromString("00000000-0000-4000-8000-000000000011"));

    /** The resulting status of each legal (from, action) pair. A pair not listed is illegal. */
    private static final Map<BreakStatus, Map<BreakAction, BreakStatus>> LEGAL = Map.of(
            OPEN, Map.of(INVESTIGATE, INVESTIGATING, RESOLVE, RESOLVED, AUTO_RESOLVE, RESOLVED),
            INVESTIGATING, Map.of(RESOLVE, RESOLVED, AUTO_RESOLVE, RESOLVED),
            RESOLVED, Map.of(REOPEN, OPEN));

    static Stream<Arguments> everyStatusAndAction() {
        return Arrays.stream(BreakStatus.values())
                .flatMap(status -> Arrays.stream(BreakAction.values()).map(action -> Arguments.of(status, action)));
    }

    @ParameterizedTest(name = "{1} from {0}")
    @MethodSource("everyStatusAndAction")
    @DisplayName("each action is allowed exactly from the statuses TDD 8.4 draws an edge from")
    void everyTransition(BreakStatus from, BreakAction action) {
        Break subject = breakIn(from);
        Optional<BreakStatus> expected = Optional.ofNullable(LEGAL.get(from).get(action));

        if (expected.isPresent()) {
            Transition transition = apply(subject, action);
            assertThat(transition.result().status()).isEqualTo(expected.get());
            assertThat(transition.event().to()).isEqualTo(expected.get());
        } else {
            assertThatThrownBy(() -> apply(subject, action))
                    .isInstanceOf(IllegalBreakTransitionException.class)
                    .satisfies(e -> {
                        assertThat(((IllegalBreakTransitionException) e).from()).isEqualTo(from);
                        assertThat(((IllegalBreakTransitionException) e).action()).isEqualTo(action);
                    });
        }
    }

    @Test
    @DisplayName("FR-BRK-6: opening a break records an event with no previous status")
    void openingRecordsAnEvent() {
        Transition opened = open();

        assertThat(opened.result().status()).isEqualTo(OPEN);
        assertThat(opened.event().from()).isEmpty();
        assertThat(opened.event().to()).isEqualTo(OPEN);
        assertThat(opened.event().actor()).isEqualTo(Actor.SYSTEM);
    }

    @Test
    @DisplayName("FR-BRK-4: an operator's resolution carries its code, reason, actor and time")
    void resolutionIsRecorded() {
        Transition resolved = open().result().resolve(ResolutionCode.PSP_ERROR_CONFIRMED, "Confirmed with the PSP", OPERATOR, LATER);

        assertThat(resolved.result().resolutionCode()).contains(ResolutionCode.PSP_ERROR_CONFIRMED);
        assertThat(resolved.result().resolvedAt()).contains(LATER);
        assertThat(resolved.event().from()).contains(OPEN);
        assertThat(resolved.event().resolutionCode()).contains(ResolutionCode.PSP_ERROR_CONFIRMED);
        assertThat(resolved.event().reason()).contains("Confirmed with the PSP");
        assertThat(resolved.event().actor()).isEqualTo(OPERATOR);
    }

    @Test
    @DisplayName("FR-BRK-4: resolving without a reason, or with one over 1000 characters, is rejected")
    void resolutionNeedsAReason() {
        Break open = open().result();

        assertThatThrownBy(() -> open.resolve(ResolutionCode.WRITTEN_OFF, " ", OPERATOR, LATER))
                .isInstanceOf(InvalidBreakException.class);
        assertThatThrownBy(() -> open.resolve(ResolutionCode.WRITTEN_OFF, null, OPERATOR, LATER))
                .isInstanceOf(InvalidBreakException.class);
        assertThatThrownBy(() -> open.resolve(ResolutionCode.WRITTEN_OFF, "x".repeat(1001), OPERATOR, LATER))
                .isInstanceOf(InvalidBreakException.class);
        assertThat(open.resolve(ResolutionCode.WRITTEN_OFF, "x".repeat(1000), OPERATOR, LATER).result().status())
                .isEqualTo(RESOLVED);
    }

    @Test
    @DisplayName("FR-BRK-5: an operator cannot resolve with MATCHED_LATE")
    void matchedLateIsSystemOnly() {
        assertThatThrownBy(() -> open().result().resolve(ResolutionCode.MATCHED_LATE, "It matched", OPERATOR, LATER))
                .isInstanceOf(InvalidBreakException.class);
    }

    @Test
    @DisplayName("FR-BRK-5: a late match is resolved by the system as MATCHED_LATE")
    void autoResolutionIsBySystem() {
        Transition resolved = open().result().resolveAsMatchedLate("Matched by a later run", LATER);

        assertThat(resolved.result().resolutionCode()).contains(ResolutionCode.MATCHED_LATE);
        assertThat(resolved.event().actor()).isEqualTo(Actor.SYSTEM);
    }

    @Test
    @DisplayName("only an operator investigates, resolves manually or reopens")
    void systemCannotActAsOperator() {
        Break open = open().result();
        Break resolved = open.resolveAsMatchedLate("Matched by a later run", LATER).result();

        assertThatThrownBy(() -> open.startInvestigation(Actor.SYSTEM, Optional.empty(), LATER))
                .isInstanceOf(InvalidBreakException.class);
        assertThatThrownBy(() -> open.resolve(ResolutionCode.WRITTEN_OFF, "reason", Actor.SYSTEM, LATER))
                .isInstanceOf(InvalidBreakException.class);
        assertThatThrownBy(() -> resolved.reopen(UUID.randomUUID(), Actor.SYSTEM, "reason", LATER))
                .isInstanceOf(InvalidBreakException.class);
    }

    @Test
    @DisplayName("an operator cannot be named as the system")
    void operatorCannotBeTheSystem() {
        assertThatThrownBy(() -> Actor.operator("system")).isInstanceOf(InvalidBreakException.class);
        assertThatThrownBy(() -> Actor.operator(" ")).isInstanceOf(InvalidBreakException.class);
    }

    @Test
    @DisplayName("FR-BRK-3: reopening creates a new OPEN break that points at the resolved one")
    void reopenCreatesANewBreak() {
        Break resolved = open().result().resolve(ResolutionCode.FALSE_POSITIVE, "Looked fine", OPERATOR, LATER).result();
        UUID newId = UUID.fromString("00000000-0000-4000-8000-000000000099");

        Transition reopened = resolved.reopen(newId, OPERATOR, "It was not fine", LATER.plusSeconds(60));

        assertThat(reopened.result().id()).isEqualTo(newId);
        assertThat(reopened.result().status()).isEqualTo(OPEN);
        assertThat(reopened.result().previousBreakId()).contains(resolved.id());
        assertThat(reopened.result().item()).isEqualTo(resolved.item());
        assertThat(reopened.event().breakId()).isEqualTo(newId);
        assertThat(resolved.status()).isEqualTo(RESOLVED);
    }

    @Test
    @DisplayName("a change dated before the break was opened is rejected")
    void changeBeforeOpeningIsRejected() {
        assertThatThrownBy(() -> open().result().startInvestigation(OPERATOR, Optional.empty(), OPENED.minusSeconds(1)))
                .isInstanceOf(InvalidBreakException.class);
    }

    @Test
    @DisplayName("a resolved break without its resolution code or time cannot be built")
    void resolvedStateIsConsistent() {
        assertThatThrownBy(() -> new Break(UUID.randomUUID(), BreakType.AMOUNT_MISMATCH, PSP_LINE, List.of(),
                RESOLVED, Optional.empty(), Optional.empty(), Optional.empty(), OPENED, Optional.of(LATER)))
                .isInstanceOf(InvalidBreakException.class);
        assertThatThrownBy(() -> new Break(UUID.randomUUID(), BreakType.AMOUNT_MISMATCH, PSP_LINE, List.of(),
                OPEN, Optional.of(ResolutionCode.WRITTEN_OFF), Optional.empty(), Optional.empty(), OPENED, Optional.empty()))
                .isInstanceOf(InvalidBreakException.class);
    }

    @Test
    @DisplayName("FR-BRK-1: the break types and resolution codes are the closed sets of TDD 8.3")
    void closedSets() {
        assertThat(BreakType.values()).extracting(Enum::name).containsExactly(
                "MISSING_IN_PSP", "MISSING_IN_LEDGER", "AMOUNT_MISMATCH", "CURRENCY_MISMATCH", "DUPLICATE_LINE",
                "AMBIGUOUS_MATCH", "MISSING_SETTLEMENT", "BATCH_AMOUNT_MISMATCH", "UNEXPECTED_BANK_LINE");
        assertThat(ResolutionCode.values()).extracting(Enum::name).containsExactly(
                "MATCHED_LATE", "MATCHED_MANUALLY", "ADJUSTMENT_REQUIRED_IN_LEDGER", "PSP_ERROR_CONFIRMED",
                "BANK_ERROR_CONFIRMED", "WRITTEN_OFF", "FALSE_POSITIVE", "DUPLICATE_CONFIRMED");
    }

    private static Transition open() {
        return Break.open(UUID.fromString("00000000-0000-4000-8000-000000000001"), BreakType.AMOUNT_MISMATCH,
                PSP_LINE, List.of(LEDGER_ENTRY), Optional.of(UUID.fromString("00000000-0000-4000-8000-000000000002")),
                Actor.SYSTEM, OPENED);
    }

    private static Break breakIn(BreakStatus status) {
        Break open = open().result();
        return switch (status) {
            case OPEN -> open;
            case INVESTIGATING -> open.startInvestigation(OPERATOR, Optional.of("Looking into it"), OPENED).result();
            case RESOLVED -> open.resolve(ResolutionCode.WRITTEN_OFF, "Below threshold", OPERATOR, OPENED).result();
        };
    }

    private static Transition apply(Break subject, BreakAction action) {
        return switch (action) {
            case INVESTIGATE -> subject.startInvestigation(OPERATOR, Optional.empty(), LATER);
            case RESOLVE -> subject.resolve(ResolutionCode.MATCHED_MANUALLY, "Matched by hand", OPERATOR, LATER);
            case AUTO_RESOLVE -> subject.resolveAsMatchedLate("Matched by a later run", LATER);
            case REOPEN -> subject.reopen(UUID.randomUUID(), OPERATOR, "Needs another look", LATER);
        };
    }
}
