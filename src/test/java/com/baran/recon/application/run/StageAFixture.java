package com.baran.recon.application.run;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.breaks.Break;
import com.baran.recon.domain.breaks.BreakType;
import com.baran.recon.domain.item.ItemRef;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.item.LedgerEntry;
import com.baran.recon.domain.item.PspLine;
import com.baran.recon.domain.item.PspLineType;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.statement.LineSummary;
import com.baran.recon.domain.statement.StatementFile;
import com.baran.recon.domain.statement.StatementFileStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Synthetic ledger entries and PSP lines for the Stage A tests, written through the application's
 * own stores as recon_app, and the run's results read back by the keys a person would use: a PSP
 * line by its line id, a ledger entry by its event id. Generated ids are never compared, so a test
 * holds whatever order rows were stored or read in (FR-MAT-5).
 *
 * <p>Each test class passes an event-id range no other class uses, and every insert is asserted to
 * have written its row, so a key collision fails at the fixture (CLAUDE.md 7.3).
 */
final class StageAFixture {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    private final LedgerEntryStore ledgerEntries;
    private final StatementStore statements;
    private final Transactions transactions;
    private final JdbcClient jdbc;
    private final AtomicLong eventIds;

    StageAFixture(LedgerEntryStore ledgerEntries, StatementStore statements, Transactions transactions,
                  JdbcClient jdbc, long firstEventId) {
        this.ledgerEntries = ledgerEntries;
        this.statements = statements;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.eventIds = new AtomicLong(firstEventId);
    }

    /** A dated ledger entry; returns its key, {@code LEDGER <event id>}. */
    String ledger(SourceCode source, UUID transactionId, long amount, String currency, LocalDate valueDate) {
        long eventId = eventIds.incrementAndGet();
        Instant createdAt = valueDate.atStartOfDay(ISTANBUL).plusHours(10).toInstant();
        LedgerEntry entry = new LedgerEntry(UUID.randomUUID(), eventId, Optional.of(eventId), transactionId,
                UUID.randomUUID(), source, Money.of(amount, CurrencyCode.of(currency)), "TRANSFER",
                Optional.of(createdAt), Optional.of(valueDate), createdAt.plusSeconds(1));
        assertThat(ledgerEntries.storeIfAbsent(entry)).as("ledger entry with event id %s stored", eventId).isTrue();
        return "LEDGER " + eventId;
    }

    /** A five-field ledger entry, with no value date (FR-LED-7); returns its key. */
    String undatedLedger(SourceCode source, UUID transactionId, long amount, String currency) {
        long eventId = eventIds.incrementAndGet();
        LedgerEntry entry = new LedgerEntry(UUID.randomUUID(), eventId, Optional.empty(), transactionId,
                UUID.randomUUID(), source, Money.of(amount, CurrencyCode.of(currency)), "TRANSFER", Optional.empty(),
                Optional.empty(), Instant.parse("2026-09-24T08:15:01Z"));
        assertThat(ledgerEntries.storeIfAbsent(entry)).as("ledger entry with event id %s stored", eventId).isTrue();
        return "LEDGER " + eventId;
    }

    /** One PSP line in a file of its own; returns its key, {@code PSP <line id>}. */
    String psp(SourceCode source, String lineId, String reference, long gross, String currency, LocalDate valueDate) {
        return pspLines(source, List.of(new Psp(lineId, reference, gross, currency, valueDate))).getFirst();
    }

    /** PSP lines in one file, stored in the order given; returns their keys in that order. */
    List<String> pspLines(SourceCode source, List<Psp> lines) {
        UUID fileId = UUID.randomUUID();
        List<PspLine> stored = lines.stream().map(line -> line.toLine(fileId, source)).toList();
        StatementFile file = new StatementFile(fileId, source, "STMT-" + fileId, sha256(), "statement.csv", 100,
                StatementFileStatus.INGESTED, new LineSummary(Optional.empty(), lines.size(), 0, List.of(), 0, List.of()),
                "operator-001", Instant.parse("2026-10-12T08:00:00Z"));
        List<StatementStore.LineConflict> conflicts = transactions.inTransaction(() -> {
            statements.storeFile(file);
            return statements.storePspLinesIfAbsent(stored);
        });
        assertThat(conflicts).as("every PSP line stored").isEmpty();
        return lines.stream().map(line -> "PSP " + line.lineId()).toList();
    }

