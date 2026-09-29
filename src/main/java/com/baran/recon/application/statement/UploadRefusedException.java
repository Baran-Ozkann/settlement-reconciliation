package com.baran.recon.application.statement;

import java.util.Optional;
import java.util.UUID;

/**
 * An upload refused before any of its lines was read, so nothing of it is recorded. The reason is
 * one of a closed set, so the web adapter can answer each with its own status and never has to read
 * a message to decide.
 */
public final class UploadRefusedException extends RuntimeException {

    public enum Reason {
        /** The source code names no configured source (FR-ING-1). */
        UNKNOWN_SOURCE,
        /** The statement reference is not 1-100 characters of [A-Za-z0-9._-]. */
        INVALID_STATEMENT_REFERENCE,
        /** A file with the same content was already ingested (FR-ING-3). */
        DUPLICATE_CONTENT,
        /** A file was already ingested under this source and statement reference (FR-ING-3). */
        DUPLICATE_REFERENCE
    }

    private final Reason reason;
    /** Null unless a duplicate: kept plain because an exception is serializable and Optional is not. */
    private final UUID originalFileId;

    private UploadRefusedException(Reason reason, Optional<UUID> originalFileId, String message) {
        super(message);
        this.reason = reason;
        this.originalFileId = originalFileId.orElse(null);
    }

    static UploadRefusedException unknownSource() {
        return new UploadRefusedException(Reason.UNKNOWN_SOURCE, Optional.empty(), "no configured source has this code");
    }

    static UploadRefusedException invalidStatementReference() {
        return new UploadRefusedException(Reason.INVALID_STATEMENT_REFERENCE, Optional.empty(),
                "a statement reference is 1-100 characters of [A-Za-z0-9._-]");
    }

    static UploadRefusedException duplicateContent(UUID original) {
        return new UploadRefusedException(Reason.DUPLICATE_CONTENT, Optional.of(original),
                "a file with the same content was already ingested");
    }

    static UploadRefusedException duplicateReference(UUID original) {
        return new UploadRefusedException(Reason.DUPLICATE_REFERENCE, Optional.of(original),
                "a file was already ingested under this source and statement reference");
    }

    public Reason reason() {
        return reason;
    }

    /** For a duplicate, the file that was ingested first (FR-ING-3). */
    public Optional<UUID> originalFileId() {
        return Optional.ofNullable(originalFileId);
    }
}
