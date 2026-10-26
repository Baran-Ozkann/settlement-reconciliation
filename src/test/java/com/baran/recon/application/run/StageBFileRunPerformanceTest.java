package com.baran.recon.application.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * NFR-PERF-4, the file shape: the run a bank file triggers, over the file's three days at the end of
 * the data. The data, the measurement and what is asserted are {@link StageBPerformanceTest}'s.
 */
@DisplayName("NFR-PERF-4: a bank file's run over three days, against 1,000,000 PSP lines and 180 days of matches, "
        + "under -Xmx512m")
class StageBFileRunPerformanceTest extends StageBPerformanceTest {

    @Test
    @DisplayName("NFR-PERF-4 (file): the run completes and does the work the data was built for; timed, no bound yet")
    void bankFileRunOverThreeDays() throws Exception {
        measure(Shape.FILE);
    }
}