    /** The stored id of the source's PSP line. */
    UUID pspId(SourceCode source, String lineId) {
        return jdbc.sql("SELECT id FROM psp_lines WHERE source_code = :source AND line_id = :lineId")
                .param("source", source.value()).param("lineId", lineId).query(UUID.class).single();
    }

    /** The stored id of the ledger entry with this key. */
    UUID ledgerId(String key) {
        return jdbc.sql("SELECT id FROM ledger_entries WHERE event_id = :eventId")
                .param("eventId", Long.parseLong(key.substring("LEDGER ".length()))).query(UUID.class).single();
    }

    /** An OPEN break on the PSP line, opened by an operator rather than a run, as ingestion opens one. */
    UUID openBreak(BreakStore breaks, SourceCode source, String lineId, BreakType type) {
        UUID id = UUID.randomUUID();
        breaks.open(Break.open(id, type, new ItemRef(ItemSide.PSP, pspId(source, lineId)), List.of(), Optional.empty(),
                Actor.operator("operator-001"), Optional.of("Opened by the test"), Instant.parse("2026-10-12T08:00:00Z")));
        return id;
    }

    /** An operator starts investigating the break, as Phase 7's transition endpoint will. */
    void investigate(BreakStore breaks, UUID breakId) {
        breaks.apply(breaks.findById(breakId).orElseThrow().startInvestigation(Actor.operator("operator-001"),
                Optional.empty(), Instant.parse("2026-10-12T08:30:00Z")));
    }

    /** The reasons a break's events record, in order; none is {@code -}. */
    List<String> reasonsOf(UUID breakId) {
        return jdbc.sql("SELECT coalesce(reason, '-') FROM break_events WHERE break_id = :breakId ORDER BY id")
                .param("breakId", breakId).query(String.class).list();
    }

    /** The source's matches, active or not. */
    List<MatchView> matches(SourceCode source) {
        return jdbc.sql("""
                        SELECT m.rule_id, m.rule_version, m.cardinality, m.status, m.amount_difference, m.currency,
                               m.low_confidence, m.run_id, l.event_id, p.line_id,
                               (SELECT count(*) FROM match_events e
                                 WHERE e.match_id = m.id AND e.event_type = 'CREATED' AND e.actor = 'system') AS created
                          FROM matches m
                          JOIN match_items mp ON mp.match_id = m.id AND mp.side = 'PSP'
                          JOIN psp_lines p ON p.id = mp.item_id
                          JOIN match_items ml ON ml.match_id = m.id AND ml.side = 'LEDGER'
                          JOIN ledger_entries l ON l.id = ml.item_id
                         WHERE p.source_code = :source
                        """)
                .param("source", source.value())
                .query((row, n) -> new MatchView(row.getString("rule_id"), row.getInt("rule_version"),
                        row.getString("cardinality"), row.getString("status"), row.getLong("amount_difference"),
                        row.getString("currency"), row.getBoolean("low_confidence"), row.getObject("run_id", UUID.class),
                        "LEDGER " + row.getLong("event_id"), "PSP " + row.getString("line_id"), row.getLong("created")))
                .list();
    }

