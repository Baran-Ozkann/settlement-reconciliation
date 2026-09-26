package com.baran.recon.architecture;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The layering of TDD 5.2 and the money and isolation invariants INV-8 and INV-9, written once and
 * parameterised by the root package. {@code ArchitectureTest} applies them to the application;
 * {@code ArchitectureRulesCatchViolationsTest} applies the same rules to small fixture trees that
 * break each one on purpose, which is what shows a rule can fail at all.
 *
 * <p>{@code allowEmptyShould} is set because Phase 1 has almost no classes: the packages these rules
 * talk about are empty until Phase 2 fills them, and ArchUnit's default would fail a rule for having
 * nothing to check. What stops that from hiding a rule that checks nothing is the fixture test,
 * which runs every rule against classes it must reject and against classes it must accept.
 */
final class ArchitectureRules {

    private static final Set<String> FLOATING_POINT =
            Set.of("double", "float", Double.class.getName(), Float.class.getName());

    /**
     * Everything that can put a record on a topic: the client's producer package, Spring's template
     * and producer factory, and the recoverer that publishes through a template.
     */
    private static final DescribedPredicate<JavaClass> KAFKA_PRODUCER_API =
            JavaClass.Predicates.resideInAPackage("org.apache.kafka.clients.producer..")
                    .or(JavaClass.Predicates.assignableTo(KafkaOperations.class))
                    .or(JavaClass.Predicates.assignableTo(ProducerFactory.class))
                    .or(JavaClass.Predicates.assignableTo(DeadLetterPublishingRecoverer.class))
                    .as("Kafka's producer API");

    private final String root;

    private ArchitectureRules(String root) {
        this.root = root;
    }

    static ArchitectureRules forRoot(String root) {
        return new ArchitectureRules(root);
    }

    List<ArchRule> all() {
        return List.of(
                domainDependsOnlyOnJavaAndItself(),
                applicationDependsOnlyOnDomain(),
                adaptersDoNotDependOnEachOther(),
                controllersDoNotReachPersistence(),
                noFloatingPointInDomainOrApplication(),
                bigDecimalOnlyInTheFileAdapter(),
                postgresDriverOnlyInPersistence(),
                kafkaProducersOnlyInTheKafkaAdapter(),
                nothingDependsOnTheLedgersCode());
    }

    /** TDD 5.2, and CLAUDE.md 7.2: no Spring, Jackson, Kafka or JDBC in the domain. */
    ArchRule domainDependsOnlyOnJavaAndItself() {
        return classes().that().resideInAPackage(pkg("domain.."))
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", pkg("domain.."))
                .because("the domain is pure Java (TDD 5.2)")
                .allowEmptyShould(true);
    }

    ArchRule applicationDependsOnlyOnDomain() {
        return classes().that().resideInAPackage(pkg("application.."))
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", pkg("domain.."), pkg("application.."))
                .because("use cases and ports see the domain and nothing else (TDD 5.2)")
                .allowEmptyShould(true);
    }

    /** Each adapter is a slice: in.web, in.kafka, in.file, out.persistence. */
    ArchRule adaptersDoNotDependOnEachOther() {
        return slices().matching(pkg("adapters.(*).(*)..")).namingSlices("adapter $1.$2")
                .should().notDependOnEachOther()
                .because("adapters meet only through application ports (TDD 5.2)")
                .allowEmptyShould(true);
    }

    ArchRule controllersDoNotReachPersistence() {
        return noClasses().that().resideInAPackage(pkg("adapters.in.web.."))
                .should().dependOnClassesThat().resideInAnyPackage(pkg("adapters.out.."), "org.springframework.jdbc..")
                .because("controllers never access repositories directly (TDD 5.2)")
                .allowEmptyShould(true);
    }

