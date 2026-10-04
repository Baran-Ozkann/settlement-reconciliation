package com.baran.recon.adapters.out.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import com.baran.recon.application.port.StageAStore;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.match.RuleId;
import com.baran.recon.domain.match.StageARule;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.run.ItemStatus;
import com.baran.recon.domain.run.ItemTotal;

import static com.baran.recon.adapters.out.persistence.SqlValues.timestamp;

/**
 * Stage A as SQL (TDD 8.2). Each step is one statement: common table expressions find the items
 * and decide, and data-modifying ones write the matches, their items and events, all on the
 * snapshot the statement started with. Nothing a step decides is read into the JVM, so memory does
 * not grow with the source.
 *
 * <p>Every value from a file, an event or the run reaches the statements as a bind parameter; the
 * statement text is built from the constants of this class alone. A reference is compared as a
 * UUID when PostgreSQL reads it as one, so case and hyphenation do not matter; one it cannot read
 * is no reference for A1.
 */
@Repository
class JdbcStageAStore implements StageAStore {

    /** The business days of the run's index, as dates and their numbers (BusinessDayIndex). */
    private static final String BUSINESS_DAYS = """
            business_day AS (
                SELECT CAST(day AS DATE) AS day, ordinal
                  FROM unnest(CAST(:days AS TEXT[]), CAST(:ordinals AS BIGINT[])) AS index_day(day, ordinal)
            )""";

    /**
     * The source's items without an active match (FR-MAT-2), each side once: matched or not is
     * decided by INV-2's index on match_items, which a run cannot get around. A PSP line's
     * reference is its UUID, or NULL when it has none or none PostgreSQL can read.
     */
    private static final String UNMATCHED = """
            psp AS MATERIALIZED (
                SELECT line.id, line.value_date, line.gross_amount, line.currency,
                       CASE WHEN pg_input_is_valid(line.reference, 'uuid') THEN CAST(line.reference AS UUID) END
                           AS reference
                  FROM psp_lines line
                 WHERE line.source_code = :source
                   AND NOT EXISTS (SELECT 1 FROM match_items item
                                    WHERE item.side = 'PSP' AND item.item_id = line.id AND item.active)
            ),
            ledger AS MATERIALIZED (
                SELECT entry.id, entry.transaction_id, entry.value_date, entry.amount, entry.currency
                  FROM ledger_entries entry
                 WHERE entry.source_code = :source AND entry.value_date IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM match_items item
                                    WHERE item.side = 'LEDGER' AND item.item_id = entry.id AND item.active)
            )""";

    /**
     * A1: a reference no other unmatched line carries, and exactly one entry with its transaction
     * id, currency and amount within the window. The count runs over every candidate, in the range
     * or not, before the range is applied, so an item outside the range never makes a pair look
     * unique. A transaction id is named by one unmatched line at most, so an entry is in one pair.
     */
    private static final String A1_PAIRS = """
            single_reference AS (
                SELECT reference FROM psp WHERE reference IS NOT NULL GROUP BY reference HAVING count(*) = 1
            ),
            candidate AS (
                SELECT line.id AS psp_id, entry.id AS ledger_id, line.currency,
                       line.value_date BETWEEN :from AND :to OR entry.value_date BETWEEN :from AND :to AS in_range
                  FROM psp line
                  JOIN single_reference single ON single.reference = line.reference
                  JOIN ledger entry ON entry.transaction_id = line.reference
                                   AND entry.currency = line.currency AND entry.amount = line.gross_amount
                  JOIN business_day line_day ON line_day.day = line.value_date
                  JOIN business_day entry_day ON entry_day.day = entry.value_date
                 WHERE abs(line_day.ordinal - entry_day.ordinal) <= :windowDays
            ),
            pair AS MATERIALIZED (
                SELECT gen_random_uuid() AS match_id, psp_id, (array_agg(ledger_id))[1] AS ledger_id,
                       min(currency) AS currency
                  FROM candidate
                 GROUP BY psp_id
                HAVING count(*) = 1 AND bool_or(in_range)
            )""";

