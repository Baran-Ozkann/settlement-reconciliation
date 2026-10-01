package com.baran.recon.adapters.in.web;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.baran.recon.application.statement.IngestStatement;
import com.baran.recon.application.statement.UploadedStatement;
import com.baran.recon.domain.statement.StatementFile;
import com.baran.recon.domain.statement.StatementFileStatus;

/**
 * {@code POST /api/v1/statements} (FR-ING-1), for an OPERATOR (TDD 11). The file arrives already
 * kept by the servlet container in the configured temp directory, and the use case reads it from
 * there as a stream, twice: once to hash it, once to parse it. Nothing here touches the filesystem.
 *
 * <p>An ingested file is 201 with its summary. A rejected file is recorded too, and is 422 with the
 * line numbers and codes that rejected it (FR-ING-7). Every refusal before that is answered by
 * {@link ApiExceptionHandler}.
 */
@RestController
@RequestMapping(StatementController.PATH)
class StatementController {

    static final String PATH = "/api/v1/statements";

    private static final Logger LOG = LoggerFactory.getLogger(StatementController.class);

    private final IngestStatement ingest;

    StatementController(IngestStatement ingest) {
        this.ingest = ingest;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> upload(@RequestParam("source") String source,
                             @RequestParam("statementReference") String statementReference,
                             @RequestPart("file") MultipartFile file,
                             Principal principal) throws IOException {
        StatementFile recorded = ingest.ingest(new UploadedStatement(source, statementReference,
                file.getOriginalFilename(), file::getInputStream, principal.getName()));
        // The client's file name and reference stay out: only ids, codes and counts are logged.
        LOG.info("Statement file {} for source {} {}: {} data lines, {} invalid, {} repeated from other files",
                recorded.id(), recorded.source().value(), recorded.status(), recorded.lines().lineCount(),
                recorded.lines().invalidLineCount(), recorded.lines().duplicateLineCount());
        if (recorded.status() == StatementFileStatus.INGESTED) {
            return ResponseEntity.created(URI.create(PATH + "/" + recorded.id())).body(StatementView.of(recorded));
        }
        return ResponseEntity.unprocessableContent().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(rejection(recorded));
    }

    private static ProblemDetail rejection(StatementFile rejected) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                rejected.lines().headerError().isPresent()
                        ? "The file was rejected: its header is not the source's format."
                        : "The file was rejected: " + rejected.lines().invalidLineCount() + " of "
                                + rejected.lines().lineCount() + " data lines are invalid.");
        problem.setProperty("statementId", rejected.id());
        problem.setProperty("lineCount", rejected.lines().lineCount());
        problem.setProperty("invalidLineCount", rejected.lines().invalidLineCount());
        problem.setProperty("errors", StatementView.LineErrorView.of(rejected.lines().allErrors()));
        return problem;
    }
}