    /**
     * INV-8. A field, a signature or a call that carries a floating-point type. A local variable
     * leaves no type in the bytecode ArchUnit reads, which is why ci/check-rules.sh greps the source
     * for the same words.
     */
    ArchRule noFloatingPointInDomainOrApplication() {
        return classes().that().resideInAnyPackage(pkg("domain.."), pkg("application.."))
                .should(notUseFloatingPoint())
                .because("money is integer minor units end to end (INV-8)")
                .allowEmptyShould(true);
    }

    /** INV-8 and TDD 6: BigDecimal exists only to convert CSV decimals exactly, in the parser. */
    ArchRule bigDecimalOnlyInTheFileAdapter() {
        return noClasses().that().resideInAPackage(pkg(".."))
                .and().resideOutsideOfPackage(pkg("adapters.in.file.."))
                .should().dependOnClassesThat().belongToAnyOf(java.math.BigDecimal.class)
                .because("BigDecimal never leaves the file-parsing adapter (INV-8, TDD 6)")
                .allowEmptyShould(true);
    }

    /**
     * The driver is on the compile classpath because the persistence adapter reads the name of a
     * violated constraint from PSQLException (FR-LED-8). That reason is the persistence adapter's
     * alone: anywhere else the driver's types would tie code to PostgreSQL that has no business
     * knowing which database it runs on. Domain and application are already held to java.* and
     * themselves; this also covers the other adapters and config.
     */
    ArchRule postgresDriverOnlyInPersistence() {
        return noClasses().that().resideInAPackage(pkg(".."))
                .and().resideOutsideOfPackage(pkg("adapters.out.persistence.."))
                .should().dependOnClassesThat().resideInAPackage("org.postgresql..")
                .because("only the persistence adapter may know the database driver")
                .allowEmptyShould(true);
    }

    /**
     * INV-9, the compile-time half: the only code that can write to Kafka is the Kafka adapter,
     * whose one producer writes the dead-letter topic. The run-time half, a producer post-processor
     * that refuses every other topic, covers what a type check cannot see: which topic a send names.
     */
    ArchRule kafkaProducersOnlyInTheKafkaAdapter() {
        return noClasses().that().resideInAPackage(pkg(".."))
                .and().resideOutsideOfPackage(pkg("adapters.in.kafka.."))
                .should().dependOnClassesThat(KAFKA_PRODUCER_API)
                .because("only the Kafka adapter writes to Kafka, and only its dead-letter topic (INV-9)")
                .allowEmptyShould(true);
    }

    /** INV-9: the ledger is reached only through its event contract, never through its code. */
    ArchRule nothingDependsOnTheLedgersCode() {
        return noClasses().that().resideInAPackage(pkg(".."))
                .should().dependOnClassesThat().resideInAPackage("com.baran.ledger..")
                .because("the event contract in contracts/ is the only coupling to the ledger (INV-9)")
                .allowEmptyShould(true);
    }

    private String pkg(String relative) {
        return relative.startsWith("..") ? root + relative : root + "." + relative;
    }

    private static ArchCondition<JavaClass> notUseFloatingPoint() {
        return new ArchCondition<>("not use float, double, Float or Double") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaField field : javaClass.getFields()) {
                    reportIfFloating(field.getRawType(), field.getFullName(), events);
                }
                for (JavaCodeUnit unit : javaClass.getCodeUnits()) {
                    reportIfFloating(unit.getRawReturnType(), unit.getFullName() + " returns", events);
                    for (JavaClass parameter : unit.getRawParameterTypes()) {
                        reportIfFloating(parameter, unit.getFullName() + " takes", events);
                    }
                }
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    Stream.concat(Stream.of(call.getTarget().getRawReturnType()),
                                    call.getTarget().getRawParameterTypes().stream())
                            .forEach(type -> reportIfFloating(type, call.getDescription(), events));
                }
            }
        };
    }

    private static void reportIfFloating(JavaClass type, String where, ConditionEvents events) {
        JavaClass base = type;
        while (base.isArray()) {
            base = base.getComponentType();
        }
        if (FLOATING_POINT.contains(base.getName())) {
            events.add(SimpleConditionEvent.violated(type, where + " uses " + type.getName()));
        }
    }
}
