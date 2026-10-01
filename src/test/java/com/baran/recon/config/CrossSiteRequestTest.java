package com.baran.recon.config;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.support.RawHttp;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD 11.1, CSRF for an API on HTTP Basic: a browser sends cached Basic credentials with a
 * cross-site form post, so a state-changing request a browser marks as coming from another site is
 * refused with 403 before authentication. Every request here carries the operator's credentials;
 * one that passes the check reaches the application and ends in 404, since nothing maps its path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("TDD 11.1: a state-changing request a browser marks as cross-site is refused")
class CrossSiteRequestTest {

    // Per class, so the port is injected before the argument sources below read it.
    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    Stream<CrossSiteRequests.Case> refused() {
        return CrossSiteRequests.refused(port);
    }

    @ParameterizedTest(name = "{0} is refused")
    @MethodSource("refused")
    void refusedWith403(CrossSiteRequests.Case request) throws Exception {
        RawHttp.Response response = CrossSiteRequests.post(port, request);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.head()).containsIgnoringCase("Content-Type: application/problem+json");
        assertThat(response.body()).contains(CrossSiteRequests.REFUSAL);
    }

    static Stream<Arguments> stateChangingMethods() {
        return Stream.of("POST", "PUT", "PATCH", "DELETE").map(Arguments::of);
    }

    @ParameterizedTest(name = "{0} from a foreign Origin is refused")
    @MethodSource("stateChangingMethods")
    void everyStateChangingMethodIsChecked(String method) throws Exception {
        RawHttp.Response response = CrossSiteRequests.send(port, method, foreignOrigin());

        assertThat(response.status()).isEqualTo(403);
    }

    @ParameterizedTest(name = "{0} from a foreign Origin is not refused: it changes nothing")
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void safeMethodsAreNotChecked(String method) throws Exception {
        RawHttp.Response response = CrossSiteRequests.send(port, method, foreignOrigin());

        assertThat(response.status()).isNotEqualTo(403);
    }

    Stream<CrossSiteRequests.Case> allowed() {
        String host = CrossSiteRequests.host(port);
        String own = "http://" + host;
        return Stream.of(
                new CrossSiteRequests.Case("no Origin and no Sec-Fetch-Site, as PowerShell and curl send", List.of(), host),
                new CrossSiteRequests.Case("this server's own Origin", List.of("Origin: " + own), host),
                new CrossSiteRequests.Case("Sec-Fetch-Site same-origin", List.of("Sec-Fetch-Site: same-origin"), host),
                new CrossSiteRequests.Case("Sec-Fetch-Site none, a typed or bookmarked request",
                        List.of("Sec-Fetch-Site: none"), host),
                new CrossSiteRequests.Case("Sec-Fetch-Site same-origin with this server's Origin",
                        List.of("Sec-Fetch-Site: same-origin", "Origin: " + own), host));
    }

    @ParameterizedTest(name = "{0} passes to the application")
    @MethodSource("allowed")
    void sameOriginAndNonBrowserRequestsPass(CrossSiteRequests.Case request) throws Exception {
        assertThat(CrossSiteRequests.post(port, request).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a cross-site request without credentials is refused before authentication, with no Basic challenge")
    void refusedBeforeAuthentication() throws Exception {
        RawHttp.Response response = RawHttp.send(port, "POST " + CrossSiteRequests.PATH + " HTTP/1.1",
                List.of("Host: " + CrossSiteRequests.host(port), "Origin: http://attacker.example"), "x");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.head()).doesNotContainIgnoringCase("WWW-Authenticate");
    }

    private CrossSiteRequests.Case foreignOrigin() {
        return new CrossSiteRequests.Case("a foreign Origin", List.of("Origin: http://attacker.example"),
                CrossSiteRequests.host(port));
    }
}
