package com.baran.recon.adapters.out.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.StatementStore.LineConflict;
import com.baran.recon.domain.item.BankLine;
import com.baran.recon.domain.item.PspLine;
import com.baran.recon.domain.item.PspLineType;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.statement.DuplicateLine;
import com.baran.recon.domain.statement.LineError;
import com.baran.recon.domain.statement.LineSummary;
import com.baran.recon.domain.statement.StatementFile;
import com.baran.recon.domain.statement.StatementFileStatus;
import com.baran.recon.domain.statement.ValidationCode;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Statement files and their lines through the application's DataSource, as recon_app. The database
 * is shared and recon_app cannot delete, so each test uses a source code, file and line ids of its
 * own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("Statement files and their lines are stored in batches, as recon_app")
class StatementStoreTest {

    private static final CurrencyCode TRY = CurrencyCode.of("TRY");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);
    private static final Instant RECEIVED = Instant.parse("2026-09-24T09:00:00Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private StatementStore store;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("an ingested file and its PSP and bank lines are read back unchanged")
    void fileAndLinesRoundTrip() {
        StatementFile file = file(SourceCode.of("PSP_ROUND_TRIP"), StatementFileStatus.INGESTED, List.of());
        PspLine withReference = pspLine(file, "L-1", Optional.of("5f0c7a1e-0000-4000-8000-000000000001"));
        PspLine withoutReference = pspLine(file, "L-2", Optional.empty());
        BankLine bankLine = bankLine(file, SourceCode.of("BANK_ROUND_TRIP"), "S-1", Optional.of("B-001"));
        BankLine bare = new BankLine(UUID.randomUUID(), file.id(), SourceCode.of("BANK_ROUND_TRIP"), "S-2", DAY,
                DAY.plusDays(1), Money.of(-1, TRY), Optional.empty(), Optional.empty(), Optional.empty());

        store.storeFile(file);
        assertThat(store.storePspLinesIfAbsent(List.of(withReference, withoutReference))).isEmpty();
        assertThat(store.storeBankLinesIfAbsent(List.of(bankLine, bare))).isEmpty();

        assertThat(store.findFile(file.id())).contains(file);
        assertThat(store.findPspLine(withReference.id())).contains(withReference);
        assertThat(store.findPspLine(withoutReference.id())).contains(withoutReference);
        assertThat(store.findBankLine(bankLine.id())).contains(bankLine);
        assertThat(store.findBankLine(bare.id())).contains(bare);
    }

    @Test
    @DisplayName("FR-ING-7: a rejected file is stored with its line numbers and codes")
    void rejectedFileKeepsItsErrors() {
        List<LineError> errors = List.of(new LineError(2, ValidationCode.INVALID_AMOUNT),
                new LineError(7, ValidationCode.NET_AMOUNT_MISMATCH));
        StatementFile file = file(SourceCode.of("PSP_REJECTED"), StatementFileStatus.REJECTED, errors);

        store.storeFile(file);

        assertThat(store.findFile(file.id())).contains(file);
    }

    @Test
    @DisplayName("FR-ING-4, FR-ING-7: a summary with a header error, or with counts, errors and duplicates, reads back unchanged")
    void summariesRoundTrip() {
        StatementFile header = new StatementFile(UUID.randomUUID(), SourceCode.of("PSP_SUMMARY"), "STMT-H-" + UUID.randomUUID(),
                sha(), "header.csv", 12, StatementFileStatus.REJECTED,
                LineSummary.headerRejected(new LineError(1, ValidationCode.HEADER_MISMATCH)), "operator-001", RECEIVED);
        StatementFile ingested = new StatementFile(UUID.randomUUID(), SourceCode.of("PSP_SUMMARY"), "STMT-I-" + UUID.randomUUID(),
                sha(), "ingested.csv", 4096, StatementFileStatus.INGESTED,
                new LineSummary(Optional.empty(), 10, 1, List.of(new LineError(4, ValidationCode.DATE_ORDER)), 2,
                        List.of(new DuplicateLine(5, UUID.randomUUID(), true), new DuplicateLine(9, UUID.randomUUID(), false))),
                "operator-001", RECEIVED);

        store.storeFile(header);
        store.storeFile(ingested);

        assertThat(store.findFile(header.id())).contains(header);
        assertThat(store.findFile(ingested.id())).contains(ingested);
        assertThat(jdbc.sql("SELECT error_summary IS NULL FROM statement_files WHERE id = :id").param("id", header.id())
                .query(Boolean.class).single()).isFalse();
    }

    @Test
    @DisplayName("a file with every line stored has no summary at all")
    void cleanFileHasNoSummary() {
        StatementFile clean = file(SourceCode.of("PSP_CLEAN"), StatementFileStatus.INGESTED, List.of());

        store.storeFile(clean);

        assertThat(jdbc.sql("SELECT error_summary IS NULL FROM statement_files WHERE id = :id").param("id", clean.id())
                .query(Boolean.class).single()).isTrue();
        assertThat(store.findFile(clean.id())).contains(clean);
    }

    @Test
    @DisplayName("TDD 5.3: lines are written in batches, across batch boundaries, and every one is stored")
    void linesAcrossSeveralBatches() {
        SourceCode source = SourceCode.of("PSP_BATCHES");
        StatementFile file = file(source, StatementFileStatus.INGESTED, List.of());
        long lines = 2L * JdbcStatementStore.BATCH_SIZE + 1;
        store.storeFile(file);

        List<LineConflict> conflicts = store.storePspLinesIfAbsent(LongStream.rangeClosed(1, lines)
                .mapToObj(n -> pspLine(file, "L-" + n, Optional.empty())).toList());

        assertThat(conflicts).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM psp_lines WHERE file_id = :fileId").param("fileId", file.id())
                .query(Long.class).single()).isEqualTo(lines);
    }

    @Test
    @DisplayName("FR-ING-4, INV-3: a line id the source already stored from another file is skipped and reported")
    void lineStoredFromAnotherFileIsReported() {
        SourceCode source = SourceCode.of("PSP_DUPLICATE");
        StatementFile first = file(source, StatementFileStatus.INGESTED, List.of());
        StatementFile second = file(source, StatementFileStatus.INGESTED, List.of());
        store.storeFile(first);
        store.storeFile(second);
        PspLine original = pspLine(first, "L-1", Optional.empty());
        store.storePspLinesIfAbsent(List.of(original));
        PspLine repeated = pspLine(second, "L-1", Optional.empty());
        PspLine fresh = pspLine(second, "L-2", Optional.empty());

        List<LineConflict> conflicts = store.storePspLinesIfAbsent(List.of(repeated, fresh));

        assertThat(conflicts).containsExactly(new LineConflict(repeated.id(), original.id(), first.id()));
        assertThat(store.findPspLine(repeated.id())).isEmpty();
        assertThat(store.findPspLine(fresh.id())).contains(fresh);
        assertThat(store.findPspLine(original.id())).contains(original);
    }

    @Test
    @DisplayName("FR-ING-4: the same line id twice in one batch stores the first and reports the second against it")
    void repeatWithinOneBatchIsReportedAgainstTheFirst() {
        SourceCode source = SourceCode.of("PSP_IN_BATCH");
        StatementFile file = file(source, StatementFileStatus.INGESTED, List.of());
        store.storeFile(file);
        PspLine first = pspLine(file, "L-1", Optional.empty());
        PspLine again = pspLine(file, "L-1", Optional.of("another-reference"));

        List<LineConflict> conflicts = store.storePspLinesIfAbsent(List.of(first, again));

        assertThat(conflicts).containsExactly(new LineConflict(again.id(), first.id(), file.id()));
        assertThat(store.findPspLine(first.id())).contains(first);
    }

    @Test
    @DisplayName("FR-ING-4: a bank line id repeated across files is reported the same way; another source may reuse it")
    void bankLineConflicts() {
        SourceCode source = SourceCode.of("BANK_DUPLICATE");
        StatementFile first = file(source, StatementFileStatus.INGESTED, List.of());
        StatementFile second = file(source, StatementFileStatus.INGESTED, List.of());
        store.storeFile(first);
        store.storeFile(second);
        BankLine original = bankLine(first, source, "S-1", Optional.empty());
        store.storeBankLinesIfAbsent(List.of(original));
        BankLine repeated = bankLine(second, source, "S-1", Optional.empty());
        BankLine otherSource = bankLine(second, SourceCode.of("BANK_OTHER"), "S-1", Optional.empty());

        assertThat(store.storeBankLinesIfAbsent(List.of(repeated, otherSource)))
                .containsExactly(new LineConflict(repeated.id(), original.id(), first.id()));
        assertThat(store.findBankLine(otherSource.id())).contains(otherSource);
    }

    @Test
    @DisplayName("FR-ING-3: ingested files are found by hash and by source and reference; rejected ones never")
    void ingestedFilesAreFoundRejectedAreNot() {
        StatementFile ingested = file(SourceCode.of("PSP_LOOKUP"), StatementFileStatus.INGESTED, List.of());
        StatementFile rejected = file(SourceCode.of("PSP_LOOKUP"), StatementFileStatus.REJECTED,
                List.of(new LineError(2, ValidationCode.INVALID_DATE)));
        store.storeFile(ingested);
        store.storeFile(rejected);

        assertThat(store.findIngestedFileBySha256(ingested.sha256())).contains(ingested.id());
        assertThat(store.findIngestedFileBySha256(rejected.sha256())).isEmpty();
        assertThat(store.findIngestedFileByReference(ingested.source(), ingested.statementReference()))
                .contains(ingested.id());
        assertThat(store.findIngestedFileByReference(rejected.source(), rejected.statementReference())).isEmpty();
        assertThat(store.findIngestedFileByReference(SourceCode.of("PSP_ELSEWHERE"), ingested.statementReference()))
                .isEmpty();
    }

    @Test
    @DisplayName("FR-ING-6: after checkLineFilesAtCommit, lines may precede their file; without the file the commit fails")
    void linesMayPrecedeTheirFileUntilCommit() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        StatementFile file = file(SourceCode.of("PSP_DEFERRED"), StatementFileStatus.INGESTED, List.of());
        PspLine line = pspLine(file, "L-1", Optional.empty());
        StatementFile orphanFile = file(SourceCode.of("PSP_DEFERRED"), StatementFileStatus.INGESTED, List.of());
        PspLine orphan = pspLine(orphanFile, "L-2", Optional.empty());

        transaction.executeWithoutResult(status -> {
            store.checkLineFilesAtCommit();
            store.storePspLinesIfAbsent(List.of(line));
            store.storeFile(file);
        });
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            store.checkLineFilesAtCommit();
            store.storePspLinesIfAbsent(List.of(orphan));
        })).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("psp_lines_file_fk");

        assertThat(store.findPspLine(line.id())).contains(line);
        assertThat(store.findPspLine(orphan.id())).isEmpty();
    }

    /** A file of ten data lines, with the given ones invalid. */
    private static StatementFile file(SourceCode source, StatementFileStatus status, List<LineError> errors) {
        UUID id = UUID.randomUUID();
        return new StatementFile(id, source, "STMT-" + id, sha(), "statement-2026-09-24.csv", 2048, status,
                new LineSummary(Optional.empty(), 10, errors.size(), errors, 0, List.of()), "operator-001", RECEIVED);
    }

    private static String sha() {
        return HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes()).substring(0, 64);
    }

    private static PspLine pspLine(StatementFile file, String lineId, Optional<String> reference) {
        return new PspLine(UUID.randomUUID(), file.id(), file.source(), lineId, reference, "B-001",
                PspLineType.PAYMENT, DAY, DAY, Money.of(12_500, TRY), Money.of(250, TRY), Money.of(12_250, TRY));
    }

    private static BankLine bankLine(StatementFile file, SourceCode source, String lineId, Optional<String> batchId) {
        return new BankLine(UUID.randomUUID(), file.id(), source, lineId, DAY, DAY, Money.of(24_500, TRY),
                batchId.map(id -> "BATCH-" + id), batchId, Optional.of("Settlement Test Merchant 001"));
    }
}
