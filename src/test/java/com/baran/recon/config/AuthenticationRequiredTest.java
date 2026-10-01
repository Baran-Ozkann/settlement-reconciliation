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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.support.ReconPostgres;
import com.baran.recon.support.ReconUsers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-SEC-1 over real HTTP, on both connectors: without credentials, or with a wrong password, every
 * request but health's is answered 401 with a Basic challenge, whatever its path and method, and
 * whether or not anything is mapped there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("NFR-SEC-1: every endpoint but health requires an authenticated user")
class AuthenticationRequiredTest {

    private static HttpClient http;

    @LocalServerPort
    private int serverPort;

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

    @ParameterizedTest(name = "GET {0} without credentials is 401")
    @ValueSource(strings = {"/api/v1/statements/00000000-0000-4000-8000-000000000001", "/api/v1/runs",
            "/api/v1/anything", "/", "/error"})
    void apiWithoutCredentialsIs401(String path) throws Exception {
        assertChallenged(send(api(path).GET(), Optional.empty()));
    }

    @Test
    @DisplayName("POST /api/v1/statements without credentials is 401")
    void uploadWithoutCredentialsIs401() throws Exception {
        assertChallenged(send(api("/api/v1/statements").POST(HttpRequest.BodyPublishers.ofString("x")), Optional.empty()));
    }

    @ParameterizedTest(name = "{0} without credentials is 401")
    @ValueSource(strings = {"info", "prometheus", "env"})
    void actuatorWithoutCredentialsIs401(String endpoint) throws Exception {
        assertChallenged(send(actuator(endpoint).GET(), Optional.empty()));
    }

    @Test
    @DisplayName("a wrong password is 401, on the API and on the management connector")
    void wrongPasswordIs401() throws Exception {
        Optional<String> wrong = Optional.of(ReconUsers.wrongPasswordAuthorization());

        assertChallenged(send(api("/api/v1/runs").GET(), wrong));
        assertChallenged(send(actuator("prometheus").GET(), wrong));
    }

    @Test
    @DisplayName("a request that authenticates is not challenged, so the 401s above are the credentials' doing")
    void rightPasswordIsNot401() throws Exception {
        HttpResponse<String> response = send(actuator("prometheus").GET(), Optional.of(ReconUsers.operatorAuthorization()));

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("health needs no credentials")
    void healthIsPublic() throws Exception {
        assertThat(send(actuator("health").GET(), Optional.empty()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("no session is created, so each request has to carry its credentials")
    void noSessionCookie() throws Exception {
        HttpResponse<String> response = send(actuator("info").GET(), Optional.of(ReconUsers.operatorAuthorization()));

        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    private static void assertChallenged(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                challenge -> assertThat(challenge).startsWith("Basic "));
    }

    private HttpResponse<String> send(HttpRequest.Builder request, Optional<String> authorization)
            throws IOException, InterruptedException {
        authorization.ifPresent(value -> request.header("Authorization", value));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder api(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + path));
    }

    private HttpRequest.Builder actuator(String endpoint) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/" + endpoint));
    }
}
