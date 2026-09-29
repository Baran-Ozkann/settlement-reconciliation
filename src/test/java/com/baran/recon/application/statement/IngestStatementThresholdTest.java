package com.baran.recon.application.statement;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.domain.statement.LineError;
import com.baran.recon.domain.statement.StatementFile;
import com.baran.recon.domain.statement.StatementFileStatus;
import com.baran.recon.domain.statement.ValidationCode;
import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.application.statement.StatementFiles.psp;
import static com.baran.recon.application.statement.StatementFiles.pspLine;
import static com.baran.recon.application.statement.StatementFiles.unique;
import static com.baran.recon.application.statement.StatementFiles.upload;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-ING-7 with a threshold above 0, set in this context's properties: a third of a file's lines may
 * be invalid. Below it the valid lines are ingested and the invalid ones listed; above it the file is
 * rejected as with any other threshold.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_THRESHOLD",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.ingestion.max-invalid-line-ratio-bp=3334"})
@ActiveProfiles("test")
@DisplayName("FR-ING-7: under a configured invalid-line threshold, a file with a few invalid lines is ingested")
class IngestStatementThresholdTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private IngestStatement ingest;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("FR-ING-7: one invalid line of three is within 33.34 %: ingested, the two valid lines stored, the error listed")
    void withinTheThresholdIsIngested() throws IOException {
        String id = unique();

        StatementFile file = ingest.ingest(upload("PSP_THRESHOLD", "STMT-" + id,
                psp(List.of(pspLine(id + "-1"), pspLine(id + "-2").replace("125.00", "125.001"), pspLine(id + "-3")))));

        assertThat(file.status()).isEqualTo(StatementFileStatus.INGESTED);
        assertThat(file.lines().errors()).containsExactly(new LineError(3, ValidationCode.SCALE_EXCEEDS_CURRENCY));
        assertThat(file.lines().storedLineCount()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT line_id FROM psp_lines WHERE file_id = :id ORDER BY line_id").param("id", file.id())
                .query(String.class).list()).containsExactly(id + "-1", id + "-3");
    }

    @Test
    @DisplayName("FR-ING-7: two invalid lines of three exceed it: rejected, nothing stored")
    void overTheThresholdIsRejected() throws IOException {
        String id = unique();

        StatementFile file = ingest.ingest(upload("PSP_THRESHOLD", "STMT-" + id,
                psp(List.of(pspLine(id + "-1").replace("TRY", "XYZ"), pspLine(id + "-2").replace("TRY", "XYZ"),
                        pspLine(id + "-3")))));

        assertThat(file.status()).isEqualTo(StatementFileStatus.REJECTED);
        assertThat(jdbc.sql("SELECT count(*) FROM psp_lines WHERE file_id = :id").param("id", file.id())
                .query(Long.class).single()).isZero();
    }
}