    /**
     * A match per pair, active, with both items and its creation event (FR-MAT-6). Stage A matches
     * equal amounts only, so the difference is zero, in the pair's currency.
     */
    private static final String MATCH_WRITES = """
            new_match AS (
                INSERT INTO matches (id, run_id, rule_id, rule_version, cardinality, status, amount_difference,
                                     currency, low_confidence, created_at)
                SELECT match_id, :runId, :ruleId, :ruleVersion, :cardinality, 'ACTIVE', 0, currency, :lowConfidence, :at
                  FROM pair
            ),
            new_item AS (
                INSERT INTO match_items (match_id, side, item_id, active)
                SELECT match_id, 'LEDGER', ledger_id, TRUE FROM pair
                UNION ALL
                SELECT match_id, 'PSP', psp_id, TRUE FROM pair
            ),
            new_match_event AS (
                INSERT INTO match_events (match_id, event_type, actor, reason, occurred_at)
                SELECT match_id, 'CREATED', :actor, NULL, :at FROM pair
                RETURNING match_id
            )""";

    /**
     * After A1, each unmatched line in the range with a UUID reference: a duplicate reference, or the
     * entries carrying its transaction id within the window. A line with exactly one exact entry
     * was matched by A1 and is no longer unmatched; one with no entry at all is left alone.
     */
    private static final String REFERENCE_FINDINGS = """
            reference_lines AS (
                SELECT reference, count(*) AS lines FROM psp WHERE reference IS NOT NULL GROUP BY reference
            ),
            subject AS (
                SELECT line.id, line.value_date, line.gross_amount, line.currency, line.reference, counted.lines
                  FROM psp line
                  JOIN reference_lines counted ON counted.reference = line.reference
                 WHERE line.value_date BETWEEN :from AND :to
            ),
            duplicate AS (
                SELECT subject.id AS item_id, 'DUPLICATE_LINE' AS break_type,
                       (SELECT jsonb_agg(jsonb_build_object('side', 'PSP', 'id', other.id) ORDER BY other.id)
                          FROM psp other
                         WHERE other.reference = subject.reference AND other.id <> subject.id) AS related_items
                  FROM subject
                 WHERE subject.lines > 1
            ),
            carrier AS (
                SELECT subject.id AS psp_id, entry.id AS ledger_id,
                       entry.currency = subject.currency AS same_currency,
                       entry.currency = subject.currency AND entry.amount = subject.gross_amount AS exact
                  FROM subject
                  JOIN ledger entry ON entry.transaction_id = subject.reference
                  JOIN business_day line_day ON line_day.day = subject.value_date
                  JOIN business_day entry_day ON entry_day.day = entry.value_date
                 WHERE subject.lines = 1 AND abs(line_day.ordinal - entry_day.ordinal) <= :windowDays
            ),
            carriers AS (
                SELECT psp_id, count(*) AS entries, count(*) FILTER (WHERE exact) AS exact_entries,
                       bool_and(same_currency) AS same_currency,
                       jsonb_agg(jsonb_build_object('side', 'LEDGER', 'id', ledger_id) ORDER BY ledger_id) AS all_items,
                       jsonb_agg(jsonb_build_object('side', 'LEDGER', 'id', ledger_id) ORDER BY ledger_id)
                           FILTER (WHERE exact) AS exact_items
                  FROM carrier
                 GROUP BY psp_id
            ),
            conflict AS (
                SELECT psp_id AS item_id,
                       CASE WHEN exact_entries > 1 OR entries > 1 THEN 'AMBIGUOUS_MATCH'
                            WHEN same_currency THEN 'AMOUNT_MISMATCH'
                            ELSE 'CURRENCY_MISMATCH' END AS break_type,
                       CASE WHEN exact_entries > 1 THEN exact_items ELSE all_items END AS related_items
                  FROM carriers
                 WHERE exact_entries <> 1
            ),
            found AS (
                SELECT 'PSP' AS item_side, item_id, break_type, related_items FROM duplicate
                UNION ALL
                SELECT 'PSP', item_id, break_type, related_items FROM conflict
            )""";

