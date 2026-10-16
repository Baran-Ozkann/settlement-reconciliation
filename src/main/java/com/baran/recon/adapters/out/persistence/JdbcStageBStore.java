package com.baran.recon.adapters.out.persistence;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import com.baran.recon.application.port.ItemTotals;
import com.baran.recon.application.port.StageBStore;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.match.RuleId;
import com.baran.recon.domain.match.StageBRule;

import static com.baran.recon.adapters.out.persistence.SqlValues.timestamp;

/**
 * Stage B as SQL (TDD 8.2), on the bank source's lines and the batches of the PSP sources that
 * settle to it. As in Stage A, each step is one statement and nothing it decides is read into the
 * JVM, so memory does not grow with the sources. Every value reaches the statement as a bind
 * parameter; the text is built from this class's constants and {@link BatchItemIdSql} alone.
 */
@Repository
class JdbcStageBStore implements StageBStore {

    /**
     * The items named among the related items of an unresolved break about a bank line or a batch:
     * such an item is BROKEN, not pending (TDD 8.2). A Stage A break, about a ledger entry or a PSP
     * line, never changes a bank line's or a batch's status, as a Stage B break never changes a PSP
     * line's (v1.15).
     */
    private static final String NAMED = """
            named AS MATERIALIZED (
                SELECT related.item ->> 'side' AS side, CAST(related.item ->> 'id' AS UUID) AS id
                  FROM breaks open_break
                 CROSS JOIN LATERAL jsonb_array_elements(open_break.related_items) AS related(item)
                 WHERE open_break.status <> 'RESOLVED' AND open_break.item_side IN ('BANK', 'BATCH')
            )""";

    /** The lines of the PSP sources settling to the bank, whatever their dates and files. */
    private static final String SETTLING_LINES = """
            settling_line AS (
                SELECT line.source_code, line.batch_id, line.currency, line.net_amount, line.value_date
                  FROM psp_lines line
                 WHERE line.source_code = ANY (CAST(:pspSources AS TEXT[]))
            )""";

    /**
     * The batches of the settling lines, one row per batch and currency (TDD 8.2): the lines of a PSP
     * source with one batch id, from whatever file, in the snapshot. A batch's date is the latest
     * value date of all its lines, in every currency, so a line outside the range still counts in its
     * batch's sum. A sum that does not fit a BIGINT fails the cast, and the run with it (INV-8).
     */
    private static final String BATCH_CURRENCY = """
            batch_currency AS (
                SELECT line.source_code, line.batch_id, line.currency, count(*) AS lines,
                       CAST(sum(line.net_amount) AS BIGINT) AS amount,
                       max(max(line.value_date)) OVER (PARTITION BY line.source_code, line.batch_id) AS batch_date
                  FROM settling_line line
                 GROUP BY line.source_code, line.batch_id, line.currency
            )""";

    /** The batches dated in the range, one row per batch and currency, with their item ids. */
    private static final String DATED_BATCHES = BATCH_CURRENCY + """
            ,
            batch AS MATERIALIZED (
                SELECT %s AS id, dated.source_code, dated.batch_id, dated.currency, dated.lines, dated.amount
                  FROM batch_currency dated
                 WHERE dated.batch_date BETWEEN :from AND :to
            )""".formatted(BatchItemIdSql.of("dated.source_code", "dated.batch_id"));

