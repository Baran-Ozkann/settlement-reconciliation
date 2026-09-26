package com.baran.recon.config;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.source.InvalidSourceConfigurationException;
import com.baran.recon.domain.source.LedgerAccountSources;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FR-LED-2: recon.sources binds to the account-to-source mapping")
class SourcesConfigurationTest {

    private static final String CLEARING = "00000000-0000-4000-8000-000000000001";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SourcesConfiguration.class);

    @Test
    @DisplayName("the TDD 8.1 layout binds, including keys this phase does not read yet")
    void bindsTheDocumentedLayout() {
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[0].ledger-accounts[0]=" + CLEARING,
                        "recon.sources[0].value-date-window-days=2",
                        "recon.sources[1].code=BANK_MAIN",
                        "recon.sources[1].type=BANK_STATEMENT")
                .run(started -> {
                    LedgerAccountSources sources = started.getBean(LedgerAccountSources.class);
                    assertThat(sources.sourceOf(UUID.fromString(CLEARING))).contains(SourceCode.of("PSP_ALPHA"));
                });
    }

    @Test
    @DisplayName("no recon.sources at all maps no account")
    void noSourcesMapsNothing() {
        context.run(started -> assertThat(started.getBean(LedgerAccountSources.class)
                .sourceOf(UUID.fromString(CLEARING))).isEmpty());
    }

    @Test
    @DisplayName("a contradictory configuration stops the application at startup")
    void contradictoryConfigurationFailsStartup() {
        context.withPropertyValues(
                        "recon.sources[0].code=PSP_ALPHA",
                        "recon.sources[0].type=PSP_SETTLEMENT",
                        "recon.sources[0].ledger-accounts[0]=" + CLEARING,
                        "recon.sources[1].code=PSP_BETA",
                        "recon.sources[1].type=PSP_SETTLEMENT",
                        "recon.sources[1].ledger-accounts[0]=" + CLEARING)
                .run(started -> assertThat(started).hasFailed()
                        .getFailure().rootCause().isInstanceOf(InvalidSourceConfigurationException.class));
    }
}
