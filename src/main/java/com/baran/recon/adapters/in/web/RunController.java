package com.baran.recon.adapters.in.web;

import java.net.URI;
import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baran.recon.application.run.RunMatching;
import com.baran.recon.application.run.ViewRun;
import com.baran.recon.domain.run.ReconciliationRun;

/**
 * {@code POST /api/v1/runs}, for an OPERATOR (FR-MAT-1, TDD 11): runs matching for a source and a
 * value-date range and answers when the run has finished, 201 with the run's status and statistics.
 * The authenticated user is recorded as the run's trigger (TDD 11.1). A refused run is answered by
 * {@link ApiExceptionHandler}: 400 for an unknown source or a range that ends before it starts, 409
 * naming the running run when the source is busy. A run whose work failed is FAILED, and the
 * request is answered 500 like any other failure of the application.
 *
 * <p>{@code GET /api/v1/runs/{id}}, for a VIEWER, shows a run's status and statistics.
 */
@RestController
@RequestMapping(RunController.PATH)
class RunController {

    static final String PATH = "/api/v1/runs";

    private final RunMatching matching;
    private final ViewRun view;

    RunController(RunMatching matching, ViewRun view) {
        this.matching = matching;
        this.view = view;
    }

    @GetMapping("/{id}")
    ResponseEntity<?> get(@PathVariable("id") UUID id) {
        return view.find(id).<ResponseEntity<?>>map(run -> ResponseEntity.ok(RunView.of(run)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No run has this id.")));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunView> start(@RequestBody RunRequest request, Principal principal) {
        ReconciliationRun run = matching.run(request.source(), date(request.valueDateFrom()),
                date(request.valueDateTo()), principal.getName());
        return ResponseEntity.created(URI.create(PATH + "/" + run.id())).body(RunView.of(run));
    }

    /** A strict ISO date, so a day a month does not have is refused rather than moved. */
    private static LocalDate date(String text) {
        if (text == null) {
            throw new MalformedRunRequestException();
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException malformed) {
            throw new MalformedRunRequestException();
        }
    }

    /** The body of a run request; the dates are read as text so a malformed one gets the API's own answer. */
    record RunRequest(String source, String valueDateFrom, String valueDateTo) {
    }
}
