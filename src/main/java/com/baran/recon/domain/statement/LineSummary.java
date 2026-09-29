package com.baran.recon.domain.statement;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What became of a file's data lines. Every data line was stored, refused with a line error, or
 * recorded as a duplicate of a line another file stored (FR-ING-4, FR-ING-7). A file whose header
 * is missing or wrong has no data lines at all: none could be read by a header it does not have, so
 * the header's error on line 1 is the whole summary.
 *
 * <p>The counts are complete; the lists keep the first {@value #MAX_LISTED} of each, in file order.
 * A file of two million invalid lines is reported by its count and a sample, so neither memory nor
 * the stored summary grows with the file (FR-ING-5).
 */
public record LineSummary(Optional<LineError> headerError, long lineCount, long invalidLineCount,
                          List<LineError> errors, long duplicateLineCount, List<DuplicateLine> duplicates) {

    public static final int MAX_LISTED = 1_000;

    public LineSummary {
        errors = List.copyOf(errors);
        duplicates = List.copyOf(duplicates);
        if (lineCount < 0 || invalidLineCount < 0 || duplicateLineCount < 0) {
            throw new InvalidStatementFileException("line counts are not negative");
        }
        if (invalidLineCount + duplicateLineCount > lineCount) {
            throw new InvalidStatementFileException("more lines refused or repeated than the file has");
        }
        if (errors.size() != Math.min(invalidLineCount, MAX_LISTED)) {
            throw new InvalidStatementFileException("the errors listed are the first of the invalid lines, up to "
                    + MAX_LISTED);
        }
        if (duplicates.size() != Math.min(duplicateLineCount, MAX_LISTED)) {
            throw new InvalidStatementFileException("the duplicates listed are the first of the repeated lines, up to "
                    + MAX_LISTED);
        }
        if (errors.stream().anyMatch(error -> error.lineNumber() < 2)) {
            throw new InvalidStatementFileException("line 1 is the header; data lines are numbered from 2");
        }
        headerError.ifPresent(error -> {
            if (error.lineNumber() != 1 || lineCount != 0) {
                throw new InvalidStatementFileException("a header error is on line 1, and no data line follows it");
            }
        });
    }

    /** A file refused at its header: no data line was read. */
    public static LineSummary headerRejected(LineError headerError) {
        return new LineSummary(Optional.of(headerError), 0, 0, List.of(), 0, List.of());
    }

    /** The errors that describe the file, the header's first. */
    public List<LineError> allErrors() {
        return Stream.concat(headerError.stream(), errors.stream()).toList();
    }

    /** Lines stored as lines of the file. */
    public long storedLineCount() {
        return lineCount - invalidLineCount - duplicateLineCount;
    }
}
