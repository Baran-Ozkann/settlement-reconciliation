package com.baran.recon.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asks the running management connector over real HTTP which endpoints answer (CLAUDE.md 3.2):
 * health, info and prometheus do, and every other endpoint Spring Boot ships is absent. A 404 is
 * the only acceptable answer for those - not a 401, which would mean the endpoint exists and is
 * one misconfigured rule away from being readable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Actuator exposes health, info and prometheus over HTTP and nothing else")
class ActuatorExposureTest {

    private static HttpClient http;

    @LocalManagementPort
    private int managementPort;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @BeforeAll
    static void createClient() {
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void closeClient() {
        http.close();
    }

    @Test
    @DisplayName("health is UP, with the database reached as the application role")
    void healthIsUp() throws Exception {
        HttpResponse<String> response = get("health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @ParameterizedTest(name = "{0} is exposed")
    @ValueSource(strings = {"info", "prometheus"})
    void exposedEndpointAnswers(String endpoint) throws Exception {
        assertThat(get(endpoint).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0} is not exposed")
    @ValueSource(strings = {"env", "configprops", "beans", "heapdump", "threaddump", "loggers",
            "mappings", "metrics", "conditions", "scheduledtasks", "flyway", "shutdown", "caches",
            "startup", "sbom", "quartz", "sessions", "logfile", "auditevents", "httpexchanges"})
    void otherEndpointIsAbsent(String endpoint) throws Exception {
        assertThat(get(endpoint).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String endpoint) throws IOException, InterruptedException {
        URI uri = URI.create("http://127.0.0.1:" + managementPort + "/actuator/" + endpoint);
        return http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
