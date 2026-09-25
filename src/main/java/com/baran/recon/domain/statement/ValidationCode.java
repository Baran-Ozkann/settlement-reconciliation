package com.baran.recon.domain.statement;

/** Why a line of an uploaded file is invalid (TDD 7.3). */
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
    UNSUPPORTED_CURRENCY,
    DATE_ORDER,
    DUPLICATE_LINE_IN_FILE
}
