package com.baran.recon.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.support.ReconPostgres;
import com.baran.recon.support.ReconUsers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD 11: starting a run needs an OPERATOR and reading one a VIEWER, which an OPERATOR also is.
 * Only the role rules are tested here, so a request that passes them is anything but 401 or 403;
 * what the endpoints answer is tested with them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("TDD 11: POST /api/v1/runs needs an OPERATOR, GET /api/v1/runs/{id} a VIEWER")
class RunRolesTest {

    private static final String RUN = "/api/v1/runs/00000000-0000-4000-8000-000000000001";
    private static final String BODY = "{\"source\":\"PSP_ROLES\",\"valueDateFrom\":\"2026-09-24\",\"valueDateTo\":\"2026-09-24\"}";

    private static HttpClient http;

    @LocalServerPort
    private int port;

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
    @DisplayName("a VIEWER starting a run is 403 as Problem Details")
    void viewerStartingARunIs403() throws Exception {
        HttpResponse<String> response = send(start(), Optional.of(ReconUsers.viewerAuthorization()));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
    }

    @Test
    @DisplayName("an OPERATOR starting a run passes the role rule, so the 403 above is the role's doing")
    void operatorPassesTheRoleRule() throws Exception {
        assertThat(send(start(), Optional.of(ReconUsers.operatorAuthorization())).statusCode()).isNotIn(401, 403);
    }

    @Test
    @DisplayName("NFR-SEC-1: starting a run without credentials is 401")
    void anonymousStartIs401() throws Exception {
        assertThat(send(start(), Optional.empty()).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a VIEWER and an OPERATOR pass the rule for reading a run; without credentials it is 401")
    void readingARunNeedsAViewer() throws Exception {
        assertThat(send(read(), Optional.of(ReconUsers.viewerAuthorization())).statusCode()).isNotIn(401, 403);
        assertThat(send(read(), Optional.of(ReconUsers.operatorAuthorization())).statusCode()).isNotIn(401, 403);
        assertThat(send(read(), Optional.empty()).statusCode()).isEqualTo(401);
    }

    private HttpRequest.Builder start() {
        return HttpRequest.newBuilder(uri("/api/v1/runs")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BODY));
    }

    private HttpRequest.Builder read() {
        return HttpRequest.newBuilder(uri(RUN)).GET();
    }

    private HttpResponse<String> send(HttpRequest.Builder request, Optional<String> authorization)
            throws IOException, InterruptedException {
        authorization.ifPresent(value -> request.header("Authorization", value));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
