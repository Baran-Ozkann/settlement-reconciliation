package com.baran.recon.adapters.in.web;

import java.util.StringJoiner;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.baran.recon.application.port.StatementFileAlreadyIngestedException;
import com.baran.recon.application.port.TooManyLinesException;
import com.baran.recon.application.statement.UploadRefusedException;

/**
 * Every error the API answers is RFC 9457 Problem Details (FR-API-2), and none carries a stack
 * trace, SQL, a file path, an exception message or the uploaded file's name. Spring MVC's own
 * exceptions keep the statuses and generic details the base class gives them.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** FR-ING-1, FR-ING-3, FR-ING-8: refused before a line was read, so nothing of the upload is recorded. */
    @ExceptionHandler(UploadRefusedException.class)
    ProblemDetail uploadRefused(UploadRefusedException refused) {
        ProblemDetail problem = switch (refused.reason()) {
            case UNKNOWN_SOURCE -> ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "No configured source has this code.");
            case INVALID_STATEMENT_REFERENCE -> ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "A statement reference is 1-100 characters of [A-Za-z0-9._-].");
            case DUPLICATE_CONTENT -> ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "A file with the same content was already ingested.");
            case DUPLICATE_REFERENCE -> ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "A file was already ingested under this source and statement reference.");
            case FILE_TOO_LARGE -> ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                    "The file is larger than the file size limit.");
        };
        refused.originalFileId().ifPresent(original -> problem.setProperty("originalStatementId", original));
        return problem;
    }

    /**
     * FR-ING-3: a concurrent upload of the same file, or under the same reference, committed first.
     * The use case answers with the original's id when it can find it; this is the case it cannot.
     */
    @ExceptionHandler(StatementFileAlreadyIngestedException.class)
    ProblemDetail ingestedConcurrently(StatementFileAlreadyIngestedException concurrent) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "A file with the same content or statement reference was ingested first.");
    }

    /** FR-ING-8: refused like a file over the size limit, and, like it, never recorded. */
    @ExceptionHandler(TooManyLinesException.class)
    ProblemDetail tooManyLines(TooManyLinesException tooMany) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "The file has more than " + tooMany.maxLines() + " data lines.");
    }

    /** FR-ING-8: the servlet container stopped reading the upload at the size limit. */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException tooLarge,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "The upload is larger than the file size limit.");
        return handleExceptionInternal(tooLarge, problem, headers, HttpStatus.CONTENT_TOO_LARGE, request);
    }

    /**
     * Anything else is the application's failure, not the request's. The log names it by its
     * exception classes only: a database error's message can quote a row, and a file system error's
     * a path. The ingestion that failed was rolled back whole (FR-ING-6), so a retry is safe.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception failure, HttpServletRequest request) {
        LOG.error("{} {} failed, caused by {}", request.getMethod(), request.getRequestURI(), causes(failure));
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");
    }

    /** The chain of exception classes, outermost first. */
    private static String causes(Throwable failure) {
        StringJoiner chain = new StringJoiner(" <- ");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            chain.add(cause.getClass().getName());
        }
        return chain.toString();
    }
}