    /**
     * A break per finding, OPEN, opened by this run, with its opening event (FR-BRK-6). An item that
     * already has an unresolved break keeps it and gets no second one: the insert names INV-7's
     * partial index as its conflict target, so that rule alone is skipped, and only the breaks
     * actually inserted get an event.
     */
    private static final String BREAK_WRITES = """
            opened AS (
                INSERT INTO breaks (id, break_type, item_side, item_id, related_items, status, resolution_code,
                                    opened_run_id, previous_break_id, opened_at, resolved_at)
                SELECT gen_random_uuid(), break_type, item_side, item_id, related_items, 'OPEN', NULL,
                       :runId, NULL, :at, NULL
                  FROM found
                    ON CONFLICT (item_side, item_id) WHERE status <> 'RESOLVED' DO NOTHING
                RETURNING id
            ),
            opened_event AS (
                INSERT INTO break_events (break_id, from_status, to_status, resolution_code, actor, reason, occurred_at)
                SELECT id, NULL, 'OPEN', NULL, :actor, NULL, :at FROM opened
                RETURNING break_id
            )""";

    /**
     * A3: lines without a usable reference - none or not a UUID (NULL here), or a UUID that is not
     * repeated by another unmatched line and that no entry of the source carries, matched or not,
     * dated or not - and their candidates by currency, amount and window. Every such line counts,
     * in the range or not, so a pair is unique in both directions over everything the window
     * reaches: the line has one candidate, and that entry is the candidate of no other line. Only a
     * line in the range is matched or given a break.
     */
    private static final String A3_FINDINGS = """
            reference_lines AS (
                SELECT reference, count(*) AS lines FROM psp WHERE reference IS NOT NULL GROUP BY reference
            ),
            unreferenced AS (
                SELECT line.id, line.value_date, line.gross_amount, line.currency
                  FROM psp line
                  LEFT JOIN reference_lines counted ON counted.reference = line.reference
                 WHERE line.reference IS NULL
                    OR (counted.lines = 1
                        AND NOT EXISTS (SELECT 1 FROM ledger_entries known
                                         WHERE known.source_code = :source AND known.transaction_id = line.reference))
            ),
            candidate AS (
                SELECT line.id AS psp_id, entry.id AS ledger_id, line.currency,
                       line.value_date BETWEEN :from AND :to AS in_range
                  FROM unreferenced line
                  JOIN ledger entry ON entry.currency = line.currency AND entry.amount = line.gross_amount
                  JOIN business_day line_day ON line_day.day = line.value_date
                  JOIN business_day entry_day ON entry_day.day = entry.value_date
                 WHERE abs(line_day.ordinal - entry_day.ordinal) <= :windowDays
            ),
            by_line AS (
                SELECT psp_id, count(*) AS entries, bool_or(in_range) AS in_range, min(currency) AS currency,
                       CASE WHEN count(*) = 1 THEN (array_agg(ledger_id))[1] END AS only_entry,
                       jsonb_agg(jsonb_build_object('side', 'LEDGER', 'id', ledger_id) ORDER BY ledger_id)
                           AS related_items
                  FROM candidate
                 GROUP BY psp_id
            ),
            by_entry AS (
                SELECT ledger_id, count(*) AS lines FROM candidate GROUP BY ledger_id
            ),
            decided AS (
                SELECT line.psp_id, line.only_entry, line.currency, line.related_items,
                       line.entries = 1 AND entry.lines = 1 AS unique_pair
                  FROM by_line line
                  LEFT JOIN by_entry entry ON entry.ledger_id = line.only_entry
                 WHERE line.in_range
            ),
            pair AS MATERIALIZED (
                SELECT gen_random_uuid() AS match_id, psp_id, only_entry AS ledger_id, currency
                  FROM decided
                 WHERE unique_pair
            ),
            found AS (
                SELECT 'PSP' AS item_side, psp_id AS item_id, 'AMBIGUOUS_MATCH' AS break_type, related_items
                  FROM decided
                 WHERE NOT unique_pair
            )""";

