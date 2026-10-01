package com.baran.recon.support;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * The application's two users for tests, with passwords generated per test JVM, so no credential
 * for them exists in the repository or outside this JVM. Every Spring test context gets them as the
 * environment variables application.yml reads ({@link Factory}, registered in
 * {@code META-INF/spring.factories}), ahead of the real environment, so a variable set on the
 * developer's machine cannot change what a test logs in with.
 *
 * <p>The hashes use bcrypt's lowest cost, 4, only to keep test startup quick; the configuration
 * accepts any valid cost.
 */
public final class ReconUsers {

    public static final String OPERATOR = "operator-001";
    public static final String VIEWER = "viewer-001";

    private static final String OPERATOR_PASSWORD = randomPassword();
    private static final String VIEWER_PASSWORD = randomPassword();
    private static final Map<String, Object> ENVIRONMENT = environment();

    private ReconUsers() {
    }

    public static String operatorAuthorization() {
        return basic(OPERATOR, OPERATOR_PASSWORD);
    }

    public static String viewerAuthorization() {
        return basic(VIEWER, VIEWER_PASSWORD);
    }

    /** The operator's name with a password that is not theirs. */
    public static String wrongPasswordAuthorization() {
        return basic(OPERATOR, OPERATOR_PASSWORD + "x");
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> environment() {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
        return Map.of(
                "RECON_OPERATOR_USERNAME", OPERATOR,
                "RECON_OPERATOR_PASSWORD_HASH", bcrypt.encode(OPERATOR_PASSWORD),
                "RECON_VIEWER_USERNAME", VIEWER,
                "RECON_VIEWER_PASSWORD_HASH", bcrypt.encode(VIEWER_PASSWORD));
    }

    private static String randomPassword() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Adds the users to every test context. */
    public static final class Factory implements ContextCustomizerFactory {

        @Override
        public ContextCustomizer createContextCustomizer(Class<?> testClass,
                                                         List<ContextConfigurationAttributes> configAttributes) {
            return new Customizer();
        }
    }

    /** Equal to every other, so contexts that differ in nothing else are still shared. */
    private static final class Customizer implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration merged) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("reconTestUsers", ENVIRONMENT));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Customizer;
        }

        @Override
        public int hashCode() {
            return Customizer.class.hashCode();
        }
    }
}
