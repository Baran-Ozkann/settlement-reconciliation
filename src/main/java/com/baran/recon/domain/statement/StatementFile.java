package com.baran.recon.domain.statement;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.baran.recon.domain.item.SourceCode;

/**
 * An uploaded statement file as recorded (TDD 10). Only the sanitized file name is ever kept
 * (FR-ING-9), and a rejected file carries the line numbers and codes that rejected it (FR-ING-7).
 * An ingested file may carry some too, when the configured invalid-line threshold allows them.
 */
public record StatementFile(
        UUID id,
        SourceCode source,
        String statementReference,
        String sha256,
        String sanitizedFilename,
        long sizeBytes,
        long lineCount,
        StatementFileStatus status,
        List<LineError> errors,
        String uploadedBy,
        Instant receivedAt) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SANITIZED_FILENAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final int MAX_UPLOADER_LENGTH = 100;

    public StatementFile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (statementReference == null || statementReference.isEmpty()) {
            throw new InvalidStatementFileException("a statement reference is required");
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new InvalidStatementFileException("sha256 is 64 lower-case hex digits");
        }
        if (sanitizedFilename == null || !SANITIZED_FILENAME.matcher(sanitizedFilename).matches()) {
            throw new InvalidStatementFileException("the file name is stored only in sanitized form");
        }
        if (sizeBytes < 0 || lineCount < 0) {
            throw new InvalidStatementFileException("size and line count are not negative");
        }
        errors = List.copyOf(errors);
        if (status == StatementFileStatus.REJECTED && errors.isEmpty()) {
            throw new InvalidStatementFileException("a rejected file carries the errors that rejected it");
        }
        if (uploadedBy == null || uploadedBy.isBlank() || uploadedBy.length() > MAX_UPLOADER_LENGTH) {
            throw new InvalidStatementFileException("uploaded_by is 1-" + MAX_UPLOADER_LENGTH + " characters");
        }
    }
}
