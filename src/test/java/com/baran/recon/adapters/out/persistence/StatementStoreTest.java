package com.baran.recon.adapters.out.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.StatementStore;
import com.baran.recon.domain.item.BankLine;
import com.baran.recon.domain.item.PspLine;
import com.baran.recon.domain.item.PspLineType;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.statement.LineError;
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

    @Test
    @DisplayName("an ingested file and its PSP and bank lines are read back unchanged")
    void fileAndLinesRoundTrip() {
        StatementFile file = file(SourceCode.of("PSP_ROUND_TRIP"), StatementFileStatus.INGESTED, List.of());
        PspLine withReference = pspLine(file, "L-1", Optional.of("5f0c7a1e-0000-4000-8000-000000000001"));
        PspLine withoutReference = pspLine(file, "L-2", Optional.empty());
        BankLine bankLine = new BankLine(UUID.randomUUID(), file.id(), SourceCode.of("BANK_ROUND_TRIP"), "S-1", DAY, DAY,
                Money.of(24_500, TRY), Optional.of("BATCH-B-001"), Optional.of("B-001"), Optional.empty());

        store.storeFile(file);
        assertThat(store.storePspLines(Stream.of(withReference, withoutReference))).isEqualTo(2);
        assertThat(store.storeBankLines(Stream.of(bankLine))).isEqualTo(1);

        assertThat(store.findFile(file.id())).contains(file);
        assertThat(store.findPspLine(withReference.id())).contains(withReference);
        assertThat(store.findPspLine(withoutReference.id())).contains(withoutReference);
        assertThat(store.findBankLine(bankLine.id())).contains(bankLine);
    }

    @Test
    @DisplayName("FR-ING-7: a rejected file is stored with its line numbers and codes")
    void rejectedFileKeepsItsErrors() {
        List<LineError> errors = List.of(new LineError(2, ValidationCode.INVALID_AMOUNT),
                new LineError(7, ValidationCode.NET_AMOUNT_MISMATCH));
        StatementFile file = file(SourceCode.of("PSP_REJECTED"), StatementFileStatus.REJECTED, errors);

        store.storeFile(file);

        assertThat(store.findFile(file.id())).map(StatementFile::errors).contains(errors);
    }

    @Test
    @DisplayName("TDD 5.3: lines are written in batches, across batch boundaries, and every one is stored")
    void linesAcrossSeveralBatches() {
        SourceCode source = SourceCode.of("PSP_BATCHES");
        StatementFile file = file(source, StatementFileStatus.INGESTED, List.of());
        long lines = 2L * JdbcStatementStore.BATCH_SIZE + 1;
        store.storeFile(file);

        long stored = store.storePspLines(LongStream.rangeClosed(1, lines)
                .mapToObj(n -> pspLine(file, "L-" + n, Optional.empty())));

        assertThat(stored).isEqualTo(lines);
        assertThat(jdbc.sql("SELECT count(*) FROM psp_lines WHERE file_id = :fileId").param("fileId", file.id())
                .query(Long.class).single()).isEqualTo(lines);
    }

    @Test
    @DisplayName("FR-ING-4: a line id already stored for the source is refused, even from another file")
    void duplicateLineAcrossFilesIsRefused() {
        SourceCode source = SourceCode.of("PSP_DUPLICATE");
        StatementFile first = file(source, StatementFileStatus.INGESTED, List.of());
        StatementFile second = file(source, StatementFileStatus.INGESTED, List.of());
        store.storeFile(first);
        store.storeFile(second);
        store.storePspLines(Stream.of(pspLine(first, "L-1", Optional.empty())));

        assertThatThrownBy(() -> store.storePspLines(Stream.of(pspLine(second, "L-1", Optional.empty()))))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private static StatementFile file(SourceCode source, StatementFileStatus status, List<LineError> errors) {
        UUID id = UUID.randomUUID();
        String sha256 = HexFormat.of().formatHex(id.toString().getBytes()).substring(0, 64);
        return new StatementFile(id, source, "STMT-" + id, sha256, "statement-2026-09-24.csv", 2048, 2,
                status, errors, "operator-001", RECEIVED);
    }

    private static PspLine pspLine(StatementFile file, String lineId, Optional<String> reference) {
        return new PspLine(UUID.randomUUID(), file.id(), file.source(), lineId, reference, "B-001",
                PspLineType.PAYMENT, DAY, DAY, Money.of(12_500, TRY), Money.of(250, TRY), Money.of(12_250, TRY));
    }
}
