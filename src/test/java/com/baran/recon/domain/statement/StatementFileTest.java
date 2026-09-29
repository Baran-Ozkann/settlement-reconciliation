package com.baran.recon.domain.statement;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.baran.recon.domain.item.SourceCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 10: statement file records and their line summary")
class StatementFileTest {

    private static final String SHA = "0".repeat(63) + "f";
    private static final UUID BREAK = UUID.fromString("00000000-0000-4000-8000-00000000b4ea");

    @Test
    @DisplayName("an ingested file with every line stored is valid")
    void ingestedFile() {
        StatementFile file = file(StatementFileStatus.INGESTED, "psp.csv", clean(3));

        assertThat(file.lines().allErrors()).isEmpty();
        assertThat(file.lines().storedLineCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("FR-ING-7: a rejected file carries line numbers and codes")
    void rejectedFileCarriesErrors() {
        StatementFile rejected = file(StatementFileStatus.REJECTED, "psp.csv",
                new LineSummary(Optional.empty(), 3, 1, List.of(new LineError(2, ValidationCode.INVALID_AMOUNT)), 0,
                        List.of()));

        assertThat(rejected.lines().allErrors()).containsExactly(new LineError(2, ValidationCode.INVALID_AMOUNT));
    }

    @Test
    @DisplayName("FR-ING-7: a rejected file without errors is refused")
    void rejectedWithoutErrorsIsRefused() {
        assertThatThrownBy(() -> file(StatementFileStatus.REJECTED, "psp.csv", clean(1)))
                .isInstanceOf(InvalidStatementFileException.class);
    }

    @Test
    @DisplayName("FR-ING-8: a file refused at its header has no data lines, and only that error")
    void headerRejectedFile() {
        LineSummary header = LineSummary.headerRejected(new LineError(1, ValidationCode.HEADER_MISMATCH));
        StatementFile rejected = file(StatementFileStatus.REJECTED, "psp.csv", header);

        assertThat(rejected.lines().allErrors()).containsExactly(new LineError(1, ValidationCode.HEADER_MISMATCH));
        assertThat(rejected.lines().lineCount()).isZero();
        assertThatThrownBy(() -> file(StatementFileStatus.INGESTED, "psp.csv", header))
                .isInstanceOf(InvalidStatementFileException.class);
        assertThatThrownBy(() -> new LineSummary(Optional.of(new LineError(2, ValidationCode.HEADER_MISMATCH)), 0, 0,
                List.of(), 0, List.of())).isInstanceOf(InvalidStatementFileException.class);
        assertThatThrownBy(() -> new LineSummary(Optional.of(new LineError(1, ValidationCode.HEADER_MISMATCH)), 1, 0,
                List.of(), 0, List.of())).isInstanceOf(InvalidStatementFileException.class);
    }

    @Test
    @DisplayName("FR-ING-4: an ingested file records the lines it repeated; a rejected one repeated none")
    void duplicatesBelongToIngestedFiles() {
        LineSummary repeated = new LineSummary(Optional.empty(), 2, 0, List.of(), 1, List.of(new DuplicateLine(3, BREAK, true)));

        assertThat(file(StatementFileStatus.INGESTED, "psp.csv", repeated).lines().storedLineCount()).isOne();
        assertThatThrownBy(() -> file(StatementFileStatus.REJECTED, "psp.csv", new LineSummary(Optional.empty(), 3, 1,
                List.of(new LineError(2, ValidationCode.INVALID_DATE)), 1, List.of(new DuplicateLine(3, BREAK, true)))))
                .isInstanceOf(InvalidStatementFileException.class);
    }

    @Test
    @DisplayName("FR-ING-5: counts are complete, and the lists hold the first 1,000 of each")
    void listsAreCapped() {
        List<LineError> first = LongStream.rangeClosed(2, LineSummary.MAX_LISTED + 1)
                .mapToObj(line -> new LineError(line, ValidationCode.INVALID_DATE)).toList();

        LineSummary many = new LineSummary(Optional.empty(), 5_000, 4_000, first, 0, List.of());

        assertThat(many.errors()).hasSize(LineSummary.MAX_LISTED);
        assertThat(many.invalidLineCount()).isEqualTo(4_000);
        assertThatThrownBy(() -> new LineSummary(Optional.empty(), 5_000, 4_000, first.subList(0, 10), 0, List.of()))
                .isInstanceOf(InvalidStatementFileException.class);
        assertThatThrownBy(() -> new LineSummary(Optional.empty(), 5_000, 3, first, 0, List.of()))
                .isInstanceOf(InvalidStatementFileException.class);
    }

    @Test
    @DisplayName("counts cannot exceed the lines of the file, be negative, or list line 1 as a data line")
    void countsAreConsistent() {
        assertThatThrownBy(() -> new LineSummary(Optional.empty(), 1, 1, List.of(new LineError(2, ValidationCode.INVALID_DATE)),
                1, List.of(new DuplicateLine(3, BREAK, false)))).isInstanceOf(InvalidStatementFileException.class);
        assertThatThrownBy(() -> new LineSummary(Optional.empty(), -1, 0, List.of(), 0, List.of()))
                .isInstanceOf(InvalidStatementFileException.class);
        assertThatThrownBy(() -> new LineSummary(Optional.empty(), 2, 1, List.of(new LineError(1, ValidationCode.INVALID_DATE)),
                0, List.of())).isInstanceOf(InvalidStatementFileException.class);
        assertThatThrownBy(() -> new DuplicateLine(1, BREAK, true)).isInstanceOf(InvalidStatementFileException.class);
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @ValueSource(strings = {"", "../etc/passwd", "name with space.csv", "C:\\path\\file.csv"})
    @DisplayName("FR-ING-9: only a sanitized file name can be stored")
    void unsanitizedNameIsRefused(String name) {
        assertThatThrownBy(() -> file(StatementFileStatus.INGESTED, name, clean(1)))
                .isInstanceOf(InvalidStatementFileException.class);
    }

    @Test
    @DisplayName("the hash is 64 lower-case hex digits")
    void hashFormat() {
        assertThatThrownBy(() -> new StatementFile(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), "STMT-1",
                SHA.toUpperCase(), "psp.csv", 10, StatementFileStatus.INGESTED, clean(1), "operator-001", Instant.EPOCH))
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
                "psp.csv", 10, StatementFileStatus.INGESTED, clean(1), "operator-001", Instant.EPOCH))
                .isInstanceOf(InvalidStatementFileException.class);
        assertThatThrownBy(() -> new StatementFile(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), "STMT-1", SHA,
                "psp.csv", -1, StatementFileStatus.INGESTED, clean(1), "operator-001", Instant.EPOCH))
                .isInstanceOf(InvalidStatementFileException.class);
        assertThatThrownBy(() -> new StatementFile(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), "STMT-1", SHA,
                "psp.csv", 10, StatementFileStatus.INGESTED, clean(1), " ", Instant.EPOCH))
                .isInstanceOf(InvalidStatementFileException.class);
    }

    private static LineSummary clean(long lines) {
        return new LineSummary(Optional.empty(), lines, 0, List.of(), 0, List.of());
    }

    private static StatementFile file(StatementFileStatus status, String name, LineSummary lines) {
        return new StatementFile(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), "STMT-1", SHA, name, 10, status, lines,
                "operator-001", Instant.EPOCH);
    }
}
