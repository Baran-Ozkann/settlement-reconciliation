package com.baran.recon.application.port;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.baran.recon.domain.item.SourceCode;

/**
 * What follows a committed ingestion (FR-MAT-1): a run for the file's source over the value dates of
 * the lines it stored. The ingestion is already committed when this is called, so an implementation
 * returns without waiting for the run and throws nothing: the upload has succeeded whatever becomes of
 * its run.
 */
public interface RunTrigger {

    /** Starts nothing. */
    RunTrigger NONE = file -> {
    };

    void fileIngested(IngestedFile file);

    /**
     * An ingested file's source and the earliest and latest value dates among the lines it stored. A
     * file that stored no line has no such dates and triggers nothing.
     */
    record IngestedFile(UUID fileId, SourceCode source, LocalDate valueDateFrom, LocalDate valueDateTo) {

        public IngestedFile {
            Objects.requireNonNull(fileId, "fileId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(valueDateFrom, "valueDateFrom");
            Objects.requireNonNull(valueDateTo, "valueDateTo");
            if (valueDateFrom.isAfter(valueDateTo)) {
                throw new IllegalArgumentException("the value dates end before they start");
            }
        }
    }
}
