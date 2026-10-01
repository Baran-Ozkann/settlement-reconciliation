package com.baran.recon.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF protection for a stateless API authenticated with HTTP Basic (TDD 11.1). A browser caches
 * Basic credentials and sends them with a cross-site form post, and a multipart form post needs no
 * CORS preflight, so a page on another site could upload a file as the operator. Spring's CSRF
 * token lives in an HTTP session, which this API never creates, so this check replaces it.
 *
 * <p>A request that can change state (anything but GET, HEAD, OPTIONS, TRACE) is refused with 403
 * when a browser marks it as not from this server:
 * <ul>
 *   <li>{@code Sec-Fetch-Site} is anything but {@code same-origin} or {@code none} (the user typed or
 *       bookmarked it);</li>
 *   <li>{@code Origin} is {@code null}, which sandboxed frames and {@code file://} pages send, and which
 *       is therefore refused rather than treated as absent;</li>
 *   <li>{@code Origin} is not this server's own origin, taken from configuration and the bound port
 *       ({@link ServerOrigin}), never from the request's {@code Host};</li>
 *   <li>either header appears more than once.</li>
 * </ul>
 * A client that sends neither header, such as PowerShell or curl, is not a browser a page can
 * drive, and passes on to authentication as before.
 */
final class CrossSiteRequestFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(CrossSiteRequestFilter.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> TRUSTED_FETCH_SITES = Set.of("same-origin", "none");
    private static final String REFUSAL = """
            {"type":"about:blank","title":"Forbidden","status":403,\
            "detail":"A state-changing request from another site is refused."}""";

    private final Supplier<Optional<String>> serverOrigin;

    CrossSiteRequestFilter(Supplier<Optional<String>> serverOrigin) {
        this.serverOrigin = serverOrigin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> refusal = SAFE_METHODS.contains(request.getMethod()) ? Optional.empty() : refusal(request);
        if (refusal.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        // The reason only: header values are the sender's text, and a log line is no place for it.
        LOG.warn("Refused a cross-site {} request: {}", request.getMethod(), refusal.get());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(REFUSAL.getBytes(StandardCharsets.UTF_8));
    }

    /** Why the request is refused, or empty when it is not. */
    Optional<String> refusal(HttpServletRequest request) {
        List<String> fetchSites = Collections.list(request.getHeaders("Sec-Fetch-Site"));
        if (fetchSites.size() > 1) {
            return Optional.of("Sec-Fetch-Site sent more than once");
        }
        if (fetchSites.size() == 1 && !TRUSTED_FETCH_SITES.contains(fetchSites.getFirst().trim().toLowerCase(Locale.ROOT))) {
            return Optional.of("Sec-Fetch-Site says another site");
        }
        List<String> origins = Collections.list(request.getHeaders("Origin"));
        if (origins.size() > 1) {
            return Optional.of("Origin sent more than once");
        }
        if (origins.isEmpty()) {
            return Optional.empty();
        }
        String origin = origins.getFirst();
        if ("null".equals(origin)) {
            return Optional.of("Origin is null");
        }
        return serverOrigin.get().filter(origin::equals).isPresent()
                ? Optional.empty()
                : Optional.of("Origin is not this server's");
    }
}
