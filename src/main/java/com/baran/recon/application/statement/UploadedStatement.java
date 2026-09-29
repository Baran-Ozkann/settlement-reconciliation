package com.baran.recon.application.statement;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * One upload as the web adapter received it (FR-ING-1). Every field is the client's and none is
 * trusted: the source may name nothing, the reference may be malformed, and the file name is only
 * ever kept sanitized. {@code uploadedBy} is the authenticated principal (TDD 11.1).
 *
 * @param content the file's bytes, which can be read more than once: once to hash, once to parse
 */
public record UploadedStatement(String source, String statementReference, String originalFilename,
                                Content content, String uploadedBy) {

    public UploadedStatement {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(uploadedBy, "uploadedBy");
    }

    /** The upload's bytes, kept by the adapter somewhere that can be read again: never in memory. */
    @FunctionalInterface
    public interface Content {

        InputStream open() throws IOException;
    }
}
