package com.baran.recon.adapters.in.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.run.AutomaticRunTrigger;
import com.baran.recon.application.run.HeldLedgerEntryStore;
import com.baran.recon.application.run.RunMatching;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.Multipart;
import com.baran.recon.support.ReconPostgres;
import com.baran.recon.support.ReconUsers;

import static com.baran.recon.application.statement.StatementFiles.psp;
import static com.baran.recon.application.statement.StatementFiles.pspLine;
import static com.baran.recon.application.statement.StatementFiles.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The break proof for the trigger's bounded queue (FR-MAT-1, TDD 9.1), kept as a test so it runs on
 * every build. RunAfterUploadTest sees an upload answered 201 at once while the thread is busy and
 * the queue full, its run refused with a WARN. Here the context's trigger is the same class on an
 * executor that, with its queue full, runs the work on the submitting thread instead of refusing it,
 * a common way never to drop a task. No file is edited. The upload's request thread then runs the
 * run itself, waits with it for the busy source, and the upload is not answered until that run has
 * run: refusing is what keeps a full queue from holding the upload.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.matching.automatic-trigger.enabled=true",
        "recon.sources[0].code=PSP_TRIGGER_CALLER_RUNS",
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@Import({HeldLedgerEntryStore.Injection.class, BlockingTriggerQueueBreakProofTest.CallerRuns.class})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Break proof: with a full queue that hands the run to the caller, the upload waits for the run")
class BlockingTriggerQueueBreakProofTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final SourceCode SOURCE = SourceCode.of("PSP_TRIGGER_CALLER_RUNS");
    private static final Pattern WAITING = Pattern.compile("waits: source " + SOURCE.value() + " has a running run");

    @LocalServerPort
    private int port;

    @Autowired
    private RunMatching matching;

    @Autowired
    private LedgerEntryStore ledgerEntries;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Test
    @DisplayName("the third upload's request thread runs its run and waits for the busy source; it is answered only "
            + "after the source is free")
    void withACallerRunsQueueTheUploadWaits(CapturedOutput output) throws Exception {
        HeldLedgerEntryStore held = HeldLedgerEntryStore.of(ledgerEntries);
        ExecutorService thread = Executors.newSingleThreadExecutor();
        HttpClient http = HttpClient.newHttpClient();
        held.hold(SOURCE);
        try (StatementApi api = new StatementApi(port)) {
            Future<ReconciliationRun> manual = thread.submit(() -> matching.run(SOURCE.value(),
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), ReconUsers.OPERATOR));
            assertThat(held.awaitEntered(1, WAIT)).isTrue();
            String id = unique();
            assertThat(api.upload(SOURCE.value(), "STMT-" + id + "-A", psp(pspLine(id + "-1", "2026-09-21"))).status())
                    .isEqualTo(201);
            await().atMost(WAIT).untilAsserted(() -> assertThat(waits(output)).as("the thread holds the first run")
                    .isEqualTo(1));
            assertThat(api.upload(SOURCE.value(), "STMT-" + id + "-B", psp(pspLine(id + "-2", "2026-09-22"))).status())
                    .as("the second fills the queue").isEqualTo(201);

            CompletableFuture<HttpResponse<String>> third = http.sendAsync(
                    upload(id + "-C", pspLine(id + "-3", "2026-09-23")), HttpResponse.BodyHandlers.ofString());

            await().atMost(WAIT).untilAsserted(() -> assertThat(waits(output))
                    .as("the third upload's request thread waits with its run").isEqualTo(2));
            assertThat(third).as("the upload is not answered while its run waits").isNotDone();
            held.release(SOURCE);
            assertThat(manual.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(third.get(WAIT.toSeconds(), TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        } finally {
            held.release(SOURCE);
            thread.shutdownNow();
            http.close();
        }
    }

    private static long waits(CapturedOutput output) {
        Matcher matcher = WAITING.matcher(output.getAll());
        long found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    private HttpRequest upload(String reference, String line) {
        Multipart body = new Multipart()
                .field("source", SOURCE.value())
                .field("statementReference", "STMT-" + reference)
                .file("file", StatementApi.FILENAME, psp(line));
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/statements"))
                .header("Content-Type", body.contentType())
                .header("Authorization", ReconUsers.operatorAuthorization())
                .POST(body.publisher())
                .build();
    }

    /** The application's trigger on one thread and a queue of one, whose overflow runs on the caller. */
    @TestConfiguration(proxyBeanMethods = false)
    static class CallerRuns {

        @Bean
        @Primary
        AutomaticRunTrigger callerRunsRunTrigger(RunMatching matching) {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.CallerRunsPolicy());
            return new AutomaticRunTrigger(matching::run, executor,
                    AutomaticRunTrigger.BusyWait.sleeping(Duration.ofMillis(50)), Clock.systemUTC(),
                    Duration.ofMinutes(30));
        }
    }
}
