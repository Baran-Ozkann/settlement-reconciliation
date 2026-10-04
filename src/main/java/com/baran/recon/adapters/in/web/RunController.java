package com.baran.recon.adapters.in.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baran.recon.application.run.ViewRun;

/** {@code GET /api/v1/runs/{id}}, for a VIEWER (TDD 11): a run's status and statistics. */
@RestController
@RequestMapping(RunController.PATH)
class RunController {

    static final String PATH = "/api/v1/runs";

    private final ViewRun view;

    RunController(ViewRun view) {
        this.view = view;
    }

    @GetMapping("/{id}")
    ResponseEntity<?> get(@PathVariable("id") UUID id) {
        return view.find(id).<ResponseEntity<?>>map(run -> ResponseEntity.ok(RunView.of(run)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No run has this id.")));
    }
}
