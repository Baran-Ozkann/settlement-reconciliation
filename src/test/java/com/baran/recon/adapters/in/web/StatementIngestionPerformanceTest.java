package com.baran.recon.adapters.in.web;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.StatementParser;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.support.ReconPostgres;
import com.baran.recon.support.ReconUsers;

import static com.baran.recon.application.statement.StatementFiles.PSP_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-PERF-1 and FR-ING-5: a 1,000,000-line PSP file is ingested through the real endpoint by a JVM
 * limited to 512 MB of heap, measured rather than estimated. Runs only under -Pperf, which sets
 * -Xmx512m for the test JVM; the test refuses to measure without it.
 *
 * <p>The file is generated into target/perf, never committed, and sent from disk, so the client
 * holds no more of it than the server does. The clock covers the whole request: the upload over
 * loopback, the container spooling it, hashing, parsing, a million inserts and the commit. The
 * application, its database client, the HTTP client and the test all share the one capped heap.
 *
 * <p>The store and the parsers are timed through a proxy, so the result also says where the time
 * went: receiving and hashing the upload (everything before parsing starts), parsing with the line
 * inserts it drives, the inserts alone, and the commit (everything after the file row is written).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.sources[0].code=PSP_PERF",
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@Tag("perf")
@DisplayName("NFR-PERF-1: ingesting a 1,000,000-line PSP file under -Xmx512m")
class StatementIngestionPerformanceTest {

    /**
     * NFR-PERF-1, revised in TDD v1.7 (4.6) from 60 s, which was set before anything was measured.
     * A miss fails this test; the numbers are printed first either way.
     */
    private static final Duration TARGET = Duration.ofSeconds(120);
    private static final long MAX_HEAP = 512L * 1024 * 1024;
    private static final int LINES = 1_000_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Nanoseconds spent in each timed method, and when each last returned. */
    private static final Map<String, AtomicLong> SPENT = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> LAST_RETURNED = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> FIRST_CALLED = new ConcurrentHashMap<>();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Test
    @DisplayName("NFR-PERF-1: a million lines are ingested in 120 s or less, with the heap capped at 512 MB")
    void millionLinesUnderTheHeapCap() throws Exception {
        assertThat(Runtime.getRuntime().maxMemory()).as("run under -Pperf, which sets -Xmx512m").isLessThanOrEqualTo(MAX_HEAP);
        String id = UUID.randomUUID().toString().substring(0, 8);
        Path file = generate(id);
        String boundary = "recon-perf-" + id;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/statements"))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", ReconUsers.operatorAuthorization())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        text("--" + boundary + "\r\nContent-Disposition: form-data; name=\"source\"\r\n\r\nPSP_PERF\r\n"
                                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"statementReference\"\r\n\r\n"
                                + "PERF-" + id + "\r\n"
                                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"perf.csv\"\r\n"
                                + "Content-Type: text/csv\r\n\r\n"),
                        HttpRequest.BodyPublishers.ofFile(file),
                        text("\r\n--" + boundary + "--\r\n")))
                .build();
        resetPeaks();
        SPENT.clear();
        LAST_RETURNED.clear();
        FIRST_CALLED.clear();

        long start = System.nanoTime();
        HttpResponse<String> response;
        try (HttpClient http = HttpClient.newHttpClient()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        long end = System.nanoTime();
        Duration elapsed = Duration.ofNanos(end - start);

        long peakHeap = peakHeapUsed();
        System.out.printf(Locale.ROOT, "NFR-PERF-1 RESULT: %d lines (%d bytes) answered %d in %.3f s (target %d s); "
                        + "max heap %d MB, peak heap used %d MB; %d processors, Java %s%n",
                LINES, Files.size(file), response.statusCode(), elapsed.toNanos() / 1e9, TARGET.toSeconds(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024), peakHeap / (1024 * 1024),
                Runtime.getRuntime().availableProcessors(), Runtime.version());
        System.out.printf(Locale.ROOT, "NFR-PERF-1 BREAKDOWN: receive and hash %.3f s, parse with inserts %.3f s "
                        + "(of which line inserts %.3f s in %d batches), commit and response %.3f s%n",
                seconds(FIRST_CALLED.get("parse").get() - start), seconds(SPENT.get("parse").get()),
                seconds(SPENT.get("storePspLinesIfAbsent").get()), LINES / 1_000,
                seconds(end - LAST_RETURNED.get("storeFile").get()));
        Files.delete(file);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("lineCount").asLong()).isEqualTo(LINES);
        assertThat(body.get("storedLineCount").asLong()).isEqualTo(LINES);
        assertThat(elapsed).as("NFR-PERF-1").isLessThanOrEqualTo(TARGET);
    }

    /** Synthetic lines, each with its own line id and reference; every value is fake. */
    private static Path generate(String id) throws IOException {
        Path directory = Files.createDirectories(Path.of("target", "perf"));
        Path file = directory.resolve("psp-" + LINES + "-" + id + ".csv");
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write(PSP_HEADER);
            out.write('\n');
            for (int n = 1; n <= LINES; n++) {
                out.write(String.format(Locale.ROOT,
                        "%s-%07d,5f0c7a1e-0000-4000-8000-%012d,B-%04d,2026-09-23,2026-09-24,PAYMENT,125.00,2.50,122.50,TRY\n",
                        id, n, n, n % 1000));
            }
        }
        return file;
    }

    private static double seconds(long nanos) {
        return nanos / 1e9;
    }

    private static HttpRequest.BodyPublisher text(String text) {
        return HttpRequest.BodyPublishers.ofByteArray(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void resetPeaks() {
        ManagementFactory.getMemoryPoolMXBeans().forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    /** The sum of each heap pool's peak, an upper bound on the heap used at any one moment. */
    private static long peakHeapUsed() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeTheStages {

        @Bean
        static BeanPostProcessor timeStoreAndParsers() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof StatementStore || bean instanceof StatementParser) {
                        Class<?> port = bean instanceof StatementStore ? StatementStore.class : StatementParser.class;
                        return Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, (proxy, method, args) -> {
                            long called = System.nanoTime();
                            FIRST_CALLED.computeIfAbsent(method.getName(), name -> new AtomicLong(called));
                            try {
                                return method.invoke(bean, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            } finally {
                                long returned = System.nanoTime();
                                SPENT.computeIfAbsent(method.getName(), name -> new AtomicLong()).addAndGet(returned - called);
                                LAST_RETURNED.computeIfAbsent(method.getName(), name -> new AtomicLong()).set(returned);
                            }
                        });
                    }
                    return bean;
                }
            };
        }
    }
}
