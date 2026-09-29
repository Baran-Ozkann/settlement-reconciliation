package com.baran.recon.domain.statement;

/**
 * Why a line of an uploaded file is invalid (TDD 7.3).
 *
 * <p>{@link #INVALID_CURRENCY} means the text is not an ISO 4217 code at all. A real code outside
 * {@code recon.supported-currencies} is not a validation error: the line records money that really
 * moved, so it is ingested and matching reports it, as the ledger projection does (FR-LED-9).
 */
public enum ValidationCode {
    HEADER_MISMATCH,
    LINE_TOO_LONG,
    INVALID_ENCODING,
    COLUMN_COUNT,
    REQUIRED_MISSING,
    INVALID_FORMAT,
    INVALID_DATE,
    INVALID_AMOUNT,
    SCALE_EXCEEDS_CURRENCY,
    SIGN_TYPE_MISMATCH,
    NET_AMOUNT_MISMATCH,
    INVALID_CURRENCY,
    DATE_ORDER,
    DUPLICATE_LINE_IN_FILE
}
