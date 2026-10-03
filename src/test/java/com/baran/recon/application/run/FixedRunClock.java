package com.baran.recon.application.run;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Import into a test context so its runs see a fixed time: grace periods are counted up to the
 * run's day (TDD 8.2), and a test must not depend on the day it is run (CLAUDE.md 7.3).
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedRunClock {

    /** Noon in Istanbul on Monday 2026-10-12. */
    public static final Instant NOW = Instant.parse("2026-10-12T09:00:00Z");
    public static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneId.of("Europe/Istanbul"));

    @Bean
    @Primary
    Clock fixedRunClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
