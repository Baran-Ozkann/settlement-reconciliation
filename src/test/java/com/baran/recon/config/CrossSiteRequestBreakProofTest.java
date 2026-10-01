package com.baran.recon.config;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The break proof for {@code CrossSiteRequestTest}, kept as a test so it runs on every build
 * (TDD 9.1). In a context of its own, a bean post-processor takes {@link CrossSiteRequestFilter} out
 * of the application's security filter chain; no file is edited. Each request that test sees refused
 * then reaches the application, so the filter is what refuses them, and not some other rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Break proof: without the cross-site filter, each refused request reaches the application")
class CrossSiteRequestBreakProofTest {

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

    @ParameterizedTest(name = "{0} gets through")
    @MethodSource("refused")
    void withoutTheFilterTheRequestGetsThrough(CrossSiteRequests.Case request) throws Exception {
        assertThat(CrossSiteRequests.post(port, request).status()).isEqualTo(404);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WithoutTheFilter {

        @Bean
        static BeanPostProcessor removeCrossSiteRequestFilter() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DefaultSecurityFilterChain chain) {
                        assertThat(chain.getFilters().removeIf(CrossSiteRequestFilter.class::isInstance))
                                .as("the application's chain holds the filter").isTrue();
                    }
                    return bean;
                }
            };
        }
    }
}
