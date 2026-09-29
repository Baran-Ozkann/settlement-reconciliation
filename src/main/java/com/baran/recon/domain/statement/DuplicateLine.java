package com.baran.recon.domain.statement;

import java.util.Objects;
import java.util.UUID;

/**
 * A line of an ingested file whose line id its source had already stored from another file
 * (FR-ING-4). It is not stored as a line; it is recorded as a DUPLICATE_LINE break on the line
 * already stored. When that line already had an unresolved break, INV-7 allows no second one, and
 * {@code breakId} names the break it already had.
 */
public record DuplicateLine(long lineNumber, UUID breakId, boolean breakOpened) {

    public DuplicateLine {
        if (lineNumber < 2) {
            throw new InvalidStatementFileException("a duplicate is a data line, numbered from 2");
        }
        Objects.requireNonNull(breakId, "breakId");
    }
}
