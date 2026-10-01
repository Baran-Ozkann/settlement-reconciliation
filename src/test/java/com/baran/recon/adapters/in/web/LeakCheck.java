package com.baran.recon.adapters.in.web;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an error body sent to a client must never carry (FR-API-2, CLAUDE.md 3.2): a stack trace or
 * an exception's name, SQL or a table name, a file system path - the temp directory's above all -
 * or the name the uploaded file was sent under.
 */
final class LeakCheck {

    private static final List<String> FORBIDDEN = List.of(
            "Exception", "exception", "\tat ", "at com.", "at org.", "at java.", "java.", "org.springframework",
            "com.baran", "trace", "SQL", "SELECT", "INSERT", "UPDATE", "psp_lines", "bank_lines", "statement_files",
            "breaks", "recon_app", "constraint", ":\\", "\\\\", "test-uploads", "upload_", ".tmp", "tmp/", "Temp");

    private LeakCheck() {
    }

    static void assertLeaksNothing(String body, String uploadedFilename) {
        assertThat(body).as("an error body").isNotBlank();
        assertThat(body).doesNotContain(uploadedFilename);
        FORBIDDEN.forEach(word -> assertThat(body).as("an error body").doesNotContain(word));
    }
}
