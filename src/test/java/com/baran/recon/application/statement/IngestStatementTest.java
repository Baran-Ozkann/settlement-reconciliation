package com.baran.recon.application.statement;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.TooManyLinesException;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.breaks.Break;
import com.baran.recon.domain.breaks.BreakEvent;
import com.baran.recon.domain.breaks.BreakStatus;
import com.baran.recon.domain.breaks.BreakType;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.statement.DuplicateLine;
import com.baran.recon.domain.statement.LineError;
import com.baran.recon.domain.statement.StatementFile;
import com.baran.recon.domain.statement.StatementFileStatus;
import com.baran.recon.domain.statement.ValidationCode;
import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.application.statement.StatementFiles.bank;
import static com.baran.recon.application.statement.StatementFiles.bankLine;
import static com.baran.recon.application.statement.StatementFiles.psp;
import static com.baran.recon.application.statement.StatementFiles.pspLine;
import static com.baran.recon.application.statement.StatementFiles.unique;
import static com.baran.recon.application.statement.StatementFiles.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ingestion use case against the real database, as recon_app, with the default threshold of 0.
 * The database is shared and nothing can be deleted, so each test's line ids and references are its
 * own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_INGEST",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[1].code=BANK_INGEST",
        "recon.sources[1].type=BANK_STATEMENT",
        "recon.sources[1].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
        "recon.ingestion.max-lines=2000"})
@ActiveProfiles("test")
@DisplayName("TDD 5.3: a statement file is ingested atomically, or rejected with its line errors")
class IngestStatementTest {

    private static final String PSP = "PSP_INGEST";
    private static final String BANK = "BANK_INGEST";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private IngestStatement ingest;

    @Autowired
    private StatementStore store;