    private static final String MATCH_BY_REFERENCE = "WITH " + String.join(",\n", BUSINESS_DAYS, UNMATCHED, A1_PAIRS,
            MATCH_WRITES) + "\nSELECT count(*) FROM new_match_event";

    private static final String OPEN_REFERENCE_BREAKS = "WITH " + String.join(",\n", BUSINESS_DAYS, UNMATCHED,
            REFERENCE_FINDINGS, BREAK_WRITES) + "\nSELECT count(*) FROM opened_event";

    private static final String MATCH_BY_AMOUNT = "WITH " + String.join(",\n", BUSINESS_DAYS, UNMATCHED, A3_FINDINGS,
            MATCH_WRITES, BREAK_WRITES)
            + "\nSELECT (SELECT count(*) FROM new_match_event) AS matched, (SELECT count(*) FROM opened_event) AS opened";

    /**
     * FR-BRK-5, for the break types a match answers (TDD 8.2): a missing item, or a choice among
     * candidates. A duplicate line or a reference conflict is not explained by the match and stays
     * open for an operator. The breaks are locked before they are read, so their status is the latest
     * committed one, and the update still requires it: the event then records the status the break
     * really left. Only the three columns recon_app may update change.
     */
    private static final String RESOLVE_MATCHED_LATE = """
            WITH matched_item AS (
                SELECT item.side, item.item_id
                  FROM match_items item
                  JOIN matches made ON made.id = item.match_id
                 WHERE made.run_id = :runId AND item.active
            ),
            target AS (
                SELECT open_break.id, open_break.status
                  FROM breaks open_break
                  JOIN matched_item matched
                    ON matched.side = open_break.item_side AND matched.item_id = open_break.item_id
                 WHERE open_break.status <> 'RESOLVED'
                   AND open_break.break_type IN ('MISSING_IN_PSP', 'MISSING_IN_LEDGER', 'AMBIGUOUS_MATCH')
                   FOR UPDATE OF open_break
            ),
            resolved AS (
                UPDATE breaks
                   SET status = 'RESOLVED', resolution_code = 'MATCHED_LATE', resolved_at = :at
                  FROM target
                 WHERE breaks.id = target.id AND breaks.status = target.status
                RETURNING breaks.id, target.status AS from_status
            ),
            resolved_event AS (
                INSERT INTO break_events (break_id, from_status, to_status, resolution_code, actor, reason, occurred_at)
                SELECT id, from_status, 'RESOLVED', 'MATCHED_LATE', :actor, :reason, :at FROM resolved
                RETURNING break_id
            )
            SELECT count(*) FROM resolved_event
            """;

    /**
     * The items named among the related items of an unresolved break: such an item is BROKEN, not
     * pending, and gets no break of its own (TDD 8.2).
     */
    private static final String NAMED = """
            named AS MATERIALIZED (
                SELECT related.item ->> 'side' AS side, CAST(related.item ->> 'id' AS UUID) AS id
                  FROM breaks open_break
                 CROSS JOIN LATERAL jsonb_array_elements(open_break.related_items) AS related(item)
                 WHERE open_break.status <> 'RESOLVED'
            )""";

