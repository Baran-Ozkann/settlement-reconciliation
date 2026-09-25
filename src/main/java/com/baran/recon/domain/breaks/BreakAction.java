package com.baran.recon.domain.breaks;

import java.util.EnumSet;
import java.util.Set;

/**
 * Everything that can be done to an existing break, and the statuses it can be done from. This is
 * the whole lifecycle of TDD 8.4 in one table:
 * <pre>
 *   OPEN          --investigate-->  INVESTIGATING
 *   OPEN          --resolve------>  RESOLVED        (operator, code + reason)
 *   INVESTIGATING --resolve------>  RESOLVED        (operator, code + reason)
 *   OPEN          --auto-resolve->  RESOLVED        (system, MATCHED_LATE)
 *   INVESTIGATING --auto-resolve->  RESOLVED        (system, MATCHED_LATE)
 *   RESOLVED      --reopen------->  a new break, OPEN, pointing back at this one
 * </pre>
 */
public enum BreakAction {
    INVESTIGATE("start investigating", EnumSet.of(BreakStatus.OPEN)),
    RESOLVE("resolve", EnumSet.of(BreakStatus.OPEN, BreakStatus.INVESTIGATING)),
    AUTO_RESOLVE("auto-resolve", EnumSet.of(BreakStatus.OPEN, BreakStatus.INVESTIGATING)),
    REOPEN("reopen", EnumSet.of(BreakStatus.RESOLVED));

    private final String description;
    private final Set<BreakStatus> allowedFrom;

    BreakAction(String description, Set<BreakStatus> allowedFrom) {
        this.description = description;
        this.allowedFrom = allowedFrom;
    }

    public boolean isAllowedFrom(BreakStatus status) {
        return allowedFrom.contains(status);
    }

    String describe() {
        return description;
    }
}