    /**
     * INV-1, INV-4: the range's bank lines and batches by status, decided with EXISTS so an item is
     * counted once however many matches or breaks touch it, in the order MATCHED, BROKEN, PENDING.
     * Then the same items again without a status, each derived its own way, so a status total that
     * counts an item twice or misses one disagrees with it: bank lines straight from their table, and
     * batches as the distinct batch ids of the lines whose batch is dated in the range.
     */
    private static final String ITEM_TOTALS = "WITH " + String.join(",\n", NAMED, SETTLING_LINES, DATED_BATCHES) + """
            ,
            bank_item AS (
                SELECT line.currency, line.amount,
                       CASE WHEN EXISTS (SELECT 1 FROM match_items item
                                          WHERE item.side = 'BANK' AND item.item_id = line.id AND item.active)
                                 THEN 'MATCHED'
                            WHEN EXISTS (SELECT 1 FROM breaks subject
                                          WHERE subject.item_side = 'BANK' AND subject.item_id = line.id
                                            AND subject.status <> 'RESOLVED')
                              OR EXISTS (SELECT 1 FROM named WHERE named.side = 'BANK' AND named.id = line.id)
                                 THEN 'BROKEN'
                            ELSE 'PENDING' END AS status
                  FROM bank_lines line
                 WHERE line.source_code = :bank AND line.value_date BETWEEN :from AND :to
            ),
            batch_item AS (
                SELECT batch.currency, batch.amount,
                       CASE WHEN EXISTS (SELECT 1 FROM match_items item
                                          WHERE item.side = 'BATCH' AND item.item_id = batch.id AND item.active)
                                 THEN 'MATCHED'
                            WHEN EXISTS (SELECT 1 FROM breaks subject
                                          WHERE subject.item_side = 'BATCH' AND subject.item_id = batch.id
                                            AND subject.status <> 'RESOLVED')
                              OR EXISTS (SELECT 1 FROM named WHERE named.side = 'BATCH' AND named.id = batch.id)
                                 THEN 'BROKEN'
                            ELSE 'PENDING' END AS status
                  FROM batch
            ),
            scope_batch AS (
                SELECT line.source_code, line.batch_id
                  FROM psp_lines line
                 WHERE line.source_code = ANY (CAST(:pspSources AS TEXT[]))
                 GROUP BY line.source_code, line.batch_id
                HAVING max(line.value_date) BETWEEN :from AND :to
            )
            SELECT 'BANK' AS side, currency, status, count(*) AS items, CAST(sum(amount) AS BIGINT) AS amount
              FROM bank_item GROUP BY currency, status
            UNION ALL
            SELECT 'BATCH', currency, status, count(*), CAST(sum(amount) AS BIGINT)
              FROM batch_item GROUP BY currency, status
            UNION ALL
            SELECT 'BANK', currency, NULL, count(*), CAST(sum(amount) AS BIGINT)
              FROM bank_lines
             WHERE source_code = :bank AND value_date BETWEEN :from AND :to
             GROUP BY currency
            UNION ALL
            SELECT 'BATCH', line.currency, NULL, count(DISTINCT (line.source_code, line.batch_id)),
                   CAST(sum(line.net_amount) AS BIGINT)
              FROM psp_lines line
              JOIN scope_batch dated ON dated.source_code = line.source_code AND dated.batch_id = line.batch_id
             GROUP BY line.currency
            """;

    /**
     * The bank source's lines without an active match (FR-MAT-2), in the range or not: a line outside
     * it still names its batch, as a PSP line outside Stage A's range still carries its reference.
     */
    private static final String UNMATCHED_BANK = """
            unmatched_bank AS MATERIALIZED (
                SELECT line.id, line.amount, line.currency, line.extracted_batch_id AS batch_id,
                       line.value_date BETWEEN :from AND :to AS in_range
                  FROM bank_lines line
                 WHERE line.source_code = :bank
                   AND NOT EXISTS (SELECT 1 FROM match_items item
                                    WHERE item.side = 'BANK' AND item.item_id = line.id AND item.active)
            )""";

    /**
     * The settling lines of the batches some unmatched bank line names: no other batch can be matched
     * or named by a rule, so no other is summed.
     */
    private static final String CLAIMED_LINES = """
            settling_line AS (
                SELECT line.source_code, line.batch_id, line.currency, line.net_amount, line.value_date
                  FROM psp_lines line
                 WHERE line.source_code = ANY (CAST(:pspSources AS TEXT[]))
                   AND line.batch_id IN (SELECT claim.batch_id FROM unmatched_bank claim)
            )""";

