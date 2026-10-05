package com.baran.recon.application.run;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baran.recon.application.port.RunTrigger.IngestedFile;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The trigger alone (FR-MAT-1), with a stand-in for the run use case: what it starts, on which
 * thread, how it treats a busy source and one that stays busy, a refusal and a failure, and what a
 * full queue does. The database's part, refusing a second RUNNING run, is tested with the
 * application in RunAfterUploadTest.
 */
@DisplayName("FR-MAT-1: the automatic trigger runs each ingested file's run in the background, one at a time")
class AutomaticRunTriggerTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final SourceCode SOURCE = SourceCode.of("PSP_TRIGGER_UNIT");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 22);
    private static final LocalDate TO = LocalDate.of(2026, 9, 24);
    /** The application's bound; a test that does not reach it runs on the system clock. */
    private static final Duration GIVE_UP = Duration.ofMinutes(30);
    private static final Duration RETRY = Duration.ofSeconds(5);

    private final ConcurrentLinkedQueue<String> started = new ConcurrentLinkedQueue<>();
    private AutomaticRunTrigger trigger;

    @AfterEach
    void closeTrigger() throws InterruptedException {
        if (trigger != null) {
            trigger.close();
        }
    }

    @Test
    @DisplayName("the run gets the file's source and value-date range, is triggered by the system, and the call does "
            + "not wait for it")
    void runsTheFilesRunInTheBackground() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        trigger = AutomaticRunTrigger.onOneThread((source, from, to, by) -> {
            started.add(source + " " + from + " " + to + " " + by);
            running.countDown();
            hold(finish);
            return completed(source, from, to);
        }, 10, Duration.ofMillis(10), GIVE_UP, Clock.systemUTC());

        trigger.fileIngested(file(FROM, TO));

        assertThat(running.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("the run started").isTrue();
        assertThat(finish.getCount()).as("the call returned while the run could not finish").isOne();
        assertThat(started).containsExactly("PSP_TRIGGER_UNIT 2026-09-22 2026-09-24 system");
        finish.countDown();
    }

    @Test
    @DisplayName("TDD 5.3: while the source is busy the run waits and tries again, then starts once it is free")
    void busySourceIsTriedAgain() {
        AtomicInteger refusals = new AtomicInteger(2);
        AtomicInteger waits = new AtomicInteger();
        trigger = new AutomaticRunTrigger((source, from, to, by) -> {
            started.add(source);
            if (refusals.getAndDecrement() > 0) {
                throw RunRefusedException.sourceBusy(Optional.of(UUID.randomUUID()), null);
            }
            return completed(source, from, to);
        }, oneThread(),
                () -> waits.incrementAndGet() > 0, Clock.systemUTC(), GIVE_UP);

        trigger.fileIngested(file(FROM, TO));

        await().atMost(WAIT).untilAsserted(() -> assertThat(started).hasSize(3));
        assertThat(waits).hasValue(2);
    }

    @Test
    @DisplayName("a wait that is stopped gives up that run, and the next file's run still runs")
    void stoppedWaitGivesUp() {
        AtomicInteger calls = new AtomicInteger();
        trigger = new AutomaticRunTrigger((source, from, to, by) -> {
            started.add(from.toString());
            if (calls.getAndIncrement() == 0) {
                throw RunRefusedException.sourceBusy(Optional.empty(), null);
            }
            return completed(source, from, to);
        }, oneThread(),
                () -> false, Clock.systemUTC(), GIVE_UP);

        trigger.fileIngested(file(FROM, FROM));
        trigger.fileIngested(file(TO, TO));

        await().atMost(WAIT).untilAsserted(() -> assertThat(started).containsExactly("2026-09-22", "2026-09-24"));
    }

    @Test
    @DisplayName("TDD 14 Phase 5: a source still busy after busy-give-up-after gives the run up, and the next file's run "
            + "runs")
    void sourceBusyPastTheBoundIsGivenUp() {
        ManualClock clock = new ManualClock();
        AtomicInteger waits = new AtomicInteger();
        trigger = new AutomaticRunTrigger((source, from, to, by) -> {
            started.add(from.toString());
            if (from.equals(FROM)) {
                throw RunRefusedException.sourceBusy(Optional.of(UUID.randomUUID()), null);
            }
            return completed(source, from, to);
        }, oneThread(), () -> {
            waits.incrementAndGet();
            clock.advance(RETRY);
            return true;
        }, clock, GIVE_UP);

        trigger.fileIngested(file(FROM, FROM));
        trigger.fileIngested(file(TO, TO));

        await().atMost(WAIT).untilAsserted(() -> assertThat(started).last().isEqualTo("2026-09-24"));
        // Refused at 0 s, 5 s, ... 1,800 s: the try that finds the source busy at the bound is the last.
        assertThat(started).filteredOn(FROM.toString()::equals).hasSize(361);
        assertThat(waits).hasValue(360);
    }

    @Test
    @DisplayName("TDD 14 Phase 5: a source freed before busy-give-up-after has passed still gets the run, the bound "
            + "counted from the first refusal")
    void sourceFreedBeforeTheBoundIsStarted() {
        ManualClock clock = new ManualClock();
        AtomicInteger refusals = new AtomicInteger(360);
        AtomicReference<ReconciliationRun> ran = new AtomicReference<>();
        trigger = new AutomaticRunTrigger((source, from, to, by) -> {
            started.add(from.toString());
            if (refusals.getAndDecrement() > 0) {
                throw RunRefusedException.sourceBusy(Optional.empty(), null);
            }
            ran.set(completed(source, from, to));
            return ran.get();
        }, oneThread(), () -> {
            clock.advance(RETRY);
            return true;
        }, clock, GIVE_UP);

        trigger.fileIngested(file(FROM, FROM));

        // Refused at 0 s ... 1,795 s, free at 1,800 s: the try at the bound is still made.
        await().atMost(WAIT).untilAsserted(() -> assertThat(ran.get()).isNotNull());
        assertThat(started).hasSize(361);
    }

    @Test
    @DisplayName("a give-up bound that is not positive is refused")
    void boundMustBePositive() {
        assertThatThrownBy(() -> new AutomaticRunTrigger((source, from, to, by) -> completed(source, from, to),
                oneThread(), () -> true, Clock.systemUTC(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a refusal other than a busy source is not tried again")
    void otherRefusalIsNotRetried() {
        AtomicInteger waits = new AtomicInteger();
        trigger = new AutomaticRunTrigger((source, from, to, by) -> {
            started.add(from.toString());
            if (from.equals(FROM)) {
                throw RunRefusedException.unknownSource();
            }
            return completed(source, from, to);
        }, oneThread(), () -> {
            waits.incrementAndGet();
            return true;
        }, Clock.systemUTC(), GIVE_UP);

        trigger.fileIngested(file(FROM, FROM));
        trigger.fileIngested(file(TO, TO));

        await().atMost(WAIT).untilAsserted(() -> assertThat(started).containsExactly("2026-09-22", "2026-09-24"));
        assertThat(waits).hasValue(0);
    }

    @Test
    @DisplayName("a run whose work fails is not tried again, and the thread goes on to the next file")
    void failedRunDoesNotStopTheThread() {
        trigger = AutomaticRunTrigger.onOneThread((source, from, to, by) -> {
            started.add(from.toString());
            if (from.equals(FROM)) {
                throw new IllegalStateException("the run's work failed");
            }
            return completed(source, from, to);
        }, 10, Duration.ofMillis(1), GIVE_UP, Clock.systemUTC());

        trigger.fileIngested(file(FROM, FROM));
        trigger.fileIngested(file(TO, TO));

        await().atMost(WAIT).untilAsserted(() -> assertThat(started).containsExactly("2026-09-22", "2026-09-24"));
    }

    @Test
    @DisplayName("runs are taken one at a time, in the order their files were ingested")
    void oneAtATimeInOrder() {
        AtomicInteger working = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        trigger = AutomaticRunTrigger.onOneThread((source, from, to, by) -> {
            mostAtOnce.accumulateAndGet(working.incrementAndGet(), Math::max);
            started.add(from.toString());
            working.decrementAndGet();
            return completed(source, from, to);
        }, 10, Duration.ofMillis(1), GIVE_UP, Clock.systemUTC());

        List<LocalDate> days = FROM.datesUntil(FROM.plusDays(6)).toList();
        days.forEach(day -> trigger.fileIngested(file(day, day)));

        await().atMost(WAIT).untilAsserted(() -> assertThat(started).hasSize(days.size()));
        assertThat(started).containsExactlyElementsOf(days.stream().map(LocalDate::toString).toList());
        assertThat(mostAtOnce).hasValue(1);
    }

    @Test
    @DisplayName("a full queue refuses the next file at once, without waiting for a place; the queued ones still run")
    void fullQueueRefusesAtOnce() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        trigger = AutomaticRunTrigger.onOneThread((source, from, to, by) -> {
            started.add(from.toString());
            running.countDown();
            hold(finish);
            return completed(source, from, to);
        }, 1, Duration.ofMillis(1), GIVE_UP, Clock.systemUTC());
        trigger.fileIngested(file(FROM, FROM));
        assertThat(running.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("the first run holds the thread").isTrue();
        trigger.fileIngested(file(FROM.plusDays(1), FROM.plusDays(1)));

        trigger.fileIngested(file(TO, TO));

        assertThat(finish.getCount()).as("refused while the thread was still held").isOne();
        finish.countDown();
        await().atMost(WAIT).untilAsserted(() -> assertThat(started).containsExactly("2026-09-22", "2026-09-23"));
        trigger.close();
        assertThat(started).doesNotContain("2026-09-24");
    }

    @Test
    @DisplayName("closing stops a run that waits for its source and drops none silently: queued files are not started")
    void closeStopsWaitingRuns() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        trigger = new AutomaticRunTrigger((source, from, to, by) -> {
            started.add(from.toString());
            waiting.countDown();
            throw RunRefusedException.sourceBusy(Optional.empty(), null);
        }, oneThread(),
                AutomaticRunTrigger.BusyWait.sleeping(Duration.ofMinutes(5)), Clock.systemUTC(), GIVE_UP);
        trigger.fileIngested(file(FROM, FROM));
        trigger.fileIngested(file(TO, TO));
        assertThat(waiting.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        trigger.close();

        assertThat(started).containsExactly("2026-09-22");
    }

    /** A clock the test moves on, here from the busy wait, so a bound of minutes takes no time. */
    private static final class ManualClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-12T09:00:00Z"));

        void advance(Duration by) {
            now.updateAndGet(instant -> instant.plus(by));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("the trigger reads instants only");
        }
    }

    /** One thread, as the application has, with room for every file a test queues. */
    private static ExecutorService oneThread() {
        return Executors.newSingleThreadExecutor();
    }

    private static IngestedFile file(LocalDate from, LocalDate to) {
        return new IngestedFile(UUID.randomUUID(), SOURCE, from, to);
    }

    private static ReconciliationRun completed(String source, LocalDate from, LocalDate to) {
        Instant now = Instant.parse("2026-10-12T09:00:00Z");
        return ReconciliationRun.start(UUID.randomUUID(), SourceCode.of(source), from, to, Map.of(), now, "system")
                .complete(Map.of(), now);
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
}
