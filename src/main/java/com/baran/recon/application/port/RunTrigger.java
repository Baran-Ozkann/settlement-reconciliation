package com.baran.recon.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.baran.recon.domain.item.SourceCode;

/**
 * What follows a committed ingestion (FR-MAT-1, TDD 5.3): a run for the file's source over the value
 * dates of the lines it stored, and after a PSP file also a run of the bank source it settles to over
 * the same range. The ingestion is already committed when this is called, so an implementation
 * returns without waiting for the runs and throws nothing: the upload has succeeded whatever becomes
 * of its runs.
 */
public interface RunTrigger {

    /** Starts nothing. */
    RunTrigger NONE = file -> {
    };

    void fileIngested(IngestedFile file);

    /**
     * An ingested file's source, the bank source a PSP file's source settles to, and the earliest and
     * latest value dates among the lines it stored. A file that stored no line has no such dates and
     * triggers nothing.
     */
    record IngestedFile(UUID fileId, SourceCode source, Optional<SourceCode> settlesTo, LocalDate valueDateFrom,
                        LocalDate valueDateTo) {

        public IngestedFile {
            Objects.requireNonNull(fileId, "fileId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(settlesTo, "settlesTo");
            Objects.requireNonNull(valueDateFrom, "valueDateFrom");
            Objects.requireNonNull(valueDateTo, "valueDateTo");
            if (valueDateFrom.isAfter(valueDateTo)) {
                throw new IllegalArgumentException("the value dates end before they start");
            }
        }

        /**
         * The sources to run over the file's range, in order: the file's own, then the bank source it
         * settles to. A bank line whose batch arrived late is matched by that second run, since B1
         * pairs a bank line and a batch when either is in the range (TDD 5.3, 8.2).
         */
        public List<SourceCode> runSources() {
            return Stream.concat(Stream.of(source), settlesTo.stream()).toList();
        }
    }
}
