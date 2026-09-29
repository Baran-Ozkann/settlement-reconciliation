package com.baran.recon.domain.statement;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.baran.recon.domain.item.SourceCode;

/**
 * An uploaded statement file as recorded (TDD 10). Only the sanitized file name is ever kept
 * (FR-ING-9), and a rejected file carries the line numbers and codes that rejected it (FR-ING-7).
 * An ingested file may carry some too, when the configured invalid-line threshold allows them, and
 * the lines it repeated from other files (FR-ING-4). A rejected file has no duplicates: nothing of it
 * was stored, and no break was opened for it.
 */
public record StatementFile(
        UUID id,
        SourceCode source,
        String statementReference,
        String sha256,
        String sanitizedFilename,
        long sizeBytes,
        StatementFileStatus status,
        LineSummary lines,
        String uploadedBy,
        Instant receivedAt) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /**
     * The operator's name for the statement, unique per source among ingested files (FR-ING-3). The
     * same allow-list as a stored file name: it is shown and logged, and nothing a statement needs
     * to be called is outside it.
     */
    private static final Pattern STATEMENT_REFERENCE = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final int MAX_UPLOADER_LENGTH = 100;

    public static boolean isValidStatementReference(String reference) {
        return reference != null && STATEMENT_REFERENCE.matcher(reference).matches();
    }

    public StatementFile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (!isValidStatementReference(statementReference)) {
            throw new InvalidStatementFileException("a statement reference is 1-100 characters of [A-Za-z0-9._-]");
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new InvalidStatementFileException("sha256 is 64 lower-case hex digits");
        }
        if (sanitizedFilename == null || !SanitizedFilename.isSanitized(sanitizedFilename)) {
            throw new InvalidStatementFileException("the file name is stored only in sanitized form");
        }
        if (sizeBytes < 0) {
            throw new InvalidStatementFileException("size is not negative");
        }
        if (status == StatementFileStatus.REJECTED && lines.allErrors().isEmpty()) {
            throw new InvalidStatementFileException("a rejected file carries the errors that rejected it");
        }
        if (status == StatementFileStatus.INGESTED && lines.headerError().isPresent()) {
            throw new InvalidStatementFileException("a file without a valid header cannot be ingested");
        }
        if (status == StatementFileStatus.REJECTED && lines.duplicateLineCount() > 0) {
            throw new InvalidStatementFileException("a rejected file stored nothing, so it repeated nothing");
        }
        if (uploadedBy == null || uploadedBy.isBlank() || uploadedBy.length() > MAX_UPLOADER_LENGTH) {
            throw new InvalidStatementFileException("uploaded_by is 1-" + MAX_UPLOADER_LENGTH + " characters");
        }
    }
}
