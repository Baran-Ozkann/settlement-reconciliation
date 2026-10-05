package com.baran.recon.application.run;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.baran.recon.application.port.RunTrigger;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.run.ReconciliationRun;

/**
 * The run that follows an ingestion (FR-MAT-1, TDD 5.3), started on a background thread so the
 * upload does not wait for it. Runs are taken one at a time, in the order their files were
 * ingested, and recorded as triggered by the system.
 *
 * <p>While the file's source has a running run, the run is refused when it tries to record itself
 * RUNNING, by the database's one-running-run index; it then waits and tries again until it starts,
 * or until the source has been busy for the give-up bound. So it never runs alongside another run of
 * its source, one stuck run cannot hold every later file's run, and no run is dropped silently: a run
 * that cannot be started, is given up, or whose work fails, is logged at WARN with the file's id, so
 * an operator can start it by hand.
 *
 * <p>The files waiting for the thread are bounded. When the queue is full, the file's run is refused
 * at once and logged at WARN with the file's id: the upload is answered without waiting for a place.
 * Only ids and reasons are logged, never a line's content.
 */
public final class AutomaticRunTrigger implements RunTrigger, AutoCloseable {

    /** How long closing waits for a run in progress, so its outcome is recorded before the database goes. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(30);

    private static final String BY_HAND = "; start it with POST /api/v1/runs";

    /** The JDK's logger, so the application layer stays on java.* (TDD 5.2); it reaches the application's log. */
    private static final System.Logger LOG = System.getLogger(AutomaticRunTrigger.class.getName());

    private final RunStarter starter;
    private final ExecutorService executor;
    private final BusyWait busyWait;
    private final Clock clock;
    private final Duration busyGiveUpAfter;

    /**
     * @param executor        runs each file's run; {@link #onOneThread} gives the one the application uses
     * @param busyWait        waits between tries while the source is busy, and says whether to try again
     * @param clock           measures how long the source has been busy
     * @param busyGiveUpAfter how long the source may stay busy, from the first refusal, before the run
     *                        is given up
     */
    public AutomaticRunTrigger(RunStarter starter, ExecutorService executor, BusyWait busyWait, Clock clock,
                               Duration busyGiveUpAfter) {
        this.starter = Objects.requireNonNull(starter, "starter");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.busyWait = Objects.requireNonNull(busyWait, "busyWait");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.busyGiveUpAfter = Objects.requireNonNull(busyGiveUpAfter, "busyGiveUpAfter");
        if (busyGiveUpAfter.isNegative() || busyGiveUpAfter.isZero()) {
            throw new IllegalArgumentException("the busy give-up bound must be positive");
        }
    }

    /**
     * One thread and a queue of {@code queueCapacity} files; a file past it is refused, never held. A
     * busy source is tried again every {@code busyRetryInterval}, and given up once it has been busy
     * for {@code busyGiveUpAfter}.
     */
    public static AutomaticRunTrigger onOneThread(RunStarter starter, int queueCapacity, Duration busyRetryInterval,
                                                  Duration busyGiveUpAfter, Clock clock) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), work -> {
                    Thread thread = new Thread(work, "recon-run-trigger");
                    // The context closes the trigger first; a thread left over must not keep the JVM up.
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        return new AutomaticRunTrigger(starter, executor, BusyWait.sleeping(busyRetryInterval), clock, busyGiveUpAfter);
    }

    @Override
    public void fileIngested(IngestedFile file) {
        try {
            executor.execute(new TriggeredRun(file));
        } catch (RejectedExecutionException refused) {
            LOG.log(System.Logger.Level.WARNING, "Run for statement file {0} on source {1} was not queued: the run "
                    + "queue is full or the application is stopping" + BY_HAND, file.fileId(), file.source().value());
        }
    }

    /**
     * Stops taking files. A file still queued, or a run still waiting for its source, is not started
     * and is logged; a run already working is given {@link #CLOSE_WAIT} to finish.
     */
    @Override
    public void close() throws InterruptedException {
        List<Runnable> notStarted = executor.shutdownNow();
        for (Runnable waiting : notStarted) {
            if (waiting instanceof TriggeredRun run) {
                notStarted(run.file(), "the application is stopping");
            }
        }
        if (!executor.awaitTermination(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            LOG.log(System.Logger.Level.WARNING, "A triggered run was still working when the application stopped");
        }
    }

    private static void notStarted(IngestedFile file, String reason) {
        LOG.log(System.Logger.Level.WARNING, "Run for statement file {0} on source {1} was not started: {2}" + BY_HAND,
                file.fileId(), file.source().value(), reason);
    }

    /** The chain of exception classes, outermost first: a message can quote a row or a path. */
    private static String causes(Throwable failure) {
        StringJoiner chain = new StringJoiner(" <- ");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            chain.add(cause.getClass().getName());
        }
        return chain.toString();
    }

    /** Starts a run as {@link RunMatching#run} does, returning once it has finished. */
    @FunctionalInterface
    public interface RunStarter {

        ReconciliationRun start(String source, LocalDate valueDateFrom, LocalDate valueDateTo, String triggeredBy);
    }

    /** What a run does while its source is busy. */
    @FunctionalInterface
    public interface BusyWait {

        /** @return whether to try again; false when the wait was interrupted, the application stopping */
        boolean waitBeforeRetry();

        static BusyWait sleeping(Duration interval) {
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("the busy retry interval must be positive");
            }
            return () -> {
                try {
                    Thread.sleep(interval);
                    return true;
                } catch (InterruptedException stopping) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            };
        }
    }

    private final class TriggeredRun implements Runnable {

        private final IngestedFile file;

        TriggeredRun(IngestedFile file) {
            this.file = file;
        }

        IngestedFile file() {
            return file;
        }

        @Override
        public void run() {
            Instant busySince = null;
            while (true) {
                try {
                    ReconciliationRun run = starter.start(file.source().value(), file.valueDateFrom(),
                            file.valueDateTo(), Actor.SYSTEM.name());
                    LOG.log(System.Logger.Level.INFO, "Run {0} for statement file {1} on source {2} is {3}", run.id(),
                            file.fileId(), file.source().value(), run.status());
                    return;
                } catch (RunRefusedException refused) {
                    if (refused.reason() != RunRefusedException.Reason.SOURCE_BUSY) {
                        notStarted(file, "refused as " + refused.reason());
                        return;
                    }
                    if (busySince == null) {
                        busySince = clock.instant();
                        LOG.log(System.Logger.Level.INFO, "Run for statement file {0} waits: source {1} has a running "
                                + "run {2}", file.fileId(), file.source().value(),
                                refused.runningRunId().map(Object::toString).orElse("that has just finished"));
                    } else if (!clock.instant().isBefore(busySince.plus(busyGiveUpAfter))) {
                        notStarted(file, "its source was still busy after " + busyGiveUpAfter);
                        return;
                    }
                    if (!busyWait.waitBeforeRetry()) {
                        notStarted(file, "its source was busy");
                        return;
                    }
                } catch (RuntimeException failure) {
                    LOG.log(System.Logger.Level.WARNING, "Run for statement file {0} on source {1} failed, caused by {2}"
                            + BY_HAND, file.fileId(), file.source().value(), causes(failure));
                    return;
                }
            }
        }
    }
}