    @Autowired
    private BreakStore breaks;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("FR-ING-1, FR-ING-6: a valid PSP file is ingested with every line, recorded once in its final state")
    void validPspFileIsIngested() throws IOException {
        String id = unique();
        String content = psp(List.of(pspLine(id + "-1"), pspLine(id + "-2"), pspLine(id + "-3")));

        StatementFile file = ingest.ingest(upload(PSP, "STMT-" + id, content));

        assertThat(file.status()).isEqualTo(StatementFileStatus.INGESTED);
        assertThat(file.lines().lineCount()).isEqualTo(3);
        assertThat(file.lines().storedLineCount()).isEqualTo(3);
        assertThat(file.sha256()).isEqualTo(sha256(content));
        assertThat(file.sizeBytes()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
        assertThat(file.uploadedBy()).isEqualTo("operator-001");
        assertThat(file.sanitizedFilename()).isEqualTo("statement.csv");
        assertThat(store.findFile(file.id())).contains(file);
        assertThat(linesOf("psp_lines", file)).isEqualTo(3);
    }

    @Test
    @DisplayName("FR-ING-2: a bank source's file is read as a bank statement, its batch ids extracted")
    void bankFileIsIngested() throws IOException {
        String id = unique();

        StatementFile file = ingest.ingest(upload(BANK, "STMT-" + id, bank(List.of(bankLine(id + "-1")))));

        assertThat(file.status()).isEqualTo(StatementFileStatus.INGESTED);
        assertThat(jdbc.sql("SELECT extracted_batch_id FROM bank_lines WHERE file_id = :id").param("id", file.id())
                .query(String.class).single()).isEqualTo("B-001");
    }

    @Test
    @DisplayName("FR-ING-2: a PSP file uploaded to a bank source is read as a bank file, and refused at its header")
    void formatComesFromTheSourceNotTheFile() throws IOException {
        String id = unique();

        StatementFile file = ingest.ingest(upload(BANK, "STMT-" + id, psp(List.of(pspLine(id + "-1")))));

        assertThat(file.status()).isEqualTo(StatementFileStatus.REJECTED);
        assertThat(file.lines().allErrors()).containsExactly(new LineError(1, ValidationCode.HEADER_MISMATCH));
    }

    @Test
    @DisplayName("FR-ING-6, FR-ING-7: one invalid line rejects the file; none of its lines is stored; the rejection is recorded")
    void invalidLineRejectsTheWholeFile() throws IOException {
        String id = unique();
        String content = psp(List.of(pspLine(id + "-1"), pspLine(id + "-2").replace("2026-09-23", "2026-02-30"),
                pspLine(id + "-3")));

        StatementFile file = ingest.ingest(upload(PSP, "STMT-" + id, content));

        assertThat(file.status()).isEqualTo(StatementFileStatus.REJECTED);
        assertThat(file.lines().errors()).containsExactly(new LineError(3, ValidationCode.INVALID_DATE));
        assertThat(file.lines().invalidLineCount()).isOne();
        assertThat(store.findFile(file.id())).contains(file);
        assertThat(linesOf("psp_lines", file)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM psp_lines WHERE line_id LIKE :prefix").param("prefix", id + "%")
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("FR-ING-3, INV-3: the same content again is refused with the original's id, and adds no row")
    void duplicateContentIsRefused() throws IOException {
        String id = unique();
        String content = psp(List.of(pspLine(id + "-1")));
        StatementFile first = ingest.ingest(upload(PSP, "STMT-" + id + "-A", content));
        long files = count("statement_files");
        long lines = count("psp_lines");

        assertThatThrownBy(() -> ingest.ingest(upload(PSP, "STMT-" + id + "-B", content)))
                .isInstanceOfSatisfying(UploadRefusedException.class, refused -> {
                    assertThat(refused.reason()).isEqualTo(UploadRefusedException.Reason.DUPLICATE_CONTENT);
                    assertThat(refused.originalFileId()).contains(first.id());
                });
        assertThat(count("statement_files")).isEqualTo(files);
        assertThat(count("psp_lines")).isEqualTo(lines);
    }

    @Test
    @DisplayName("FR-ING-3: a second file under the same source and statement reference is refused with the original's id")
    void duplicateReferenceIsRefused() throws IOException {
        String id = unique();
        StatementFile first = ingest.ingest(upload(PSP, "STMT-" + id, psp(List.of(pspLine(id + "-1")))));

        assertThatThrownBy(() -> ingest.ingest(upload(PSP, "STMT-" + id, psp(List.of(pspLine(id + "-2"))))))
                .isInstanceOfSatisfying(UploadRefusedException.class, refused -> {
                    assertThat(refused.reason()).isEqualTo(UploadRefusedException.Reason.DUPLICATE_REFERENCE);
                    assertThat(refused.originalFileId()).contains(first.id());
                });
    }

    @Test
    @DisplayName("FR-ING-3: a rejected file does not block its content or its reference; corrected, it is ingested")
    void rejectedFileCanBeSentAgain() throws IOException {
        String id = unique();
        String broken = psp(List.of(pspLine(id + "-1").replace("PAYMENT", "PAYOUT")));
        StatementFile rejected = ingest.ingest(upload(PSP, "STMT-" + id, broken));

        StatementFile rejectedAgain = ingest.ingest(upload(PSP, "STMT-" + id, broken));
        StatementFile corrected = ingest.ingest(upload(PSP, "STMT-" + id, psp(List.of(pspLine(id + "-1")))));

        assertThat(rejected.status()).isEqualTo(StatementFileStatus.REJECTED);
        assertThat(rejectedAgain.status()).isEqualTo(StatementFileStatus.REJECTED);
        assertThat(rejectedAgain.id()).isNotEqualTo(rejected.id());
        assertThat(corrected.status()).isEqualTo(StatementFileStatus.INGESTED);
    }

    @Test
    @DisplayName("FR-ING-4: a line another file stored is not stored again; a DUPLICATE_LINE break opens on the stored line")
    void lineRepeatedFromAnotherFileOpensABreak() throws IOException {
        String id = unique();
        StatementFile first = ingest.ingest(upload(PSP, "STMT-" + id + "-A", psp(List.of(pspLine(id + "-1")))));
        UUID storedLine = jdbc.sql("SELECT id FROM psp_lines WHERE file_id = :id").param("id", first.id())
                .query(UUID.class).single();

        StatementFile second = ingest.ingest(upload(PSP, "STMT-" + id + "-B",
                psp(List.of(pspLine(id + "-2"), pspLine(id + "-1").replace("B-001", "B-002")))));

        assertThat(second.status()).isEqualTo(StatementFileStatus.INGESTED);
        assertThat(second.lines().duplicateLineCount()).isOne();
        assertThat(second.lines().storedLineCount()).isOne();
        DuplicateLine duplicate = second.lines().duplicates().getFirst();
        assertThat(duplicate.lineNumber()).isEqualTo(3);
        assertThat(duplicate.breakOpened()).isTrue();
        Break opened = breaks.findById(duplicate.breakId()).orElseThrow();
        assertThat(opened.type()).isEqualTo(BreakType.DUPLICATE_LINE);
        assertThat(opened.item().side()).isEqualTo(ItemSide.PSP);
        assertThat(opened.item().id()).isEqualTo(storedLine);
        assertThat(opened.status()).isEqualTo(BreakStatus.OPEN);
        BreakEvent event = breaks.eventsOf(opened.id()).getFirst();
        assertThat(event.actor()).isEqualTo(Actor.operator("operator-001"));
        assertThat(event.reason()).contains("Line 3 of statement file " + second.id() + " repeats this line's line_id");
        assertThat(linesOf("psp_lines", second)).isOne();
        assertThat(store.findFile(second.id())).contains(second);
    }

    @Test
    @DisplayName("FR-ING-4, INV-7: repeated again while its break is open, the line points at that break and opens none")
    void lineRepeatedAgainPointsAtTheOpenBreak() throws IOException {
        String id = unique();
        ingest.ingest(upload(PSP, "STMT-" + id + "-A", psp(List.of(pspLine(id + "-1")))));
        StatementFile second = ingest.ingest(upload(PSP, "STMT-" + id + "-B",
                psp(List.of(pspLine(id + "-1").replace("B-001", "B-002")))));

        StatementFile third = ingest.ingest(upload(PSP, "STMT-" + id + "-C",
                psp(List.of(pspLine(id + "-1").replace("B-001", "B-003")))));

        DuplicateLine again = third.lines().duplicates().getFirst();
        assertThat(again.breakOpened()).isFalse();
        assertThat(again.breakId()).isEqualTo(second.lines().duplicates().getFirst().breakId());
        assertThat(breaks.eventsOf(again.breakId())).hasSize(1);
    }

    @Test
    @DisplayName("FR-ING-4: a bank line repeated from another file opens a break on the bank side")
    void bankLineRepeatedFromAnotherFile() throws IOException {
        String id = unique();
        ingest.ingest(upload(BANK, "STMT-" + id + "-A", bank(List.of(bankLine(id + "-1")))));

        StatementFile second = ingest.ingest(upload(BANK, "STMT-" + id + "-B",
                bank(List.of(bankLine(id + "-1").replace("245.00", "246.00")))));

        assertThat(breaks.findById(second.lines().duplicates().getFirst().breakId()))
                .hasValueSatisfying(opened -> assertThat(opened.item().side()).isEqualTo(ItemSide.BANK));
    }

    @Test
    @DisplayName("DUPLICATE_LINE_IN_FILE: a line id repeated within one file is a line error on the repetition")
    void lineRepeatedWithinTheFile() throws IOException {
        String id = unique();

        StatementFile file = ingest.ingest(upload(PSP, "STMT-" + id,
                psp(List.of(pspLine(id + "-1"), pspLine(id + "-2"), pspLine(id + "-1")))));

        assertThat(file.status()).isEqualTo(StatementFileStatus.REJECTED);
        assertThat(file.lines().errors()).containsExactly(new LineError(4, ValidationCode.DUPLICATE_LINE_IN_FILE));
        assertThat(linesOf("psp_lines", file)).isZero();
    }

    @Test
    @DisplayName("DUPLICATE_LINE_IN_FILE: repeated within the file after repeating another file's line, it is still in-file")
    void repeatWithinFileOfALineFromAnotherFile() throws IOException {
        String id = unique();
        ingest.ingest(upload(PSP, "STMT-" + id + "-A", psp(List.of(pspLine(id + "-1")))));

        StatementFile file = ingest.ingest(upload(PSP, "STMT-" + id + "-B",
                psp(List.of(pspLine(id + "-1"), pspLine(id + "-1").replace("B-001", "B-009")))));

        assertThat(file.status()).isEqualTo(StatementFileStatus.REJECTED);
        assertThat(file.lines().errors()).containsExactly(new LineError(3, ValidationCode.DUPLICATE_LINE_IN_FILE));
        assertThat(file.lines().duplicateLineCount()).isZero();
    }

    @Test
    @DisplayName("FR-ING-5: counts are complete and the listed errors are the first 1,000, in line order")
    void manyInvalidLines() throws IOException {
        String id = unique();
        // 1,500 lines alternating valid and invalid across batch boundaries: the errors come back in order.
        List<String> lines = IntStream.rangeClosed(1, 1_500)
                .mapToObj(n -> n % 2 == 0 ? pspLine(id + "-" + n).replace("TRY", "ABC") : pspLine(id + "-" + n))
                .toList();

        StatementFile file = ingest.ingest(upload(PSP, "STMT-" + id, psp(lines)));

        assertThat(file.status()).isEqualTo(StatementFileStatus.REJECTED);
        assertThat(file.lines().lineCount()).isEqualTo(1_500);
        assertThat(file.lines().invalidLineCount()).isEqualTo(750);
        assertThat(file.lines().errors()).hasSize(750).extracting(LineError::lineNumber).isSorted();
        assertThat(file.lines().errors().getFirst()).isEqualTo(new LineError(3, ValidationCode.INVALID_CURRENCY));
    }

    @Test
    @DisplayName("FR-ING-8: more data lines than the limit refuses the file, and nothing is recorded")
    void tooManyLinesRecordsNothing() {
        String id = unique();
        List<String> lines = IntStream.rangeClosed(1, 2_001).mapToObj(n -> pspLine(id + "-" + n)).toList();
        long files = count("statement_files");

        assertThatThrownBy(() -> ingest.ingest(upload(PSP, "STMT-" + id, psp(lines))))
                .isInstanceOf(TooManyLinesException.class);
        assertThat(count("statement_files")).isEqualTo(files);
        assertThat(jdbc.sql("SELECT count(*) FROM psp_lines WHERE line_id LIKE :prefix").param("prefix", id + "%")
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("TDD 7.3: a line in a real ISO currency outside the supported set is ingested")
    void unsupportedIsoCurrencyIsIngested() throws IOException {
        String id = unique();

        StatementFile file = ingest.ingest(upload(PSP, "STMT-" + id, psp(List.of(pspLine(id + "-1").replace("TRY", "EUR")))));

        assertThat(file.status()).isEqualTo(StatementFileStatus.INGESTED);
    }

    @Test
    @DisplayName("FR-ING-1: an unknown or malformed source, and a malformed statement reference, are refused before reading")
    void refusedBeforeReading() {
        String content = psp(List.of(pspLine(unique())));

        assertThatThrownBy(() -> ingest.ingest(upload("PSP_NOWHERE", "STMT-1", content)))
                .isInstanceOfSatisfying(UploadRefusedException.class,
                        refused -> assertThat(refused.reason()).isEqualTo(UploadRefusedException.Reason.UNKNOWN_SOURCE));
        assertThatThrownBy(() -> ingest.ingest(upload("psp ingest", "STMT-1", content)))
                .isInstanceOfSatisfying(UploadRefusedException.class,
                        refused -> assertThat(refused.reason()).isEqualTo(UploadRefusedException.Reason.UNKNOWN_SOURCE));
        assertThatThrownBy(() -> ingest.ingest(upload(null, "STMT-1", content)))
                .isInstanceOf(UploadRefusedException.class);
        for (String reference : new String[] {null, "", "STMT 1", "../STMT", "x".repeat(101)}) {
            assertThatThrownBy(() -> ingest.ingest(upload(PSP, reference, content)))
                    .isInstanceOfSatisfying(UploadRefusedException.class, refused -> assertThat(refused.reason())
                            .isEqualTo(UploadRefusedException.Reason.INVALID_STATEMENT_REFERENCE));
        }
    }

    @Test
    @DisplayName("FR-ING-9: the stored file name is the sanitized one")
    void storedNameIsSanitized() throws IOException {
        String id = unique();
        byte[] bytes = psp(List.of(pspLine(id + "-1"))).getBytes(StandardCharsets.UTF_8);

        StatementFile file = ingest.ingest(new UploadedStatement(PSP, "STMT-" + id, "..\\..\\evil name;.csv",
                () -> new ByteArrayInputStream(bytes), "operator-001"));

        assertThat(file.sanitizedFilename()).isEqualTo("evil_name_.csv");
    }

    private long linesOf(String table, StatementFile file) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE file_id = :id").param("id", file.id())
                .query(Long.class).single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
