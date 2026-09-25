package com.baran.recon.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.baran.recon.application.port.DuplicateLedgerEntryException;
import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.domain.item.LedgerEntry;
import com.baran.recon.domain.item.SourceCode;

import static com.baran.recon.adapters.out.persistence.SqlValues.currency;
import static com.baran.recon.adapters.out.persistence.SqlValues.instant;
import static com.baran.recon.adapters.out.persistence.SqlValues.money;
import static com.baran.recon.adapters.out.persistence.SqlValues.optionalDate;
import static com.baran.recon.adapters.out.persistence.SqlValues.optionalInstant;
import static com.baran.recon.adapters.out.persistence.SqlValues.timestamp;
import static com.baran.recon.adapters.out.persistence.SqlValues.timestampOrNull;
import static com.baran.recon.adapters.out.persistence.SqlValues.uuid;

@Repository
class JdbcLedgerEntryStore implements LedgerEntryStore {

    private static final String ENTRY_ID_INDEX = "ledger_entries_ledger_entry_id_unique";

    /**
     * The conflict target is event_id alone. A redelivered event is skipped; the same ledger entry
     * under a new event id still violates ledger_entries_ledger_entry_id_unique and fails, as
     * FR-LED-8 requires. An untargeted DO NOTHING would swallow that as well.
     */
    private static final String INSERT = """
            INSERT INTO ledger_entries (id, event_id, ledger_entry_id, transaction_id, account_id, source_code,
                                        amount, currency, tx_type, created_at, value_date, received_at)
            VALUES (:id, :eventId, :ledgerEntryId, :transactionId, :accountId, :sourceCode,
                    :amount, :currency, :txType, :createdAt, :valueDate, :receivedAt)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String SELECT_BY_ID = """
            SELECT id, event_id, ledger_entry_id, transaction_id, account_id, source_code,
                   amount, currency, tx_type, created_at, value_date, received_at
              FROM ledger_entries
             WHERE id = :id
            """;

    private final JdbcClient jdbc;

    JdbcLedgerEntryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean storeIfAbsent(LedgerEntry entry) {
        try {
            return jdbc.sql(INSERT)
                    .param("id", entry.id())
                    .param("eventId", entry.eventId())
                    .param("ledgerEntryId", entry.ledgerEntryId().orElse(null), Types.BIGINT)
                    .param("transactionId", entry.transactionId())
                    .param("accountId", entry.accountId())
                    .param("sourceCode", entry.source().value())
                    .param("amount", entry.amount().minorUnits())
                    .param("currency", entry.amount().currency().code())
                    .param("txType", entry.txType())
                    .param("createdAt", timestampOrNull(entry.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("valueDate", entry.valueDate().orElse(null), Types.DATE)
                    .param("receivedAt", timestamp(entry.receivedAt()))
                    .update() == 1;
        } catch (DuplicateKeyException duplicate) {
            if (PostgresErrors.violatedConstraint(duplicate).filter(ENTRY_ID_INDEX::equals).isPresent()) {
                throw new DuplicateLedgerEntryException(entry.ledgerEntryId().orElseThrow(), duplicate);
            }
            throw duplicate;
        }
    }

    @Override
    public Optional<LedgerEntry> findById(UUID id) {
        return jdbc.sql(SELECT_BY_ID).param("id", id).query(JdbcLedgerEntryStore::map).optional();
    }

    private static LedgerEntry map(ResultSet row, int rowNumber) throws SQLException {
        long ledgerEntryId = row.getLong("ledger_entry_id");
        Optional<Long> entryId = row.wasNull() ? Optional.empty() : Optional.of(ledgerEntryId);
        return new LedgerEntry(
                uuid(row, "id"),
                row.getLong("event_id"),
                entryId,
                uuid(row, "transaction_id"),
                uuid(row, "account_id"),
                SourceCode.of(row.getString("source_code")),
                money(row, "amount", currency(row, "currency")),
                row.getString("tx_type"),
                optionalInstant(row, "created_at"),
                optionalDate(row, "value_date"),
                instant(row, "received_at"));
    }
}
