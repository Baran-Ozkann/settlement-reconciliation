package com.baran.recon.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * The v1 security baseline (TDD 11.1): HTTP Basic over loopback, one user per role, each user's name
 * and bcrypt hash from the environment and none in the repository. OPERATOR includes VIEWER.
 *
 * <p>Every request needs an authenticated user except the health endpoint. Uploading a statement
 * file and starting a run need an OPERATOR; reading a statement file or a run needs a VIEWER, which
 * an OPERATOR also is (TDD 11). A request without valid credentials is 401 with a Basic challenge,
 * one whose user lacks the role is 403, each as Problem Details like every other error (FR-API-2).
 * Sessions are never created, so each request carries its credentials. Spring's session CSRF token
 * has no session to live in; {@link CrossSiteRequestFilter} takes its place, and is not a Spring
 * bean so the servlet container does not register it a second time outside the chain. A production
 * deployment would use an OAuth2 resource server instead.
 *
 * <p>Only a servlet application has requests to secure. A test context without a web server has no
 * {@link HttpSecurity} to build a chain from, and no user who could log in.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SecurityConfiguration.UsersProperties.class)
class SecurityConfiguration {

    static final String OPERATOR = "OPERATOR";
    static final String VIEWER = "VIEWER";

    private static final String REALM = "settlement-reconciliation";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}");

    private static final AuthenticationEntryPoint CHALLENGE = (request, response, failure) -> {
        response.setHeader("WWW-Authenticate", "Basic realm=\"" + REALM + "\"");
        writeProblem(response, HttpStatus.UNAUTHORIZED, "Authentication is required.");
    };

    private static final AccessDeniedHandler DENIED = (request, response, denied) ->
            writeProblem(response, HttpStatus.FORBIDDEN, "This user's role does not allow the request.");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ServerOrigin serverOrigin) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        // The error page renders a failure the request already met; it opens nothing.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/statements").hasRole(OPERATOR)
                        .requestMatchers(HttpMethod.GET, "/api/v1/statements/*").hasRole(VIEWER)
                        .requestMatchers(HttpMethod.POST, "/api/v1/runs").hasRole(OPERATOR)
                        .requestMatchers(HttpMethod.GET, "/api/v1/runs/*").hasRole(VIEWER)
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.realmName(REALM).authenticationEntryPoint(CHALLENGE))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(CHALLENGE).accessDeniedHandler(DENIED))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .logout(logout -> logout.disable())
                .csrf(csrf -> csrf.disable())
                .addFilterAt(new CrossSiteRequestFilter(serverOrigin::value), CsrfFilter.class)
                .build();
    }

    /** The details are fixed text, so the body needs no JSON encoder to be safe. */
    private static void writeProblem(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String body = "{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":"
                + status.value() + ",\"detail\":\"" + detail + "\"}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    ServerOrigin serverOrigin(@Value("${server.address}") String address) {
        return new ServerOrigin(address);
    }

    @Bean
    RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix().role(OPERATOR).implies(VIEWER).build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(UsersProperties properties) {
        properties.validate();
        return new InMemoryUserDetailsManager(
                User.withUsername(properties.operator().username()).password(properties.operator().passwordHash())
                        .roles(OPERATOR).build(),
                User.withUsername(properties.viewer().username()).password(properties.viewer().passwordHash())
                        .roles(VIEWER).build());
    }

    /**
     * {@code recon.security}: the two users, bound from environment variables by application.yml.
     * A missing or malformed value stops startup, and no message ever repeats a value it read.
     */
    @ConfigurationProperties("recon.security")
    record UsersProperties(Credentials operator, Credentials viewer) {

        void validate() {
            check("operator", operator);
            check("viewer", viewer);
            if (operator.username().equals(viewer.username())) {
                throw new IllegalStateException("recon.security.operator and recon.security.viewer must be different users");
            }
        }

        private static void check(String role, Credentials credentials) {
            if (credentials == null) {
                throw new IllegalStateException("recon.security." + role + " is not configured");
            }
            checkSet(role + ".username", credentials.username());
            checkSet(role + ".password-hash", credentials.passwordHash());
            String username = credentials.username();
            // The name is recorded as the actor of what the user does, so it cannot be the system's.
            if (!USERNAME.matcher(username).matches() || "system".equals(username)) {
                throw new IllegalStateException("recon.security." + role
                        + ".username must be 1-100 characters of [A-Za-z0-9._-], and not \"system\"");
            }
            if (!BCRYPT.matcher(credentials.passwordHash()).matches()) {
                throw new IllegalStateException("recon.security." + role + ".password-hash must be a bcrypt hash");
            }
        }

        /** The binder leaves a placeholder whose variable is unset as its own text, so that is unset too. */
        private static void checkSet(String key, String value) {
            if (value == null || value.isBlank() || value.startsWith("${")) {
                throw new IllegalStateException("recon.security." + key + " is not set");
            }
        }
    }

    record Credentials(String username, String passwordHash) {

        /**
         * A bcrypt hash is full of {@code $}, so .env holds it in single quotes: docker compose reads
         * that file too, and would take each {@code $} as the start of a variable. The local profile
         * reads .env as a properties file, which keeps the quotes, so one enclosing pair is dropped.
         */
        Credentials {
            if (passwordHash != null && passwordHash.length() >= 2 && passwordHash.startsWith("'")
                    && passwordHash.endsWith("'")) {
                passwordHash = passwordHash.substring(1, passwordHash.length() - 1);
            }
        }

        /** Never the hash, so a failure that prints the properties cannot print it. */
        @Override
        public String toString() {
            return "Credentials[username=" + username + "]";
        }
    }
}
