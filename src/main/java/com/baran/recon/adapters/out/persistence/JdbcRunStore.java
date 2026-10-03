package com.baran.recon.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.baran.recon.application.port.RunAlreadyRunningException;
import com.baran.recon.application.port.RunNotRunningException;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;

import static com.baran.recon.adapters.out.persistence.SqlValues.date;
import static com.baran.recon.adapters.out.persistence.SqlValues.instant;
import static com.baran.recon.adapters.out.persistence.SqlValues.optionalInstant;
import static com.baran.recon.adapters.out.persistence.SqlValues.timestamp;
import static com.baran.recon.adapters.out.persistence.SqlValues.timestampOrNull;
import static com.baran.recon.adapters.out.persistence.SqlValues.uuid;

@Repository
class JdbcRunStore implements RunStore {

    private static final String ONE_RUNNING_PER_SOURCE_INDEX = "reconciliation_runs_one_running_per_source";

    private static final String INSERT = """
            INSERT INTO reconciliation_runs (id, source_code, value_date_from, value_date_to, status,
                                             config_snapshot, stats, started_at, finished_at, triggered_by)
            VALUES (:id, :sourceCode, :valueDateFrom, :valueDateTo, :status,
                    CAST(:configSnapshot AS JSONB), CAST(:stats AS JSONB), :startedAt, :finishedAt, :triggeredBy)
            """;

    private static final String SELECT_BY_ID = """
            SELECT id, source_code, value_date_from, value_date_to, status, config_snapshot, stats,
                   started_at, finished_at, triggered_by
              FROM reconciliation_runs
             WHERE id = :id
            """;

    private static final String SELECT_RUNNING = """
            SELECT id
              FROM reconciliation_runs
             WHERE source_code = :sourceCode AND status = 'RUNNING'
            """;

    /** The status condition makes a run's outcome final: whoever finishes it first is kept. */
    private static final String UPDATE_OUTCOME = """
            UPDATE reconciliation_runs
               SET status = :status, stats = CAST(:stats AS JSONB), finished_at = :finishedAt
             WHERE id = :id AND status = 'RUNNING'
            """;

    /**
     * A run left RUNNING was started by another JVM, whose clock may have been ahead of this one; a
     * run never finishes before it started (reconciliation_runs_finished_after_start).
     */
    private static final String FAIL_ALL_RUNNING = """
            UPDATE reconciliation_runs
               SET status = 'FAILED', finished_at = GREATEST(:finishedAt, started_at)
             WHERE status = 'RUNNING'
            RETURNING id
            """;

    private static final TypeReference<TreeMap<String, String>> CONFIG = new TypeReference<>() {
    };
    private static final TypeReference<TreeMap<String, Long>> STATS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcRunStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(ReconciliationRun run) {
        try {
            insertRow(run);
        } catch (DuplicateKeyException duplicate) {
            if (PostgresErrors.violatedConstraint(duplicate).filter(ONE_RUNNING_PER_SOURCE_INDEX::equals).isPresent()) {
                throw new RunAlreadyRunningException(run.source(), duplicate);
            }
            throw duplicate;
        }
    }

    private void insertRow(ReconciliationRun run) {
        jdbc.sql(INSERT)
                .param("id", run.id())
                .param("sourceCode", run.source().value())
                .param("valueDateFrom", run.valueDateFrom())
                .param("valueDateTo", run.valueDateTo())
                .param("status", run.status().name())
                .param("configSnapshot", json.writeValueAsString(run.configSnapshot()))
                .param("stats", run.stats().map(json::writeValueAsString).orElse(null), Types.VARCHAR)
                .param("startedAt", timestamp(run.startedAt()))
                .param("finishedAt", timestampOrNull(run.finishedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("triggeredBy", run.triggeredBy())
                .update();
    }

    @Override
    public Optional<ReconciliationRun> findById(UUID id) {
        return jdbc.sql(SELECT_BY_ID).param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<UUID> findRunning(SourceCode source) {
        return jdbc.sql(SELECT_RUNNING).param("sourceCode", source.value()).query(UUID.class).optional();
    }

    @Override
    public void recordOutcome(ReconciliationRun finished) {
        if (finished.status() == RunStatus.RUNNING) {
            throw new IllegalArgumentException("run " + finished.id() + " has not finished");
        }
        int updated = jdbc.sql(UPDATE_OUTCOME)
                .param("id", finished.id())
                .param("status", finished.status().name())
                .param("stats", finished.stats().map(json::writeValueAsString).orElse(null), Types.VARCHAR)
                .param("finishedAt", timestampOrNull(finished.finishedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        if (updated == 0) {
            throw new RunNotRunningException(finished.id());
        }
    }

    @Override
    public List<UUID> failAllRunning(Instant finishedAt) {
        return jdbc.sql(FAIL_ALL_RUNNING).param("finishedAt", timestamp(finishedAt)).query(UUID.class).list();
    }

    private ReconciliationRun map(ResultSet row, int rowNumber) throws SQLException {
        String stats = row.getString("stats");
        return new ReconciliationRun(
                uuid(row, "id"),
                SourceCode.of(row.getString("source_code")),
                date(row, "value_date_from"),
                date(row, "value_date_to"),
                RunStatus.valueOf(row.getString("status")),
                json.readValue(row.getString("config_snapshot"), CONFIG),
                Optional.ofNullable(stats).map(text -> (SortedMap<String, Long>) json.readValue(text, STATS)),
                instant(row, "started_at"),
                optionalInstant(row, "finished_at"),
                row.getString("triggered_by"));
    }
}
