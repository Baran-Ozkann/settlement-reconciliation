package com.baran.recon.application.port;

import java.util.Objects;
import java.util.UUID;

import com.baran.recon.domain.source.SourceDefinition;

/** What a parser needs to know about the file it reads: the file's id, and the source it came from. */
public record StatementContext(UUID fileId, SourceDefinition source) {

    public StatementContext {
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(source, "source");
    }
}