    /** Pending items of the range past their side's grace period. */
    private static final String GRACE_FINDINGS = """
            found AS (
                SELECT 'LEDGER' AS item_side, entry.id AS item_id, 'MISSING_IN_PSP' AS break_type,
                       CAST('[]' AS JSONB) AS related_items
                  FROM ledger_entries entry
                 WHERE entry.source_code = :source AND entry.value_date BETWEEN :from AND :to
                   AND entry.value_date < :ledgerGraceStart
                   AND NOT EXISTS (SELECT 1 FROM match_items item
                                    WHERE item.side = 'LEDGER' AND item.item_id = entry.id AND item.active)
                   AND NOT EXISTS (SELECT 1 FROM breaks subject
                                    WHERE subject.item_side = 'LEDGER' AND subject.item_id = entry.id
                                      AND subject.status <> 'RESOLVED')
                   AND NOT EXISTS (SELECT 1 FROM named WHERE named.side = 'LEDGER' AND named.id = entry.id)
                UNION ALL
                SELECT 'PSP', line.id, 'MISSING_IN_LEDGER', CAST('[]' AS JSONB)
                  FROM psp_lines line
                 WHERE line.source_code = :source AND line.value_date BETWEEN :from AND :to
                   AND line.value_date < :pspGraceStart
                   AND NOT EXISTS (SELECT 1 FROM match_items item
                                    WHERE item.side = 'PSP' AND item.item_id = line.id AND item.active)
                   AND NOT EXISTS (SELECT 1 FROM breaks subject
                                    WHERE subject.item_side = 'PSP' AND subject.item_id = line.id
                                      AND subject.status <> 'RESOLVED')
                   AND NOT EXISTS (SELECT 1 FROM named WHERE named.side = 'PSP' AND named.id = line.id)
            )""";

    private static final String OPEN_GRACE_BREAKS = "WITH " + String.join(",\n", NAMED, GRACE_FINDINGS, BREAK_WRITES)
            + "\nSELECT count(*) FROM opened_event";

    /**
     * INV-1, INV-4: the range's items by status, decided with EXISTS so an item is counted once
     * however many matches or breaks touch it, and the same items again without a status. A sum is
     * NUMERIC in PostgreSQL; the cast back to BIGINT fails rather than wrap if it ever overflows.
     */
    private static final String ITEM_TOTALS = "WITH " + NAMED + """
            ,
            ledger_item AS (
                SELECT entry.currency, entry.amount,
                       CASE WHEN EXISTS (SELECT 1 FROM match_items item
                                          WHERE item.side = 'LEDGER' AND item.item_id = entry.id AND item.active)
                                 THEN 'MATCHED'
                            WHEN EXISTS (SELECT 1 FROM breaks subject
                                          WHERE subject.item_side = 'LEDGER' AND subject.item_id = entry.id
                                            AND subject.status <> 'RESOLVED')
                              OR EXISTS (SELECT 1 FROM named WHERE named.side = 'LEDGER' AND named.id = entry.id)
                                 THEN 'BROKEN'
                            ELSE 'PENDING' END AS status
                  FROM ledger_entries entry
                 WHERE entry.source_code = :source AND entry.value_date BETWEEN :from AND :to
            ),
            psp_item AS (
                SELECT line.currency, line.gross_amount AS amount,
                       CASE WHEN EXISTS (SELECT 1 FROM match_items item
                                          WHERE item.side = 'PSP' AND item.item_id = line.id AND item.active)
                                 THEN 'MATCHED'
                            WHEN EXISTS (SELECT 1 FROM breaks subject
                                          WHERE subject.item_side = 'PSP' AND subject.item_id = line.id
                                            AND subject.status <> 'RESOLVED')
                              OR EXISTS (SELECT 1 FROM named WHERE named.side = 'PSP' AND named.id = line.id)
                                 THEN 'BROKEN'
                            ELSE 'PENDING' END AS status
                  FROM psp_lines line
                 WHERE line.source_code = :source AND line.value_date BETWEEN :from AND :to
            )
            SELECT 'LEDGER' AS side, currency, status, count(*) AS items, CAST(sum(amount) AS BIGINT) AS amount
              FROM ledger_item GROUP BY currency, status
            UNION ALL
            SELECT 'PSP', currency, status, count(*), CAST(sum(amount) AS BIGINT)
              FROM psp_item GROUP BY currency, status
            UNION ALL
            SELECT 'LEDGER', currency, NULL, count(*), CAST(sum(amount) AS BIGINT)
              FROM ledger_entries
             WHERE source_code = :source AND value_date BETWEEN :from AND :to
             GROUP BY currency
            UNION ALL
            SELECT 'PSP', currency, NULL, count(*), CAST(sum(gross_amount) AS BIGINT)
              FROM psp_lines
             WHERE source_code = :source AND value_date BETWEEN :from AND :to
             GROUP BY currency
            """;

