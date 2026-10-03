package com.baran.recon.adapters.out.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import com.baran.recon.application.port.StageAStore;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.match.RuleId;
import com.baran.recon.domain.match.StageARule;

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

    private static final String MATCH_BY_REFERENCE = "WITH " + String.join(",\n", BUSINESS_DAYS, UNMATCHED, A1_PAIRS,
            MATCH_WRITES) + "\nSELECT count(*) FROM new_match_event";

    private final JdbcClient jdbc;

    JdbcStageAStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int matchByReference(StageAPass pass) {
        return Math.toIntExact(matching(MATCH_BY_REFERENCE, pass, StageARule.A1_EXACT_REFERENCE)
                .query(Long.class).single());
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
