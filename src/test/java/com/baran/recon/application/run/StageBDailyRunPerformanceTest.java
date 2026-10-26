package com.baran.recon.application.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * NFR-PERF-4, the daily shape: the scheduled run's range, the 30 days before the run's day and the
 * day itself (TDD 5.3). The data, the measurement and what is asserted are {@link StageBPerformanceTest}'s.
 */
@DisplayName("NFR-PERF-4: the scheduled bank run over 30 days, against 1,000,000 PSP lines and 180 days of matches, "
        + "under -Xmx512m")
class StageBDailyRunPerformanceTest extends StageBPerformanceTest {

    @Test
    @DisplayName("NFR-PERF-4 (daily): the run completes and does the work the data was built for; timed, no bound yet")
    void scheduledRunOverThirtyDays() throws Exception {
        measure(Shape.DAILY);
    }
}