    /**
     * Each unmatched bank line's one outcome, in TDD 8.2's precedence (v1.15):
     * <ol>
     *   <li>UNEXPECTED: no batch id, or one naming no batch of a settling PSP source;</li>
     *   <li>AMBIGUOUS: an id naming batches of several settling PSP sources, whatever other lines
     *       name (FR-MAT-4);</li>
     *   <li>DUPLICATE: an id naming one batch that is already matched, or that another unmatched bank
     *       line names too, in the range or not;</li>
     *   <li>B1: the batch has lines in one currency only, the line's, and its sum is the line's
     *       amount;</li>
     *   <li>B2: otherwise: the sum or the currency differs.</li>
     * </ol>
     * Each batch comes with its line count, its sum per currency in currency-code order (INV-5),
     * whether it is dated in the range, and, when already matched, the bank line it was matched to. A
     * line meets at most one batch, so it has exactly one row here.
     */
    private static final String DECIDED = """
            batch AS MATERIALIZED (
                SELECT totals.id, totals.source_code, totals.batch_id, totals.currencies, totals.lines, totals.sums,
                       totals.in_range, paid.item_id AS paid_by
                  FROM (SELECT %s AS id, source_code, batch_id, count(*) AS currencies,
                               CAST(sum(lines) AS INTEGER) AS lines,
                               jsonb_agg(jsonb_build_object('currency', currency, 'amount', amount) ORDER BY currency)
                                   AS sums,
                               max(batch_date) BETWEEN :from AND :to AS in_range
                          FROM batch_currency
                         GROUP BY source_code, batch_id) totals
                  LEFT JOIN match_items matched
                         ON matched.side = 'BATCH' AND matched.item_id = totals.id AND matched.active
                  LEFT JOIN match_items paid
                         ON paid.match_id = matched.match_id AND paid.side = 'BANK' AND paid.active
            ),
            naming AS (
                SELECT batch_id, count(*) AS batches,
                       jsonb_agg(jsonb_build_object('side', 'BATCH', 'id', id) ORDER BY id) AS batch_items
                  FROM batch
                 GROUP BY batch_id
            ),
            claims AS (
                SELECT batch_id, count(*) AS lines FROM unmatched_bank WHERE batch_id IS NOT NULL GROUP BY batch_id
            ),
            decided AS MATERIALIZED (
                SELECT line.id, line.amount, line.currency, line.in_range, line.batch_id,
                       batch.id AS batch_item, batch.in_range AS batch_in_range, batch.lines AS batch_lines,
                       batch.sums AS batch_sums, naming.batch_items AS named_batches,
                       CASE WHEN naming.batches IS NULL THEN 'UNEXPECTED'
                            WHEN naming.batches > 1 THEN 'AMBIGUOUS'
                            WHEN batch.paid_by IS NOT NULL OR claims.lines > 1 THEN 'DUPLICATE'
                            WHEN batch.currencies = 1 AND same_currency.amount = line.amount THEN 'B1'
                            ELSE 'B2' END AS outcome
                  FROM unmatched_bank line
                  LEFT JOIN naming ON naming.batch_id = line.batch_id
                  LEFT JOIN batch ON batch.batch_id = line.batch_id AND naming.batches = 1
                  LEFT JOIN batch_currency same_currency
                         ON same_currency.source_code = batch.source_code AND same_currency.batch_id = batch.batch_id
                        AND same_currency.currency = line.currency
                  LEFT JOIN claims ON claims.batch_id = line.batch_id
            )""".formatted(BatchItemIdSql.of("source_code", "batch_id"));

    /** The rules' common ground: the unmatched bank lines, the batches they name, each line's outcome. */
    private static final String RULES = String.join(",\n", UNMATCHED_BANK, CLAIMED_LINES, BATCH_CURRENCY, DECIDED);

