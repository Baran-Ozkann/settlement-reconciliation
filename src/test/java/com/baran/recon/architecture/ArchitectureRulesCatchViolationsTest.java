package com.baran.recon.architecture;

import java.util.function.Function;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each rule against a fixture tree under {@code com.baran.recon.archfixture} that breaks it on
 * purpose, and every rule against one that breaks none. The application's own packages are nearly
 * empty in Phase 1, so this is the evidence that each rule rejects what it names and accepts what
 * it does not.
 */
@DisplayName("TDD 5.2, INV-8, INV-9: each architecture rule rejects its violation")
class ArchitectureRulesCatchViolationsTest {

    private static final String FIXTURES = "com.baran.recon.archfixture.";

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    void ruleRejectsItsFixture(String fixture, Function<ArchitectureRules, ArchRule> rule, String offender) {
        EvaluationResult result = rule.apply(ArchitectureRules.forRoot(FIXTURES + fixture))
                .evaluate(importFixture(fixture));

        assertThat(result.hasViolation()).as("%s is rejected", fixture).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .as("the report names the offending class")
                .anyMatch(line -> line.contains(offender));
    }

    static Stream<Arguments> violations() {
        return Stream.of(
                violation("domainspring", ArchitectureRules::domainDependsOnlyOnJavaAndItself, "UsesSpring"),
                violation("applicationadapter", ArchitectureRules::applicationDependsOnlyOnDomain, "UseCase"),
                violation("adaptercycle", ArchitectureRules::adaptersDoNotDependOnEachOther, "Endpoint"),
                violation("controllerjdbc", ArchitectureRules::controllersDoNotReachPersistence, "QueryingController"),
                violation("controllerrepository", ArchitectureRules::controllersDoNotReachPersistence, "RepositoryController"),
                violation("doublefield", ArchitectureRules::noFloatingPointInDomainOrApplication, "Price"),
                violation("boxedfloat", ArchitectureRules::noFloatingPointInDomainOrApplication, "Rate"),
                violation("doublecall", ArchitectureRules::noFloatingPointInDomainOrApplication, "Halver"),
                violation("bigdecimal", ArchitectureRules::bigDecimalOnlyInTheFileAdapter, "DecimalStore"),
                violation("postgreskafka", ArchitectureRules::postgresDriverOnlyInPersistence, "DriverAwareListener"),
                violation("postgresconfig", ArchitectureRules::postgresDriverOnlyInPersistence, "DriverDataSource"),
                violation("ledgerproducer", ArchitectureRules::kafkaProducersOnlyInTheKafkaAdapter, "EntryEcho"),
                violation("rawproducer", ArchitectureRules::kafkaProducersOnlyInTheKafkaAdapter, "LedgerWriter"),
                violation("ledgercode", ArchitectureRules::nothingDependsOnTheLedgersCode, "Mirror"));
    }

    /**
     * The clean tree has a class in every layer, including BigDecimal in the file adapter and
     * JdbcClient and the PostgreSQL driver in persistence, a KafkaTemplate in the Kafka adapter, so each rule has something to check and something it must allow.
     * Empty selections are refused here, unlike in the application run: a rule whose selection
     * matched nothing in a tree that fills every layer would be checking nothing anywhere.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("allRules")
    @DisplayName("a tree that follows every rule passes every rule, and every rule selects something")
    void cleanFixturePasses(String description, ArchRule rule) {
        EvaluationResult result = rule.allowEmptyShould(false).evaluate(importFixture("clean"));

        assertThat(result.getFailureReport().getDetails()).isEmpty();
    }

    static Stream<Arguments> allRules() {
        return ArchitectureRules.forRoot(FIXTURES + "clean").all().stream()
                .map(rule -> Arguments.of(rule.getDescription(), rule));
    }

    private static Arguments violation(
            String fixture, Function<ArchitectureRules, ArchRule> rule, String offender) {
        return Arguments.of(fixture, rule, offender);
    }

    private static JavaClasses importFixture(String fixture) {
        return new ClassFileImporter().importPackages(FIXTURES + fixture);
    }
}
