package com.baran.recon.domain.statement;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.baran.recon.domain.item.SourceCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 10: statement file records")
class StatementFileTest {

    private static final String SHA = "0".repeat(63) + "f";

    @Test
    @DisplayName("an ingested file with no errors is valid")
    void ingestedFile() {
        assertThat(file(StatementFileStatus.INGESTED, "psp.csv", List.of()).errors()).isEmpty();
    }

    @Test
    @DisplayName("FR-ING-7: a rejected file carries line numbers and codes")
    void rejectedFileCarriesErrors() {
        StatementFile rejected = file(StatementFileStatus.REJECTED, "psp.csv",
                List.of(new LineError(2, ValidationCode.INVALID_AMOUNT)));

        assertThat(rejected.errors()).containsExactly(new LineError(2, ValidationCode.INVALID_AMOUNT));
    }

    @Test
    @DisplayName("FR-ING-7: a rejected file without errors is refused")
    void rejectedWithoutErrorsIsRefused() {
        assertThatThrownBy(() -> file(StatementFileStatus.REJECTED, "psp.csv", List.of()))
                .isInstanceOf(InvalidStatementFileException.class);
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @ValueSource(strings = {"", "../etc/passwd", "name with space.csv", "C:\\path\\file.csv"})
    @DisplayName("FR-ING-9: only a sanitized file name can be stored")
    void unsanitizedNameIsRefused(String name) {
        assertThatThrownBy(() -> file(StatementFileStatus.INGESTED, name, List.of()))
                .isInstanceOf(InvalidStatementFileException.class);
    }

    @Test
    @DisplayName("the hash is 64 lower-case hex digits")
    void hashFormat() {
        assertThatThrownBy(() -> new StatementFile(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), "STMT-1",
                SHA.toUpperCase(), "psp.csv", 10, 1, StatementFileStatus.INGESTED, List.of(), "operator-001",
                Instant.EPOCH))
                .isInstanceOf(InvalidStatementFileException.class);
    }

    @Test
    @DisplayName("line numbers start at 1")
    void lineNumbersStartAtOne() {
        assertThatThrownBy(() -> new LineError(0, ValidationCode.HEADER_MISMATCH))
                .isInstanceOf(InvalidStatementFileException.class);
    }

    @Test
    @DisplayName("a reference, a non-negative size and an uploader are required")
    void requiredFields() {
        assertThatThrownBy(() -> new StatementFile(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), "", SHA,
                "psp.csv", 10, 1, StatementFileStatus.INGESTED, List.of(), "operator-001", Instant.EPOCH))
                .isInstanceOf(InvalidStatementFileException.class);
        assertThatThrownBy(() -> new StatementFile(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), "STMT-1", SHA,
                "psp.csv", -1, 1, StatementFileStatus.INGESTED, List.of(), "operator-001", Instant.EPOCH))
                .isInstanceOf(InvalidStatementFileException.class);
        assertThatThrownBy(() -> new StatementFile(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), "STMT-1", SHA,
                "psp.csv", 10, 1, StatementFileStatus.INGESTED, List.of(), " ", Instant.EPOCH))
                .isInstanceOf(InvalidStatementFileException.class);
    }

    private static StatementFile file(StatementFileStatus status, String name, List<LineError> errors) {
        return new StatementFile(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), "STMT-1", SHA, name, 10, 1,
                status, errors, "operator-001", Instant.EPOCH);
    }
}
