package com.baran.recon.adapters.in.web;

/** A run request whose value dates are missing or not ISO dates; refused before any run is recorded. */
final class MalformedRunRequestException extends RuntimeException {

    MalformedRunRequestException() {
        super("valueDateFrom and valueDateTo must be ISO dates", null, false, false);
    }
}
