package com.baran.recon.adapters.in.kafka;

import java.util.Objects;

import com.baran.recon.application.ledger.LedgerEvent;

/** A ledger record after the contract check: an event to project, or the reason it cannot be. */
sealed interface ParsedRecord {

    record Accepted(LedgerEvent event) implements ParsedRecord {

        public Accepted {
            Objects.requireNonNull(event, "event");
        }
    }

    /** {@code message} names what failed and where, never a value taken from the record. */
    record Rejected(DeadLetterReason reason, String message) implements ParsedRecord {

        public Rejected {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(message, "message");
        }
    }
}
