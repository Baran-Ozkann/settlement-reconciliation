-- Stage B's two columns (TDD 10, v1.14). As in V3, each constraint is named and listed in the test
-- suite's DatabaseMechanism with its break proof, and each CHECK owns one rule.
--
-- A B1 match holds one BATCH item and one BANK item, never the batch's PSP lines, which keep their
-- Stage A matches (INV-2). The N of its N:1 is the batch's line count when it was matched, kept on
-- the match so a later run can tell that a line arrived after it (TDD 8.2).
ALTER TABLE matches ADD COLUMN batch_line_count INTEGER NULL;
ALTER TABLE matches ADD CONSTRAINT matches_batch_line_count_positive CHECK (batch_line_count >= 1);
ALTER TABLE matches ADD CONSTRAINT matches_batch_line_count_when_b1 CHECK (
    (rule_id = 'B1_BATCH_TOTAL') = (batch_line_count IS NOT NULL));

-- A BATCH_AMOUNT_MISMATCH carries the figures that disagree: the bank line's amount and currency
-- and the batch's per-currency sums and line count, or a matched batch's old and new figures. They
-- change as a batch's lines arrive, so they are recorded as the run saw them, not computed on read.
ALTER TABLE breaks ADD COLUMN details JSONB NULL;
ALTER TABLE breaks ADD CONSTRAINT breaks_details_object CHECK (jsonb_typeof(details) = 'object');
ALTER TABLE breaks ADD CONSTRAINT breaks_details_when_batch_amount_mismatch CHECK (
    (break_type = 'BATCH_AMOUNT_MISMATCH') = (details IS NOT NULL));
