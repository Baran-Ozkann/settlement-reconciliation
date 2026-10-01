package com.baran.recon.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.baran.recon.support.RawHttp;
import com.baran.recon.support.ReconUsers;

/**
 * The requests the cross-site check is about, sent as the operator to a path nothing maps, so a
 * request that gets past the check ends in 404 and one it refuses in 403. Shared by the test of the
 * check and its break proof, so the proof replays exactly the requests the test sees refused.
 */
final class CrossSiteRequests {

    static final String REFUSAL = "A state-changing request from another site is refused.";
    static final String PATH = "/api/v1/cross-site-probe";

    private CrossSiteRequests() {
    }

    /** One per refusal rule, each with the headers that break it and nothing else wrong. */
    static Stream<Case> refused(int port) {
        String host = host(port);
        String own = "http://" + host;
        return Stream.of(
                new Case("Sec-Fetch-Site cross-site", List.of("Sec-Fetch-Site: cross-site"), host),
                new Case("Sec-Fetch-Site same-site", List.of("Sec-Fetch-Site: same-site"), host),
                new Case("Origin null", List.of("Origin: null"), host),
                new Case("a foreign Origin", List.of("Origin: http://attacker.example"), host),
                new Case("this server under another name", List.of("Origin: http://localhost:" + port), host),
                new Case("Origin sent twice", List.of("Origin: " + own, "Origin: " + own), host),
                new Case("Sec-Fetch-Site sent twice", List.of("Sec-Fetch-Site: same-origin", "Sec-Fetch-Site: same-origin"),
                        host),
                // DNS rebinding: the page is served under the attacker's name, and the browser sends that
                // name as both Host and Origin, which agree with each other but not with this server.
                new Case("a Host header that agrees with a foreign Origin",
                        List.of("Origin: http://attacker.example:" + port), "attacker.example:" + port));
    }

    /** This server's address as a client that reached it on its own name sends it in Host. */
    static String host(int port) {
        return "127.0.0.1:" + port;
    }

    static RawHttp.Response post(int port, Case request) throws IOException {
        return send(port, "POST", request);
    }

    static RawHttp.Response send(int port, String method, Case request) throws IOException {
        List<String> headers = new ArrayList<>();
        headers.add("Host: " + request.host());
        headers.add("Authorization: " + ReconUsers.operatorAuthorization());
        headers.add("Content-Type: text/plain");
        headers.addAll(request.headers());
        return RawHttp.send(port, method + " " + PATH + " HTTP/1.1", headers, "x");
    }

    record Case(String name, List<String> headers, String host) {

        @Override
        public String toString() {
            return name;
        }
    }
}
