package com.baran.recon.application.port;

/**
 * Another file with the same content, or the same source and statement reference, was ingested
 * first (FR-ING-3). Ingestion checks both before it reads a file, so this is what a concurrent
 * upload of the same file meets when the partial unique indexes decide between the two at the end.
 */
public final class StatementFileAlreadyIngestedException extends RuntimeException {

    public StatementFileAlreadyIngestedException(Throwable cause) {
        super("a statement file with this content or reference was ingested first", cause);
    }
}