    /**
     * B1: a match per pair whose bank line or batch is in the range, active, with its BATCH and BANK
     * items and its creation event (FR-MAT-6), as Stage A writes its matches. The totals agree, so the
     * difference is zero, in the pair's currency; the batch's line count is the N of N:1 (TDD 8.2).
     */
    private static final String MATCH_BY_BATCH_TOTAL = "WITH " + RULES + """
            ,
            pair AS MATERIALIZED (
                SELECT gen_random_uuid() AS match_id, id AS bank_id, batch_item, currency, batch_lines
                  FROM decided
                 WHERE outcome = 'B1' AND (in_range OR batch_in_range)
            ),
            new_match AS (
                INSERT INTO matches (id, run_id, rule_id, rule_version, cardinality, status, amount_difference,
                                     currency, low_confidence, created_at, batch_line_count)
                SELECT match_id, :runId, :ruleId, :ruleVersion, :cardinality, 'ACTIVE', 0, currency, :lowConfidence,
                       :at, batch_lines
                  FROM pair
            ),
            new_item AS (
                INSERT INTO match_items (match_id, side, item_id, active)
                SELECT match_id, 'BATCH', batch_item, TRUE FROM pair
                UNION ALL
                SELECT match_id, 'BANK', bank_id, TRUE FROM pair
            ),
            new_match_event AS (
                INSERT INTO match_events (match_id, event_type, actor, reason, occurred_at)
                SELECT match_id, 'CREATED', :actor, NULL, :at FROM pair
                RETURNING match_id
            )
            SELECT count(*) FROM new_match_event
            """;

    /**
     * After B1, a break on each unmatched bank line in the run's range that B1 could not match. A line
     * outside the range gets none, as a PSP line outside Stage A's range gets none, and gets its own
     * when a run covers it.
     * <ul>
     *   <li>B2: BATCH_AMOUNT_MISMATCH, related item the BATCH, with the bank line's amount and the
     *       batch's line count and sums as BATCH_TOTAL_CONFLICT details (TDD 8.2, v1.15), written
     *       once, here;</li>
     *   <li>UNEXPECTED_BANK_LINE, naming nothing: there is no grace for a bank line;</li>
     *   <li>AMBIGUOUS_MATCH, naming every batch the id names (FR-MAT-4);</li>
     *   <li>DUPLICATE_LINE, naming the other bank lines that claim the batch - the unmatched ones in
     *       the range or not, as Stage A names a duplicate's other lines, and the line it is already
     *       matched to - and the BATCH, so the batch is BROKEN while it is claimed twice.</li>
     * </ul>
     * Related items are in side and id order, so the same data gives the same breaks (INV-5).
     */
    private static final String BANK_LINE_FINDINGS = """
            duplicate AS (
                SELECT subject.id AS item_id,
                       jsonb_agg(jsonb_build_object('side', other.side, 'id', other.id) ORDER BY other.side, other.id)
                           AS related_items
                  FROM decided subject
                  JOIN (SELECT batch_id, 'BANK' AS side, id FROM unmatched_bank
                        UNION ALL
                        SELECT batch_id, 'BANK', paid_by FROM batch WHERE paid_by IS NOT NULL
                        UNION ALL
                        SELECT batch_id, 'BATCH', id FROM batch) other
                    ON other.batch_id = subject.batch_id AND other.id <> subject.id
                 WHERE subject.in_range AND subject.outcome = 'DUPLICATE'
                 GROUP BY subject.id
            ),
            found AS (
                SELECT 'BANK' AS item_side, id AS item_id, 'UNEXPECTED_BANK_LINE' AS break_type,
                       CAST('[]' AS JSONB) AS related_items, CAST(NULL AS JSONB) AS details
                  FROM decided
                 WHERE in_range AND outcome = 'UNEXPECTED'
                UNION ALL
                SELECT 'BANK', id, 'BATCH_AMOUNT_MISMATCH',
                       jsonb_build_array(jsonb_build_object('side', 'BATCH', 'id', batch_item)),
                       jsonb_build_object('kind', 'BATCH_TOTAL_CONFLICT',
                                          'bank', jsonb_build_object('amount', amount, 'currency', currency),
                                          'batch', jsonb_build_object('lineCount', batch_lines, 'sums', batch_sums))
                  FROM decided
                 WHERE in_range AND outcome = 'B2'
                UNION ALL
                SELECT 'BANK', id, 'AMBIGUOUS_MATCH', named_batches, NULL
                  FROM decided
                 WHERE in_range AND outcome = 'AMBIGUOUS'
                UNION ALL
                SELECT 'BANK', item_id, 'DUPLICATE_LINE', related_items, NULL
                  FROM duplicate
            )""";

