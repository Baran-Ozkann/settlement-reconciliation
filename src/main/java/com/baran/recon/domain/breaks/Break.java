package com.baran.recon.domain.breaks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.baran.recon.domain.item.ItemRef;

/**
 * A discrepancy that needs a person (TDD 8.3, 8.4). A break is immutable: every change returns a
 * {@link Transition} carrying the changed break and the event that records it, and a change the
 * lifecycle in {@link BreakAction} has no edge for throws {@link IllegalBreakTransitionException}.
 *
 * <p>A resolved break stays resolved. Reopening one creates a new break that points back at it
 * (FR-BRK-3), so the history of the first is never rewritten.
 */
public record Break(
        UUID id,
        BreakType type,
        ItemRef item,
        List<ItemRef> relatedItems,
        BreakStatus status,
        Optional<ResolutionCode> resolutionCode,
        Optional<UUID> openedRunId,
        Optional<UUID> previousBreakId,
        Instant openedAt,
        Optional<Instant> resolvedAt) {

    /** FR-BRK-4. */
    public static final int MAX_REASON_LENGTH = 1000;

    public Break {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(openedAt, "openedAt");
        relatedItems = List.copyOf(relatedItems);
        if (status.isResolved() != resolutionCode.isPresent() || status.isResolved() != resolvedAt.isPresent()) {
            throw new InvalidBreakException("a break has a resolution code and time exactly when it is resolved");
        }
    }

    /** A new break, OPEN, and the event that opens it. */
    public static Transition open(UUID id, BreakType type, ItemRef item, List<ItemRef> relatedItems,
                                  Optional<UUID> openedRunId, Actor actor, Instant at) {
        Break opened = new Break(id, type, item, relatedItems, BreakStatus.OPEN, Optional.empty(), openedRunId,
                Optional.empty(), at, Optional.empty());
        return new Transition(opened, new BreakEvent(id, Optional.empty(), BreakStatus.OPEN, Optional.empty(),
                actor, Optional.empty(), at));
    }

    public Transition startInvestigation(Actor operator, Optional<String> reason, Instant at) {
        require(BreakAction.INVESTIGATE, at);
        requireOperator(operator);
        reason.ifPresent(Break::requireReasonLength);
        return moveTo(BreakStatus.INVESTIGATING, Optional.empty(), operator, reason, at);
    }

    /** An operator's resolution: any code but MATCHED_LATE, and a reason (FR-BRK-4). */
    public Transition resolve(ResolutionCode code, String reason, Actor operator, Instant at) {
        require(BreakAction.RESOLVE, at);
        requireOperator(operator);
        if (code.isSystemOnly()) {
            throw new InvalidBreakException(code + " is recorded by the system only");
        }
        return moveTo(BreakStatus.RESOLVED, Optional.of(code), operator, Optional.of(requireReason(reason)), at);
    }

    /** A later run matched the item: resolved by the system as MATCHED_LATE (FR-BRK-5). */
    public Transition resolveAsMatchedLate(String reason, Instant at) {
        require(BreakAction.AUTO_RESOLVE, at);
        return moveTo(BreakStatus.RESOLVED, Optional.of(ResolutionCode.MATCHED_LATE), Actor.SYSTEM,
                Optional.of(requireReason(reason)), at);
    }

    /** A new OPEN break about the same item, pointing back at this resolved one (FR-BRK-3). */
    public Transition reopen(UUID newId, Actor operator, String reason, Instant at) {
        require(BreakAction.REOPEN, at);
        requireOperator(operator);
        Break reopened = new Break(newId, type, item, relatedItems, BreakStatus.OPEN, Optional.empty(),
                Optional.empty(), Optional.of(id), at, Optional.empty());
        return new Transition(reopened, new BreakEvent(newId, Optional.empty(), BreakStatus.OPEN, Optional.empty(),
                operator, Optional.of(requireReason(reason)), at));
    }

    private Transition moveTo(BreakStatus to, Optional<ResolutionCode> code, Actor actor, Optional<String> reason,
                              Instant at) {
        Optional<Instant> resolved = to.isResolved() ? Optional.of(at) : Optional.empty();
        Break changed = new Break(id, type, item, relatedItems, to, code, openedRunId, previousBreakId, openedAt,
                resolved);
        return new Transition(changed, new BreakEvent(id, Optional.of(status), to, code, actor, reason, at));
    }

    private void require(BreakAction action, Instant at) {
        if (!action.isAllowedFrom(status)) {
            throw new IllegalBreakTransitionException(status, action);
        }
        if (at.isBefore(openedAt)) {
            throw new InvalidBreakException("a change cannot happen before the break was opened");
        }
    }

    private static void requireOperator(Actor actor) {
        if (actor.isSystem()) {
            throw new InvalidBreakException("only an operator can do this");
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidBreakException("a reason is required");
        }
        requireReasonLength(reason);
        return reason;
    }

    private static void requireReasonLength(String reason) {
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new InvalidBreakException("a reason is at most " + MAX_REASON_LENGTH + " characters");
        }
    }
}
