package com.baran.recon.application.run;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.source.BatchIdPattern;
import com.baran.recon.domain.source.ConfiguredSources;
import com.baran.recon.domain.source.SourceDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The daily scheduled run alone (TDD 5.3, v1.19), on the automatic trigger with a stand-in for the
 * run use case: which sources it queues, in which order, over which range, as whom. The runs
 * themselves, and the grace breaks only they open, are tested with the application in
 * ScheduledRunGraceTest.
 */
@DisplayName("TDD 5.3 (v1.19): the daily scheduled run queues every source over its look-back range")
class ScheduledRunsTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    /** 01:30 on Tuesday 2026-10-13 in Istanbul, still Monday in UTC. */
    private static final Clock JUST_AFTER_MIDNIGHT = Clock.fixed(Instant.parse("2026-10-12T22:30:00Z"), ZoneOffset.UTC);
    private static final BatchIdPattern PATTERN = BatchIdPattern.of("BATCH[-_]?([A-Za-z0-9_-]{1,64})");

    /** Configuration order mixes the types: a bank first, a PSP source, a second bank, a second PSP source. */
    private static final ConfiguredSources SOURCES = ConfiguredSources.of(List.of(
            SourceDefinition.bank(code("BANK_SCHEDULE_NORTH"), PATTERN),
            SourceDefinition.psp(code("PSP_SCHEDULE_ONE"), Set.of(), code("BANK_SCHEDULE_SOUTH")),
            SourceDefinition.bank(code("BANK_SCHEDULE_SOUTH"), PATTERN),
            SourceDefinition.psp(code("PSP_SCHEDULE_TWO"), Set.of(), code("BANK_SCHEDULE_NORTH"))));

    private final ConcurrentLinkedQueue<String> started = new ConcurrentLinkedQueue<>();
    private AutomaticRunTrigger trigger;

    @AfterEach
    void closeTrigger() throws InterruptedException {
        if (trigger != null) {
            trigger.close();
        }
    }

    @Test
    @DisplayName("TDD 5.3 (v1.19): the PSP sources' runs come first, then the bank sources', each in configuration "
            + "order, every one over [today - look-back-days, today] with today in the value-date zone, as the system")
    void queuesPspSourcesThenBankSourcesOverTheLookBack() {
        trigger = recordingTrigger();

        new ScheduledRuns(SOURCES, trigger, JUST_AFTER_MIDNIGHT, ISTANBUL, 30).queueDailyRuns();

        await().atMost(WAIT).untilAsserted(() -> assertThat(started).containsExactly(
                "PSP_SCHEDULE_ONE 2026-09-13 2026-10-13 system",
                "PSP_SCHEDULE_TWO 2026-09-13 2026-10-13 system",
                "BANK_SCHEDULE_NORTH 2026-09-13 2026-10-13 system",
                "BANK_SCHEDULE_SOUTH 2026-09-13 2026-10-13 system"));
    }

    @Test
    @DisplayName("TDD 5.3 (v1.19): a look-back of one day covers yesterday and today")
    void lookBackOfOneDayCoversYesterdayAndToday() {
        trigger = recordingTrigger();

        new ScheduledRuns(SOURCES, trigger, JUST_AFTER_MIDNIGHT, ISTANBUL, 1).queueDailyRuns();

        await().atMost(WAIT).untilAsserted(() -> assertThat(started).hasSize(4)
                .allMatch(run -> run.endsWith(" 2026-10-12 2026-10-13 system")));
    }

    @Test
    @DisplayName("TDD 5.3 (v1.19): each source takes a place of its own in the queue, so a queue with room for two "
            + "refuses the rest at once")
    void eachSourceTakesAPlaceOfItsOwn() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        trigger = AutomaticRunTrigger.onOneThread((source, from, to, by) -> {
            started.add(source);
            running.countDown();
            hold(finish);
            return completed(source, from, to);
        }, 2, Duration.ofMillis(1), Duration.ofMinutes(30), Clock.systemUTC());

        new ScheduledRuns(SOURCES, trigger, JUST_AFTER_MIDNIGHT, ISTANBUL, 30).queueDailyRuns();

        assertThat(running.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        finish.countDown();
        await().atMost(WAIT).untilAsserted(() -> assertThat(started)
                .containsExactly("PSP_SCHEDULE_ONE", "PSP_SCHEDULE_TWO", "BANK_SCHEDULE_NORTH"));
        trigger.close();
        assertThat(started).doesNotContain("BANK_SCHEDULE_SOUTH");
    }

    @Test
    @DisplayName("a look-back below one day is refused")
    void lookBackBelowOneDayIsRefused() {
        trigger = recordingTrigger();

        assertThatThrownBy(() -> new ScheduledRuns(SOURCES, trigger, JUST_AFTER_MIDNIGHT, ISTANBUL, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AutomaticRunTrigger recordingTrigger() {
        return AutomaticRunTrigger.onOneThread((source, from, to, by) -> {
            started.add(source + " " + from + " " + to + " " + by);
            return completed(source, from, to);
        }, 10, Duration.ofMillis(1), Duration.ofMinutes(30), Clock.systemUTC());
    }

    private static void hold(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static SourceCode code(String value) {
        return SourceCode.of(value);
    }

    private static ReconciliationRun completed(String source, LocalDate from, LocalDate to) {
        Instant now = Instant.parse("2026-10-12T22:30:00Z");
        return ReconciliationRun.start(UUID.randomUUID(), SourceCode.of(source), from, to, Map.of(), now, "system")
                .complete(Map.of(), now);
    }
}
