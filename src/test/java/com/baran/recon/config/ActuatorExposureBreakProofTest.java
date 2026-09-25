package com.baran.recon.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The break proof for {@code ActuatorExposureTest}, kept as a test so it runs on every build
 * (TDD 9.1). With env added to the exposure list - in this context's properties only, no file is
 * edited - /actuator/env answers 200. So the 404 that test expects comes from the exposure list
 * and would turn into a failure the moment the list grew, rather than from an endpoint that is
 * missing for some other reason.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoints.web.exposure.include=health,info,prometheus,env")
@ActiveProfiles("test")
@DisplayName("Break proof: an endpoint added to the exposure list answers")
class ActuatorExposureBreakProofTest {

    @LocalManagementPort
    private int managementPort;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Test
    @DisplayName("env answers 200 once it is exposed")
    void exposedEnvAnswers() throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + managementPort + "/actuator/env");
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<Void> response =
                    http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding());

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }
}
