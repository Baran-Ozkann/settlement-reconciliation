package com.baran.recon.domain.breaks;

import java.util.Objects;

/**
 * Who changed a break: the authenticated principal (TDD 11.1), or the system when a run acted on
 * its own. An operator can never be the system, so a user who happens to be called "system" cannot
 * pass off a manual resolution as an automatic one.
 */
public record Actor(String name) {

    public static final Actor SYSTEM = new Actor("system");

    private static final int MAX_LENGTH = 100;

    public Actor {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > MAX_LENGTH) {
            throw new InvalidBreakException("actor name must be 1-" + MAX_LENGTH + " characters");
        }
    }

    public static Actor operator(String name) {
        if (SYSTEM.name().equals(name)) {
            throw new InvalidBreakException("an operator cannot act as the system");
        }
        return new Actor(name);
    }

    public boolean isSystem() {
        return equals(SYSTEM);
    }
}