    private final JdbcClient jdbc;

    JdbcStageAStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int matchByReference(StageAPass pass) {
        return Math.toIntExact(matching(MATCH_BY_REFERENCE, pass, StageARule.A1_EXACT_REFERENCE)
                .query(Long.class).single());
    }

    @Override
    public int openReferenceBreaks(StageAPass pass) {
        return Math.toIntExact(pass(OPEN_REFERENCE_BREAKS, pass).query(Long.class).single());
    }

    @Override
    public FallbackOutcome matchByAmount(StageAPass pass) {
        return matching(MATCH_BY_AMOUNT, pass, StageARule.A3_FALLBACK_UNIQUE)
                .query((row, n) -> new FallbackOutcome(Math.toIntExact(row.getLong("matched")),
                        Math.toIntExact(row.getLong("opened"))))
                .single();
    }

    @Override
    public int resolveMatchedLate(UUID runId, String reason, Instant at) {
        return Math.toIntExact(jdbc.sql(RESOLVE_MATCHED_LATE)
                .param("runId", runId)
                .param("reason", reason)
                .param("actor", Actor.SYSTEM.name())
                .param("at", timestamp(at))
                .query(Long.class).single());
    }

    @Override
    public int openGraceBreaks(StageAPass pass, LocalDate ledgerGraceStart, LocalDate pspGraceStart) {
        return Math.toIntExact(jdbc.sql(OPEN_GRACE_BREAKS)
                .param("runId", pass.runId())
                .param("source", pass.source().value())
                .param("from", pass.valueDateFrom())
                .param("to", pass.valueDateTo())
                .param("ledgerGraceStart", ledgerGraceStart)
                .param("pspGraceStart", pspGraceStart)
                .param("actor", Actor.SYSTEM.name())
                .param("at", timestamp(pass.at()))
                .query(Long.class).single());
    }

    @Override
    public ItemTotals itemTotals(SourceCode source, LocalDate valueDateFrom, LocalDate valueDateTo) {
        List<ItemTotal> totals = jdbc.sql(ITEM_TOTALS)
                .param("source", source.value())
                .param("from", valueDateFrom)
                .param("to", valueDateTo)
                .query((row, n) -> new ItemTotal(ItemSide.valueOf(row.getString("side")),
                        CurrencyCode.of(row.getString("currency")),
                        Optional.ofNullable(row.getString("status")).map(ItemStatus::valueOf),
                        row.getLong("items"), row.getLong("amount")))
                .list();
        Map<Boolean, List<ItemTotal>> byKind = totals.stream()
                .collect(Collectors.partitioningBy(total -> total.status().isPresent()));
        return new ItemTotals(byKind.get(true), byKind.get(false));
    }

    private JdbcClient.StatementSpec matching(String sql, StageAPass pass, StageARule rule) {
        RuleId matchRule = rule.matchRule().orElseThrow();
        return pass(sql, pass)
                .param("ruleId", matchRule.name())
                .param("ruleVersion", rule.version())
                .param("cardinality", matchRule.cardinality().name())
                .param("lowConfidence", matchRule.lowConfidence());
    }

    private JdbcClient.StatementSpec pass(String sql, StageAPass pass) {
        return jdbc.sql(sql)
                .param("runId", pass.runId())
                .param("source", pass.source().value())
                .param("from", pass.valueDateFrom())
                .param("to", pass.valueDateTo())
                .param("windowDays", pass.valueDateWindowDays())
                .param("days", new SqlArrayValue("text", pass.businessDays().days().stream().map(Object::toString).toArray()))
                .param("ordinals", new SqlArrayValue("int8", pass.businessDays().ordinals().toArray()))
                .param("actor", Actor.SYSTEM.name())
                .param("at", timestamp(pass.at()));
    }
}
