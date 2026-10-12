package com.baran.recon.adapters.out.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;

import com.baran.recon.domain.item.BatchKey;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SQL batch item id equals {@code BatchKey.itemId()} (TDD 8.2, v1.14; Milestone 6 exit) for every
 * source code and batch id the columns' CHECKs allow: a source code of 1-64 of {@code [A-Z0-9_]}
 * starting with a letter, and a batch id of 1-64 of {@code [A-Za-z0-9_-]}. {@link SourceCode} and
 * {@link BatchKey} refuse anything else, so each generated pair is one the schema can hold. The
 * expression is evaluated as recon_app, as the application's statements will run it, on a
 * throwaway database; it reads no table.
 */
@Label("TDD 8.2: the SQL batch item id is BatchKey.itemId()")
class BatchItemIdSqlPropertiesTest {

    private static final String SEED = "20261012";
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITS = "0123456789";

    private static final String SELECT_ID = "SELECT " + BatchItemIdSql.of("batch.source_code", "batch.batch_id")
            + " FROM (SELECT CAST(? AS TEXT) AS source_code, CAST(? AS TEXT) AS batch_id) AS batch";

    private static ReconPostgres database;
    private static Connection connection;

    @BeforeContainer
    static void startDatabase() throws SQLException {
        database = ReconPostgres.throwaway();
        connection = database.connectAsApp();
    }

    @AfterContainer
    static void stopDatabase() throws SQLException {
        try {
            connection.close();
        } finally {
            database.close();
        }
    }

    @Property(tries = 2000, seed = SEED)
    @Label("for every source code and batch id the CHECKs allow, the SQL id is BatchKey.itemId()")
    void sqlIdIsTheJavaId(@ForAll("batches") BatchKey batch) throws SQLException {
        assertThat(sqlId(SELECT_ID, batch)).isEqualTo(batch.itemId());
    }

    /**
     * The break proof of the property: an expression that leaves out the version and variant bits,
     * the part that is easiest to get wrong, gives another id, so the comparison above would report it.
     */
    @Example
    @Label("break proof: the bare MD5 as a UUID, without the version and variant bits, is not BatchKey.itemId()")
    void bareMd5IsNotTheJavaId() throws SQLException {
        BatchKey batch = new BatchKey(SourceCode.of("PSP_ALPHA"), "B-001");
        String bareMd5 = "SELECT CAST(md5('recon-batch/' || CAST(? AS TEXT) || '/' || CAST(? AS TEXT)) AS UUID)";

        assertThat(sqlId(bareMd5, batch)).isNotEqualTo(batch.itemId());
        assertThat(sqlId(SELECT_ID, batch)).isEqualTo(batch.itemId());
    }

    @Provide
    Arbitrary<BatchKey> batches() {
        Arbitrary<String> sources = Combinators.combine(
                        Arbitraries.strings().withChars(UPPER).ofLength(1),
                        Arbitraries.strings().withChars(UPPER + DIGITS + "_").ofMinLength(0).ofMaxLength(63))
                .as(String::concat);
        Arbitrary<String> batchIds = Arbitraries.strings()
                .withChars(UPPER + UPPER.toLowerCase() + DIGITS + "_-").ofMinLength(1).ofMaxLength(64);
        return Combinators.combine(sources, batchIds).as((source, batchId) -> new BatchKey(SourceCode.of(source), batchId));
    }

    private static UUID sqlId(String sql, BatchKey batch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, batch.source().value());
            statement.setString(2, batch.batchId());
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }
}
