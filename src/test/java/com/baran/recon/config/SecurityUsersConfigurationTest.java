package com.baran.recon.config;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD 11.1: the two users come from the environment, and a configuration that cannot give both
 * stops startup - a missing, malformed or shared user never leaves the application running with a
 * login nobody intended. No failure message repeats a value it read.
 */
@DisplayName("TDD 11.1: the users come from the environment, and a missing or malformed one stops startup")
class SecurityUsersConfigurationTest {

    private static final String HASH = new BCryptPasswordEncoder(4).encode("synthetic-password-001");
    private static final String OTHER_HASH = new BCryptPasswordEncoder(4).encode("synthetic-password-002");

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class,
                    ServletWebSecurityAutoConfiguration.class))
            .withUserConfiguration(SecurityConfiguration.class)
            .withPropertyValues("server.address=127.0.0.1");

    @Test
    @DisplayName("two valid users start the application: the operator holds OPERATOR, which implies VIEWER")
    void validUsersStart() {
        runner.withPropertyValues(users("operator-001", HASH, "viewer-001", OTHER_HASH)).run(context -> {
            assertThat(context).hasNotFailed();
            UserDetailsService users = context.getBean(UserDetailsService.class);
            RoleHierarchy hierarchy = context.getBean(RoleHierarchy.class);

            assertThat(reachable(hierarchy, users.loadUserByUsername("operator-001")))
                    .containsExactlyInAnyOrder("ROLE_OPERATOR", "ROLE_VIEWER");
            assertThat(reachable(hierarchy, users.loadUserByUsername("viewer-001"))).containsExactly("ROLE_VIEWER");
        });
    }

    @Test
    @DisplayName("a hash in single quotes, as .env holds it for docker compose's sake, is read without them")
    void singleQuotedHashIsUnquoted() {
        runner.withPropertyValues(users("operator-001", "'" + HASH + "'", "viewer-001", OTHER_HASH)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(UserDetailsService.class).loadUserByUsername("operator-001").getPassword())
                    .isEqualTo(HASH);
        });
    }

    @ParameterizedTest(name = "{0} unset stops startup")
    @ValueSource(strings = {"recon.security.operator.username", "recon.security.operator.password-hash",
            "recon.security.viewer.username", "recon.security.viewer.password-hash"})
    void missingValueStopsStartup(String key) {
        List<String> properties = new ArrayList<>(List.of(users("operator-001", HASH, "viewer-001", OTHER_HASH)));
        properties.removeIf(property -> property.startsWith(key + "="));

        runner.withPropertyValues(properties.toArray(String[]::new))
                .run(context -> assertStartupStopped(context, key + " is not set"));
    }

    @Test
    @DisplayName("a variable application.yml names but the environment does not set stops startup")
    void unresolvedPlaceholderStopsStartup() {
        runner.withPropertyValues(users("${RECON_TEST_UNSET_USERNAME}", HASH, "viewer-001", OTHER_HASH))
                .run(context -> assertStartupStopped(context, "recon.security.operator.username is not set"));
    }

    @ParameterizedTest(name = "username \"{0}\" stops startup")
    @ValueSource(strings = {"operator 001", "operator:001", "operätor",
            "a123456789a123456789a123456789a123456789a123456789a123456789a123456789a123456789a123456789a1234567890"})
    void malformedUsernameStopsStartup(String username) {
        runner.withPropertyValues(users(username, HASH, "viewer-001", OTHER_HASH)).run(context -> {
            assertStartupStopped(context, "recon.security.operator.username must be 1-100 characters");
            assertThat(failureMessages(context)).doesNotContain(username);
        });
    }

    @Test
    @DisplayName("the username \"system\" stops startup: it is the actor of what the application does itself")
    void systemUsernameStopsStartup() {
        runner.withPropertyValues(users("operator-001", HASH, "system", OTHER_HASH)).run(context ->
                assertStartupStopped(context, "recon.security.viewer.username must be 1-100 characters"));
    }

    @ParameterizedTest(name = "password hash \"{0}\" stops startup")
    @ValueSource(strings = {"synthetic-plain-password", "{noop}synthetic-plain-password",
            "$2a$03$abcdefghijklmnopqrstuuJ8Q7sZ3YH8yJ9m9ZpQ9iR7lW1vXq4m2",
            "$2a$10$abcdefghijklmnopqrstuuJ8Q7sZ3YH8yJ9m9ZpQ9iR7lW1vXq4m"})
    void malformedHashStopsStartup(String hash) {
        runner.withPropertyValues(users("operator-001", HASH, "viewer-001", hash)).run(context -> {
            assertStartupStopped(context, "recon.security.viewer.password-hash must be a bcrypt hash");
            assertThat(failureMessages(context)).doesNotContain(hash);
        });
    }

    @Test
    @DisplayName("the same username for both users stops startup: one name cannot hold two roles")
    void sameUsernameStopsStartup() {
        runner.withPropertyValues(users("operator-001", HASH, "operator-001", OTHER_HASH))
                .run(context -> assertStartupStopped(context, "must be different users"));
    }

    @Test
    @DisplayName("a context without a web server has no users and no filter chain to build")
    void nonWebContextLeavesSecurityOut() {
        new ApplicationContextRunner().withUserConfiguration(SecurityConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(UserDetailsService.class));
    }

    private static String[] users(String operator, String operatorHash, String viewer, String viewerHash) {
        return new String[] {
                "recon.security.operator.username=" + operator,
                "recon.security.operator.password-hash=" + operatorHash,
                "recon.security.viewer.username=" + viewer,
                "recon.security.viewer.password-hash=" + viewerHash};
    }

    private static void assertStartupStopped(AssertableWebApplicationContext context, String expected) {
        assertThat(context).hasFailed();
        assertThat(failureMessages(context)).contains(expected).doesNotContain(HASH).doesNotContain(OTHER_HASH);
    }

    /** Every message in the failure's cause chain, which is everything startup would log about it. */
    private static String failureMessages(AssertableWebApplicationContext context) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private static List<String> reachable(RoleHierarchy hierarchy, UserDetails user) {
        return hierarchy.getReachableGrantedAuthorities(user.getAuthorities()).stream()
                .map(GrantedAuthority::getAuthority).toList();
    }
}
