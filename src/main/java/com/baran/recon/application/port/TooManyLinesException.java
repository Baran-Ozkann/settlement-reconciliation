package com.baran.recon.application.port;

/**
 * An uploaded file has more data lines than {@code recon.ingestion.max-lines} allows (FR-ING-8).
 * Reading stops at the first line over the limit, so a file of any length costs no more than the
 * limit to refuse. Like a file over the size limit it is refused outright with 413 and never
 * recorded: TDD 7.3 has no line code for it, and a REJECTED file carries line errors.
 */
public final class TooManyLinesException extends RuntimeException {

    private final long maxLines;

    public TooManyLinesException(long maxLines) {
        super("the file has more than " + maxLines + " data lines");
        this.maxLines = maxLines;
    }

    public long maxLines() {
        return maxLines;
    }
}
