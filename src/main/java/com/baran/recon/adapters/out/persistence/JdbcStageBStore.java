package com.baran.recon.adapters.out.persistence;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import com.baran.recon.application.port.ItemTotals;
import com.baran.recon.application.port.StageBStore;
import com.baran.recon.domain.item.SourceCode;

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

    /**
     * The batches dated in the range, one row per batch and currency (TDD 8.2): the lines of a PSP
     * source settling to the bank with one batch id, from whatever file, in the snapshot. A batch's
     * date is the latest value date of all its lines, in every currency, so a line outside the range
     * still counts in its batch's sum. A sum that does not fit a BIGINT fails the cast, and the run
     * with it (INV-8).
     */
    private static final String DATED_BATCHES = """
            batch_currency AS (
                SELECT line.source_code, line.batch_id, line.currency, count(*) AS lines,
                       CAST(sum(line.net_amount) AS BIGINT) AS amount,
                       max(max(line.value_date)) OVER (PARTITION BY line.source_code, line.batch_id) AS batch_date
                  FROM psp_lines line
                 WHERE line.source_code = ANY (CAST(:pspSources AS TEXT[]))
                 GROUP BY line.source_code, line.batch_id, line.currency
            ),
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
    private static final String ITEM_TOTALS = "WITH " + String.join(",\n", NAMED, DATED_BATCHES) + """
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

    private final JdbcClient jdbc;

    JdbcStageBStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ItemTotals itemTotals(SourceCode bank, List<SourceCode> pspSources, LocalDate valueDateFrom,
                                 LocalDate valueDateTo) {
        return ItemTotals.partitioned(jdbc.sql(ITEM_TOTALS)
                .param("bank", bank.value())
                .param("pspSources", new SqlArrayValue("text", pspSources.stream().map(SourceCode::value).toArray()))
                .param("from", valueDateFrom)
                .param("to", valueDateTo)
                .query(SqlValues::itemTotal)
                .list());
    }
}
