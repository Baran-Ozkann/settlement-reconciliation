package com.baran.recon.adapters.out.persistence;

import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.adapters.out.persistence.Row.text;
import static com.baran.recon.adapters.out.persistence.Rows.BREAK;
import static com.baran.recon.adapters.out.persistence.Rows.LEDGER_ENTRY;
import static com.baran.recon.adapters.out.persistence.Rows.MATCH;
import static com.baran.recon.adapters.out.persistence.Rows.MATCH_ITEM;
import static com.baran.recon.adapters.out.persistence.Rows.RESOLVED_BREAK;
import static com.baran.recon.adapters.out.persistence.Rows.RUN;
import static com.baran.recon.adapters.out.persistence.Rows.SECOND_MATCH;
import static com.baran.recon.adapters.out.persistence.Rows.STATEMENT_FILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The other half of each partial unique index. {@code DatabaseMechanismTest} shows each refuses its
 * violation; this shows what each deliberately lets through, because an index that refused too
 * much - a reversed match that still held its items, a resolved break that blocked the next one -
 * would be as wrong as one that refused too little.
 */
@DisplayName("Partial unique indexes refuse the duplicate and allow what their condition leaves out")
class PartialUniqueIndexesTest {

    private static final String UNIQUE_VIOLATION = "23505";

    private static ReconPostgres database;

    @BeforeAll
    static void startDatabase() {
        database = ReconPostgres.throwaway();
    }

    @AfterAll
    static void stopDatabase() {
        database.close();
    }

    @Test
    @DisplayName("INV-2: an item in an active match cannot join a second active match")
    void itemInOneActiveMatchOnly() throws SQLException {
        RolledBackTransaction.run(database, jdbc -> {
            insert(jdbc, RUN, MATCH, SECOND_MATCH, MATCH_ITEM);

            assertRefusedBy(jdbc, MATCH_ITEM.with("match_id", SECOND_MATCH.literal("id")).insert(),
                    "match_items_active_item_unique");
        });
    }

    @Test
    @DisplayName("INV-2, FR-MAT-7: once its match is reversed, the item can be matched again")
    void reversedMatchReleasesItsItems() throws SQLException {
        RolledBackTransaction.run(database, jdbc -> {
            insert(jdbc, RUN, MATCH.with("status", text("REVERSED")), SECOND_MATCH,
                    MATCH_ITEM.with("active", "false"));

            assertThatCode(() -> jdbc.execute(MATCH_ITEM.with("match_id", SECOND_MATCH.literal("id")).insert()))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("INV-7, FR-BRK-2: an item cannot have a second break while one is unresolved")
    void oneUnresolvedBreakPerItem() throws SQLException {
        RolledBackTransaction.run(database, jdbc -> {
            insert(jdbc, RUN, BREAK.with("status", text("INVESTIGATING")));

            assertRefusedBy(jdbc, BREAK.with("id", text("b0000000-0000-4000-8000-000000000002")).insert(),
                    "breaks_one_unresolved_per_item");
        });
    }

    @Test
    @DisplayName("INV-7, FR-BRK-3: after its break is resolved, the item can have a new one")
    void resolvedBreakAllowsANewOne() throws SQLException {
        RolledBackTransaction.run(database, jdbc -> {
            insert(jdbc, RUN, RESOLVED_BREAK);

            assertThatCode(() -> jdbc.execute(BREAK.with("id", text("b0000000-0000-4000-8000-000000000002"))
                    .with("previous_break_id", RESOLVED_BREAK.literal("id")).insert()))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("FR-LED-7: any number of five-field entries, none with an entry id, can be stored")
    void fiveFieldEntriesShareTheAbsentEntryId() throws SQLException {
        RolledBackTransaction.run(database, jdbc -> {
            Row fiveField = LEDGER_ENTRY.with("ledger_entry_id", "NULL").with("created_at", "NULL")
                    .with("value_date", "NULL");
            jdbc.execute(fiveField.insert());

            assertThatCode(() -> jdbc.execute(fiveField.with("id", text("1e000000-0000-4000-8000-000000000002"))
                    .with("event_id", "2").insert()))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("FR-LED-8: one ledger entry under a second event-id is refused, not skipped")
    void sameEntryIdUnderAnotherEventIsRefused() throws SQLException {
        RolledBackTransaction.run(database, jdbc -> {
            jdbc.execute(LEDGER_ENTRY.insert());

            assertRefusedBy(jdbc, LEDGER_ENTRY.with("id", text("1e000000-0000-4000-8000-000000000002"))
                    .with("event_id", "2").insert(), "ledger_entries_ledger_entry_id_unique");
        });
    }

    @Test
    @DisplayName("FR-ING-3: a rejected file does not block uploading the same file again")
    void rejectedFileCanBeUploadedAgain() throws SQLException {
        RolledBackTransaction.run(database, jdbc -> {
            jdbc.execute(STATEMENT_FILE.with("status", text("REJECTED"))
                    .with("error_summary", text("[{\"line\": 2, \"code\": \"INVALID_AMOUNT\"}]")).insert());

            assertThatCode(() -> jdbc.execute(STATEMENT_FILE.with("id", text("0a000000-0000-4000-8000-000000000002"))
                    .insert()))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("FR-ING-3: a file already ingested cannot be ingested again")
    void ingestedFileCannotBeIngestedAgain() throws SQLException {
        RolledBackTransaction.run(database, jdbc -> {
            jdbc.execute(STATEMENT_FILE.insert());

            assertRefusedBy(jdbc, STATEMENT_FILE.with("id", text("0a000000-0000-4000-8000-000000000002"))
                    .with("statement_reference", text("STMT-2")).insert(), "statement_files_sha256_ingested_unique");
        });
    }

    private static void insert(Statement jdbc, Row... rows) throws SQLException {
        for (Row row : rows) {
            jdbc.execute(row.insert());
        }
    }

    private static void assertRefusedBy(Statement jdbc, String statement, String index) {
        assertThatThrownBy(() -> jdbc.execute(statement))
                .isInstanceOf(PSQLException.class)
                .satisfies(e -> {
                    PSQLException refusal = (PSQLException) e;
                    assertThat(refusal.getSQLState()).isEqualTo(UNIQUE_VIOLATION);
                    assertThat(refusal.getServerErrorMessage().getConstraint()).isEqualTo(index);
                });
    }
}
