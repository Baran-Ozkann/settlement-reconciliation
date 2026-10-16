package com.baran.recon.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.run.ItemStatus;
import com.baran.recon.domain.run.ItemTotal;

/** Conversions between column values and domain values, in one place for every repository. */
final class SqlValues {

    private SqlValues() {
    }

    /** TIMESTAMPTZ goes over the wire as an offset date-time; UTC keeps the stored instant exact. */
    static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static OffsetDateTime timestampOrNull(Optional<Instant> instant) {
        return instant.map(SqlValues::timestamp).orElse(null);
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    static Optional<Instant> optionalInstant(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, OffsetDateTime.class)).map(OffsetDateTime::toInstant);
    }

    static LocalDate date(ResultSet row, String column) throws SQLException {
        return row.getObject(column, LocalDate.class);
    }

    static Optional<LocalDate> optionalDate(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, LocalDate.class));
    }

    static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    static Optional<UUID> optionalUuid(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, UUID.class));
    }

    static Optional<String> optionalText(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getString(column));
    }

    static OptionalInt optionalInt(ResultSet row, String column) throws SQLException {
        Integer value = row.getObject(column, Integer.class);
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    static Integer integerOrNull(OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : null;
    }

    static Money money(ResultSet row, String amountColumn, CurrencyCode currency) throws SQLException {
        return Money.of(row.getLong(amountColumn), currency);
    }

    static CurrencyCode currency(ResultSet row, String column) throws SQLException {
        return CurrencyCode.of(row.getString(column));
    }

    /**
     * A row of a run's item totals: its side, currency, status (NULL for the total of every
     * status), number of items and amount.
     */
    static ItemTotal itemTotal(ResultSet row, int rowNumber) throws SQLException {
        return new ItemTotal(ItemSide.valueOf(row.getString("side")), CurrencyCode.of(row.getString("currency")),
                Optional.ofNullable(row.getString("status")).map(ItemStatus::valueOf),
                row.getLong("items"), row.getLong("amount"));
    }
}