    /**
     * A break per finding, OPEN, opened by this run, with its opening event (FR-BRK-6), as Stage A
     * writes its breaks. An item that already has an unresolved break keeps it and gets no second
     * one, whatever type this run would open: the insert names INV-7's partial index as its conflict
     * target, so that rule alone is skipped, and only the breaks actually inserted get an event.
     */
    private static final String OPEN_BANK_LINE_BREAKS = "WITH " + String.join(",\n", RULES, BANK_LINE_FINDINGS) + """
            ,
            opened AS (
                INSERT INTO breaks (id, break_type, item_side, item_id, related_items, status, resolution_code,
                                    opened_run_id, previous_break_id, opened_at, resolved_at, details)
                SELECT gen_random_uuid(), break_type, item_side, item_id, related_items, 'OPEN', NULL,
                       :runId, NULL, :at, NULL, details
                  FROM found
                    ON CONFLICT (item_side, item_id) WHERE status <> 'RESOLVED' DO NOTHING
                RETURNING id, break_type
            ),
            opened_event AS (
                INSERT INTO break_events (break_id, from_status, to_status, resolution_code, actor, reason, occurred_at)
                SELECT id, NULL, 'OPEN', NULL, :actor, NULL, :at FROM opened
                RETURNING break_id
            )
            SELECT count(*) FILTER (WHERE break_type = 'BATCH_AMOUNT_MISMATCH') AS batch_conflicts,
                   count(*) FILTER (WHERE break_type = 'UNEXPECTED_BANK_LINE') AS unexpected_lines,
                   count(*) FILTER (WHERE break_type = 'AMBIGUOUS_MATCH') AS ambiguous_lines,
                   count(*) FILTER (WHERE break_type = 'DUPLICATE_LINE') AS duplicate_lines
              FROM opened
            """;

    private final JdbcClient jdbc;

    JdbcStageBStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int matchBatchTotals(StageBPass pass) {
        RuleId rule = StageBRule.B1_BATCH_TOTAL.matchRule().orElseThrow();
        return Math.toIntExact(pass(MATCH_BY_BATCH_TOTAL, pass)
                .param("ruleId", rule.name())
                .param("ruleVersion", StageBRule.B1_BATCH_TOTAL.version())
                .param("cardinality", rule.cardinality().name())
                .param("lowConfidence", rule.lowConfidence())
                .query(Long.class).single());
    }

    @Override
    public BankLineBreaks openBankLineBreaks(StageBPass pass) {
        return pass(OPEN_BANK_LINE_BREAKS, pass)
                .query((row, n) -> new BankLineBreaks(Math.toIntExact(row.getLong("batch_conflicts")),
                        Math.toIntExact(row.getLong("unexpected_lines")), Math.toIntExact(row.getLong("ambiguous_lines")),
                        Math.toIntExact(row.getLong("duplicate_lines"))))
                .single();
    }

    @Override
    public ItemTotals itemTotals(SourceCode bank, List<SourceCode> pspSources, LocalDate valueDateFrom,
                                 LocalDate valueDateTo) {
        return ItemTotals.partitioned(jdbc.sql(ITEM_TOTALS)
                .param("bank", bank.value())
                .param("pspSources", sourceArray(pspSources))
                .param("from", valueDateFrom)
                .param("to", valueDateTo)
                .query(SqlValues::itemTotal)
                .list());
    }

    private JdbcClient.StatementSpec pass(String sql, StageBPass pass) {
        return jdbc.sql(sql)
                .param("runId", pass.runId())
                .param("bank", pass.bank().value())
                .param("pspSources", sourceArray(pass.pspSources()))
                .param("from", pass.valueDateFrom())
                .param("to", pass.valueDateTo())
                .param("actor", Actor.SYSTEM.name())
                .param("at", timestamp(pass.at()));
    }

    private static SqlArrayValue sourceArray(List<SourceCode> sources) {
        return new SqlArrayValue("text", sources.stream().map(SourceCode::value).toArray());
    }
}
