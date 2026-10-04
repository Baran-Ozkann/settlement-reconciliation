package com.baran.recon.application.statement;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.RunTrigger;
import com.baran.recon.application.port.RunTrigger.IngestedFile;
import com.baran.recon.application.port.TooManyLinesException;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.statement.StatementFile;
import com.baran.recon.domain.statement.StatementFileStatus;
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
 * What the ingestion hands the run trigger (FR-MAT-1): an INGESTED file's id, its source and the
 * value dates of the lines it stored, once the file has committed, and nothing for any other outcome.
 * The trigger here only records; what it starts is tested with the application in RunAfterUploadTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_TRIGGER_RANGE",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[1].code=BANK_TRIGGER_RANGE",
        "recon.sources[1].type=BANK_STATEMENT",
        "recon.sources[1].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
        "recon.ingestion.max-lines=5"})
@ActiveProfiles("test")
@Import(IngestionRunTriggerTest.Recording.class)
@DisplayName("FR-MAT-1: an ingested file hands its source and stored value dates to the run trigger")
class IngestionRunTriggerTest {

    /** Sources no other test class uses; the shared database outlives each class. */
    private static final String PSP = "PSP_TRIGGER_RANGE";
    private static final String BANK = "BANK_TRIGGER_RANGE";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private IngestStatement ingest;

    @Autowired
    private RecordingRunTrigger trigger;

    @BeforeEach
    void forgetEarlierFiles() {
        trigger.files.clear();
    }

    @Test
    @DisplayName("a PSP file gives its id, its source and the earliest and latest value dates of its lines")
    void pspFileGivesItsValueDates() throws IOException {
        String id = unique();

        StatementFile file = ingest.ingest(upload(PSP, "STMT-" + id, psp(
                pspLine(id + "-1", "2026-09-23"), pspLine(id + "-2", "2026-09-21"), pspLine(id + "-3", "2026-09-25"))));

        assertThat(file.status()).isEqualTo(StatementFileStatus.INGESTED);
        assertThat(trigger.files).containsExactly(new IngestedFile(file.id(), SourceCode.of(PSP),
                LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 25)));
    }

    @Test
    @DisplayName("a bank file gives the value dates of its lines too")
    void bankFileGivesItsValueDates() throws IOException {
        String id = unique();

        StatementFile file = ingest.ingest(upload(BANK, "STMT-" + id,
                bank(List.of(bankLine(id + "-1", "2026-09-29"), bankLine(id + "-2", "2026-09-25")))));

        assertThat(trigger.files).containsExactly(new IngestedFile(file.id(), SourceCode.of(BANK),
                LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 29)));
    }

    @Test
    @DisplayName("FR-ING-4: a line repeated from another file is not stored, so its value date does not count")
    void repeatedLinesDoNotCount() throws IOException {
        String id = unique();
        ingest.ingest(upload(PSP, "STMT-" + id + "-A", psp(pspLine(id + "-1", "2026-09-10"))));
        trigger.files.clear();

        StatementFile second = ingest.ingest(upload(PSP, "STMT-" + id + "-B", psp(
                pspLine(id + "-1", "2026-09-10"), pspLine(id + "-2", "2026-09-17"))));

        assertThat(second.lines().duplicateLineCount()).isOne();
        assertThat(trigger.files).containsExactly(new IngestedFile(second.id(), SourceCode.of(PSP),
                LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 17)));
    }

    @Test
    @DisplayName("an ingested file that stored no line, every line repeating another file's, triggers nothing")
    void fileThatStoredNoLineTriggersNothing() throws IOException {
        String id = unique();
        ingest.ingest(upload(PSP, "STMT-" + id + "-A", psp(pspLine(id + "-1", "2026-09-10"))));
        trigger.files.clear();

        StatementFile repeated = ingest.ingest(upload(PSP, "STMT-" + id + "-B", psp(pspLine(id + "-1", "2026-09-11"))));

        assertThat(repeated.status()).isEqualTo(StatementFileStatus.INGESTED);
        assertThat(trigger.files).isEmpty();
    }

    @Test
    @DisplayName("FR-ING-7: a rejected file triggers nothing")
    void rejectedFileTriggersNothing() throws IOException {
        String id = unique();

        StatementFile rejected = ingest.ingest(upload(PSP, "STMT-" + id, psp(
                pspLine(id + "-1", "2026-09-21"), pspLine(id + "-2", "2026-09-22").replace(",TRY", ",ABC"))));

        assertThat(rejected.status()).isEqualTo(StatementFileStatus.REJECTED);
        assertThat(trigger.files).isEmpty();
    }

    @Test
    @DisplayName("FR-ING-3, FR-ING-8: a refused upload triggers nothing")
    void refusedUploadTriggersNothing() throws IOException {
        String id = unique();
        String content = psp(pspLine(id + "-1", "2026-09-21"));
        ingest.ingest(upload(PSP, "STMT-" + id, content));
        trigger.files.clear();

        assertThatThrownBy(() -> ingest.ingest(upload(PSP, "STMT-" + id + "-AGAIN", content)))
                .isInstanceOf(UploadRefusedException.class);
        List<String> tooMany = List.of("-a", "-b", "-c", "-d", "-e", "-f").stream()
                .map(suffix -> pspLine(id + suffix, "2026-09-21")).toList();
        assertThatThrownBy(() -> ingest.ingest(upload(PSP, "STMT-" + id + "-MANY", psp(tooMany.toArray(String[]::new)))))
                .isInstanceOf(TooManyLinesException.class);

        assertThat(trigger.files).isEmpty();
    }

    /** Records what the ingestion hands on, in place of the application's trigger. */
    static final class RecordingRunTrigger implements RunTrigger {

        private final List<IngestedFile> files = new CopyOnWriteArrayList<>();

        @Override
        public void fileIngested(IngestedFile file) {
            files.add(file);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Recording {

        @Bean
        @Primary
        RecordingRunTrigger recordingRunTrigger() {
            return new RecordingRunTrigger();
        }
    }
}
