package com.baran.recon.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.baran.recon.domain.statement.DuplicateLine;
import com.baran.recon.domain.statement.LineError;
import com.baran.recon.domain.statement.StatementFile;

/**
 * A statement file as the API shows it: its status, counts, and the line numbers and codes of its
 * errors (FR-ING-7), never a line's content. The file name is the stored, sanitized one (FR-ING-9).
 * The lists hold the first {@value com.baran.recon.domain.statement.LineSummary#MAX_LISTED} of each;
 * the counts are complete.
 */
record StatementView(
        UUID id,
        String source,
        String statementReference,
        String status,
        String sha256,
        String filename,
        long sizeBytes,
        long lineCount,
        long storedLineCount,
        long invalidLineCount,
        List<LineErrorView> errors,
        long duplicateLineCount,
        List<DuplicateLineView> duplicates,
        String uploadedBy,
        Instant receivedAt) {

    static StatementView of(StatementFile file) {
        return new StatementView(file.id(), file.source().value(), file.statementReference(), file.status().name(),
                file.sha256(), file.sanitizedFilename(), file.sizeBytes(), file.lines().lineCount(),
                file.lines().storedLineCount(), file.lines().invalidLineCount(), LineErrorView.of(file.lines().allErrors()),
                file.lines().duplicateLineCount(), file.lines().duplicates().stream().map(DuplicateLineView::of).toList(),
                file.uploadedBy(), file.receivedAt());
    }

    record LineErrorView(long line, String code) {

        static List<LineErrorView> of(List<LineError> errors) {
            return errors.stream().map(error -> new LineErrorView(error.lineNumber(), error.code().name())).toList();
        }
    }

    /**
     * A line that repeated a line another file stored (FR-ING-4): the DUPLICATE_LINE break it opened,
     * or, when that line already had an unresolved break, the break it already had (INV-7).
     */
    record DuplicateLineView(long line, UUID breakId, boolean breakOpened) {

        static DuplicateLineView of(DuplicateLine duplicate) {
            return new DuplicateLineView(duplicate.lineNumber(), duplicate.breakId(), duplicate.breakOpened());
        }
    }
}
