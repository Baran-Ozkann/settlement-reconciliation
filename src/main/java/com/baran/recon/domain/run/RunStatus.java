package com.baran.recon.domain.run;

/** A run that crashed leaves no partial results (NFR-REL-2); FAILED records that it ran at all. */
public enum RunStatus {
    RUNNING,
    COMPLETED,
    FAILED
}