    /**
     * Every stored column of the source's matches, with their items and events, one line per match
     * in id order: what a later run must leave exactly as it was (FR-MAT-2).
     */
    List<String> matchRows(SourceCode source) {
        return jdbc.sql("""
                        SELECT concat_ws('|', m.id, m.run_id, m.rule_id, m.rule_version, m.cardinality, m.status,
                                         m.amount_difference, m.currency, m.low_confidence, m.created_at,
                                         (SELECT string_agg(i.side || ':' || i.item_id || ':' || i.active, ','
                                                            ORDER BY i.side, i.item_id)
                                            FROM match_items i WHERE i.match_id = m.id),
                                         (SELECT string_agg(e.event_type || ':' || e.actor || ':' || e.occurred_at, ','
                                                            ORDER BY e.id)
                                            FROM match_events e WHERE e.match_id = m.id))
                          FROM matches m
                         WHERE m.id IN (SELECT item.match_id FROM match_items item
                                          JOIN psp_lines line ON item.side = 'PSP' AND line.id = item.item_id
                                         WHERE line.source_code = :source)
                         ORDER BY m.id
                        """)
                .param("source", source.value()).query(String.class).list();
    }

    /** The breaks on the source's items, resolved or not, each named by its item's key. */
    List<BreakView> breaks(SourceCode source) {
        return jdbc.sql("""
                        SELECT b.id, b.break_type, b.status, b.resolution_code, b.opened_run_id,
                               CASE b.item_side WHEN 'PSP' THEN 'PSP ' || p.line_id
                                                ELSE 'LEDGER ' || l.event_id END AS subject,
                               ARRAY(SELECT CASE r.item ->> 'side' WHEN 'PSP' THEN 'PSP ' || rp.line_id
                                                                  ELSE 'LEDGER ' || rl.event_id END
                                       FROM jsonb_array_elements(b.related_items) AS r(item)
                                       LEFT JOIN psp_lines rp
                                              ON r.item ->> 'side' = 'PSP' AND rp.id = CAST(r.item ->> 'id' AS UUID)
                                       LEFT JOIN ledger_entries rl
                                              ON r.item ->> 'side' = 'LEDGER' AND rl.id = CAST(r.item ->> 'id' AS UUID)
                                    ) AS related
                          FROM breaks b
                          LEFT JOIN psp_lines p ON b.item_side = 'PSP' AND p.id = b.item_id
                          LEFT JOIN ledger_entries l ON b.item_side = 'LEDGER' AND l.id = b.item_id
                         WHERE p.source_code = :source OR l.source_code = :source
                         ORDER BY b.id
                        """)
                .param("source", source.value())
                .query(StageAFixture::breakView)
                .list();
    }

    /** A break's events in the order they were written, as from>to:code:actor. */
    List<String> eventsOf(UUID breakId) {
        return jdbc.sql("""
                        SELECT coalesce(from_status, '-') || '>' || to_status || ':' || coalesce(resolution_code, '-')
                               || ':' || actor
                          FROM break_events WHERE break_id = :breakId ORDER BY id
                        """)
                .param("breakId", breakId).query(String.class).list();
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static BreakView breakView(ResultSet row, int rowNumber) throws SQLException {
        Array related = row.getArray("related");
        return new BreakView(row.getObject("id", UUID.class), row.getString("break_type"), row.getString("status"),
                Optional.ofNullable(row.getString("resolution_code")), row.getObject("opened_run_id", UUID.class),
                row.getString("subject"), new TreeSet<>(Arrays.asList((String[]) related.getArray())));
    }

    private static String sha256() {
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** A PSP line to store: a positive gross is a payment, a negative one a refund; no fee. */
    record Psp(String lineId, String reference, long gross, String currency, LocalDate valueDate) {

        PspLine toLine(UUID fileId, SourceCode source) {
            Money amount = Money.of(gross, CurrencyCode.of(currency));
            return new PspLine(UUID.randomUUID(), fileId, source, lineId, Optional.ofNullable(reference), "B-001",
                    gross > 0 ? PspLineType.PAYMENT : PspLineType.REFUND, valueDate, valueDate, amount,
                    Money.zero(amount.currency()), amount);
        }
    }

    record MatchView(String rule, int ruleVersion, String cardinality, String status, long amountDifference,
                     String currency, boolean lowConfidence, UUID runId, String ledger, String psp, long createdEvents) {
    }

    record BreakView(UUID id, String type, String status, Optional<String> resolutionCode, UUID openedRunId,
                     String subject, Set<String> related) {
    }
}
