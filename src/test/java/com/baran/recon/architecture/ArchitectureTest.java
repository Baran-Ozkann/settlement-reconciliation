package com.baran.recon.architecture;

import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.baran.recon.ReconApplication;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules in {@link ArchitectureRules}, applied to the application's own classes. */
@DisplayName("TDD 5.2, INV-8, INV-9: the application's architecture")
class ArchitectureTest {

    private static final String ROOT = "com.baran.recon";

    private static JavaClasses application;

    @BeforeAll
    static void importApplication() {
        application = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    /**
     * The rules allow an empty selection while the layers are still empty, so the import itself
     * must be shown to have found the application; an importer pointed at the wrong place would
     * otherwise pass every rule by reading nothing.
     */
    @Test
    @DisplayName("the import finds the application's classes and none of the test fixtures")
    void importSeesTheApplication() {
        assertThat(application.contain(ReconApplication.class)).isTrue();
        assertThat(application).noneMatch(javaClass -> javaClass.getPackageName().contains(".archfixture"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void applicationKeepsTheRule(ArchRule rule) {
        rule.check(application);
    }

    static Stream<ArchRule> rules() {
        return ArchitectureRules.forRoot(ROOT).all().stream();
    }
}
