package com.baran.recon.adapters.out.persistence;

/**
 * {@code BatchKey.itemId()} in SQL (TDD 8.2, v1.14): a batch has no row of its own, so a statement
 * that joins a batch to {@code match_items} or {@code breaks} computes its id from the batch's
 * source and batch id. Every statement takes it from here, so there is one SQL form of it, and
 * {@code BatchItemIdSqlPropertiesTest} pins it equal to the Java one.
 *
 * <p>Java's name-based UUID is the MD5 of the name with two nibbles overwritten: the version nibble
 * (hex digit 13) becomes 3, and the variant nibble (hex digit 17) keeps its low two bits under the
 * high bits 10, which {@code translate} does digit by digit. Both parts are ASCII under their columns'
 * CHECKs, so PostgreSQL's MD5 of the text hashes the same bytes as Java's MD5 of its UTF-8.
 *
 * <p>The arguments are SQL expressions written in this package's statements, such as column names
 * or casts of bind parameters, never input.
 */
final class BatchItemIdSql {

    private static final String TEMPLATE = """
            CAST(overlay(overlay(md5('recon-batch/' || %1$s || '/' || %2$s) PLACING '3' FROM 13 FOR 1)
                         PLACING translate(substr(md5('recon-batch/' || %1$s || '/' || %2$s), 17, 1),
                                           '0123456789abcdef', '89ab89ab89ab89ab')
                         FROM 17 FOR 1) AS UUID)""";

    private BatchItemIdSql() {
    }

    /** The expression for the batch item id of the batch {@code batchId} of {@code sourceCode}. */
    static String of(String sourceCode, String batchId) {
        return TEMPLATE.formatted(sourceCode, batchId);
    }
}
