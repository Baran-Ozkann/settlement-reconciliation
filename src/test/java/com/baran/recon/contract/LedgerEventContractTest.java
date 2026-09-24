package com.baran.recon.contract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every file in contracts/samples through contracts/ledger-events.schema.json: the verification
 * Phase 0 could not run with a permitted tool. The schema and samples are read from the repository
 * checkout, not copied onto the classpath, so what is tested is what is committed.
 *
 * <p>An invalid sample must fail for the reason its name gives, not merely fail. A schema that
 * rejected everything would fail every invalid sample too; asserting the single keyword each one was
 * written to exercise is what tells the two apart.
 */
@DisplayName("FR-LED-6: the ledger event contract samples")
class LedgerEventContractTest {

    private static final Path CONTRACTS = Path.of("contracts");
    private static final Path SCHEMA_FILE = CONTRACTS.resolve("ledger-events.schema.json");
    private static final Path SAMPLES = CONTRACTS.resolve("samples");
    private static final String META_SCHEMA = "https://json-schema.org/draft/2020-12/schema";

    /** The keyword and the field each invalid sample is written to fail on. */
    private static final Map<String, Expected> INVALID = Map.ofEntries(
            Map.entry("invalid-account-id-not-a-uuid", new Expected("pattern", "account_id")),
            Map.entry("invalid-amount-above-maximum", new Expected("maximum", "amount")),
            Map.entry("invalid-amount-fractional", new Expected("type", "amount")),
            Map.entry("invalid-amount-zero", new Expected("not", "amount")),
            Map.entry("invalid-currency-lowercase", new Expected("pattern", "currency")),
            Map.entry("invalid-missing-transaction-id", new Expected("required", "transaction_id")),
            Map.entry("invalid-tx-type-not-a-string", new Expected("type", "tx_type")),
            Map.entry("invalid-entry-id-zero", new Expected("minimum", "entry_id")),
            Map.entry("invalid-entry-id-string", new Expected("type", "entry_id")),
            Map.entry("invalid-entry-id-null", new Expected("type", "entry_id")),
            Map.entry("invalid-created-at-null", new Expected("type", "created_at")),
            Map.entry("invalid-created-at-milliseconds", new Expected("pattern", "created_at")),
            Map.entry("invalid-created-at-offset-not-utc", new Expected("pattern", "created_at")),
            Map.entry("invalid-entry-id-without-created-at", new Expected("dependentRequired", "created_at")),
            Map.entry("invalid-created-at-without-entry-id", new Expected("dependentRequired", "entry_id")));

    private static final Set<String> VALID = Set.of(
            "valid-transfer-debit",
            "valid-transfer-credit",
            "valid-funding-credit",
            "valid-reversal-debit",
            "valid-tx-type-unmapped-fee",
            "valid-five-field-written-before-entry-reference",
            "valid-created-at-istanbul-midnight");

    private static SchemaRegistry registry;
    private static Schema schema;

    /**
     * Remote fetching off: the 2020-12 meta-schema ships inside the validator, and a test that
     * reached json-schema.org would depend on a host this project is not allowed to call.
     */
    @BeforeAll
    static void loadSchema() throws IOException {
        registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false)));
        try (InputStream in = Files.newInputStream(SCHEMA_FILE)) {
            schema = registry.getSchema(in);
        }
    }

    @Test
    @DisplayName("the schema is itself a valid draft 2020-12 schema")
    void schemaConformsToTheMetaSchema() throws IOException {
        Schema metaSchema = registry.getSchema(SchemaLocation.of(META_SCHEMA));

        List<Error> errors = metaSchema.validate(read(SCHEMA_FILE), InputFormat.JSON);

        assertThat(errors).as("meta-schema errors").isEmpty();
    }

    /**
     * Without this a sample added without an expectation, or one renamed away from it, would drop
     * out of the parameterized tests below and the build would stay green having checked less.
     */
    @Test
    @DisplayName("every sample on disk has an expectation here, and every expectation has a sample")
    void everySampleIsAccountedFor() throws IOException {
        Set<String> expected = new TreeSet<>(VALID);
        expected.addAll(INVALID.keySet());

        assertThat(sampleNames()).isEqualTo(expected);
        assertThat(VALID).allMatch(name -> name.startsWith("valid-"));
        assertThat(INVALID.keySet()).allMatch(name -> name.startsWith("invalid-"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validSamples")
    @DisplayName("FR-LED-6, FR-LED-7, FR-LED-9: a valid sample validates")
    void validSampleValidates(String name) throws IOException {
        List<Error> errors = schema.validate(read(sample(name)), InputFormat.JSON);

        assertThat(errors).as("errors for %s", name).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSamples")
    @DisplayName("FR-LED-6: an invalid sample fails on exactly the keyword and field its name gives")
    void invalidSampleFailsForItsReason(String name) throws IOException {
        Expected expected = INVALID.get(name);

        List<Error> errors = schema.validate(read(sample(name)), InputFormat.JSON);

        assertThat(errors).as("errors for %s", name).hasSize(1);
        Error error = errors.getFirst();
        assertThat(error.getKeyword()).as("keyword for %s (%s)", name, error).isEqualTo(expected.keyword());
        assertThat(error.toString()).as("field for %s", name).contains(expected.field());
    }

    static Stream<String> validSamples() {
        return VALID.stream().sorted();
    }

    static Stream<String> invalidSamples() {
        return INVALID.keySet().stream().sorted();
    }

    private static Set<String> sampleNames() throws IOException {
        try (Stream<Path> files = Files.list(SAMPLES)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(file -> file.endsWith(".json"))
                    .map(file -> file.substring(0, file.length() - ".json".length()))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static Path sample(String name) {
        return SAMPLES.resolve(name + ".json");
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private record Expected(String keyword, String field) {
    }
}
