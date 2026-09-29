package com.baran.recon.application.statement;

/**
 * The configured limits of FR-ING-8 that a parser enforces while it reads, and the invalid-line
 * threshold of FR-ING-7. The file size limit is not here: it is enforced before the upload reaches
 * the application, at the servlet, so an oversize body is never written out in full.
 *
 * @param maxLineBytes                  longest line, in bytes, not counting its ending
 * @param maxLines                      most data lines a file may have
 * @param maxInvalidLineRatioBasisPoints invalid lines a file may have and still be ingested, in
 *                                      hundredths of a percent of its data lines; 0 means none. An
 *                                      integer, since no floating point enters the application (INV-8)
 */
public record IngestionLimits(int maxLineBytes, long maxLines, int maxInvalidLineRatioBasisPoints) {

    private static final int ALL = 10_000;

    public IngestionLimits {
        if (maxLineBytes < 1 || maxLines < 1) {
            throw new IllegalArgumentException("the line limits must be positive");
        }
        if (maxInvalidLineRatioBasisPoints < 0 || maxInvalidLineRatioBasisPoints > ALL) {
            throw new IllegalArgumentException("the invalid-line ratio is 0-10000 basis points");
        }
    }

    /** FR-ING-7: the file is rejected when its invalid-line ratio exceeds the threshold. */
    boolean rejects(long invalidLines, long lines) {
        return invalidLines * ALL > (long) maxInvalidLineRatioBasisPoints * lines;
    }
}
