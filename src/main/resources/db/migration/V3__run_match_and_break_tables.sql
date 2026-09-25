-- What reconciliation produces: runs, the matches they make and the breaks they open, each with
-- its audit events (TDD 10). As in V2, every constraint and index is named and listed in the test
-- suite's DatabaseMechanism with its break proof, and each CHECK owns exactly one rule: a check
-- that relates two columns forbids only the known-wrong combinations, and leaves an unknown value
-- to the check that lists the allowed ones.
--
-- item_id is a UUID on every side. A batch has no row of its own; the domain derives its id from
-- the source and batch id (BatchKey), so the same batch always has the same id.

CREATE TABLE reconciliation_runs (
    id              UUID        NOT NULL,
    source_code     TEXT        NOT NULL,
    value_date_from DATE        NOT NULL,
    value_date_to   DATE        NOT NULL,
    status          TEXT        NOT NULL,
    config_snapshot JSONB       NOT NULL,
    stats           JSONB       NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ NULL,
    triggered_by    TEXT        NOT NULL,
    CONSTRAINT reconciliation_runs_pk PRIMARY KEY (id),
    CONSTRAINT reconciliation_runs_source_code_format CHECK (source_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT reconciliation_runs_status_valid CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT reconciliation_runs_date_range_in_order CHECK (value_date_from <= value_date_to),
    CONSTRAINT reconciliation_runs_finished_unless_running CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT reconciliation_runs_finished_after_start CHECK (finished_at IS NULL OR finished_at >= started_at),
    -- FR-MAT-10: a completed run carries its statistics, so a past run stays reproducible.
    CONSTRAINT reconciliation_runs_completed_has_stats CHECK (status <> 'COMPLETED' OR stats IS NOT NULL),
    -- FR-MAT-8: the configuration the run used, as one document.
    CONSTRAINT reconciliation_runs_config_snapshot_object CHECK (jsonb_typeof(config_snapshot) = 'object')
);

-- Operational state only (TDD 10): the latest run per source.
CREATE TABLE sources_state (
    source_code TEXT        NOT NULL,
    last_run_id UUID        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT sources_state_pk PRIMARY KEY (source_code),
    CONSTRAINT sources_state_last_run_fk FOREIGN KEY (last_run_id) REFERENCES reconciliation_runs (id),
    CONSTRAINT sources_state_source_code_format CHECK (source_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
);

-- FR-MAT-6. currency is not in TDD 10's logical model: amount_difference is money, and like every
-- other amount here it carries its currency so the row can be read back as one.
CREATE TABLE matches (
    id                UUID        NOT NULL,
    run_id            UUID        NOT NULL,
    rule_id           TEXT        NOT NULL,
    rule_version      INTEGER     NOT NULL,
    cardinality       TEXT        NOT NULL,
    status            TEXT        NOT NULL,
    amount_difference BIGINT      NOT NULL,
    currency          CHAR(3)     NOT NULL,
    low_confidence    BOOLEAN     NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT matches_pk PRIMARY KEY (id),
    CONSTRAINT matches_run_fk FOREIGN KEY (run_id) REFERENCES reconciliation_runs (id),
    CONSTRAINT matches_rule_valid CHECK (rule_id IN ('A1_EXACT_REFERENCE', 'A3_FALLBACK_UNIQUE', 'B1_BATCH_TOTAL')),
    CONSTRAINT matches_rule_version_positive CHECK (rule_version >= 1),
    CONSTRAINT matches_cardinality_valid CHECK (cardinality IN ('ONE_TO_ONE', 'MANY_TO_ONE')),
    CONSTRAINT matches_status_valid CHECK (status IN ('ACTIVE', 'REVERSED')),
    CONSTRAINT matches_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    -- The rule fixes the cardinality: Stage A is one to one, Stage B many to one.
    CONSTRAINT matches_cardinality_matches_rule CHECK (
        NOT (rule_id IN ('A1_EXACT_REFERENCE', 'A3_FALLBACK_UNIQUE') AND cardinality = 'MANY_TO_ONE')
        AND NOT (rule_id = 'B1_BATCH_TOTAL' AND cardinality = 'ONE_TO_ONE')),
    -- Only the fallback rule A3 is low confidence (TDD 8.2).
    CONSTRAINT matches_low_confidence_matches_rule CHECK (
        NOT (rule_id = 'A3_FALLBACK_UNIQUE' AND NOT low_confidence)
        AND NOT (rule_id IN ('A1_EXACT_REFERENCE', 'B1_BATCH_TOTAL') AND low_confidence))
);

CREATE TABLE match_items (
    match_id UUID    NOT NULL,
    side     TEXT    NOT NULL,
    item_id  UUID    NOT NULL,
    active   BOOLEAN NOT NULL,
    CONSTRAINT match_items_pk PRIMARY KEY (match_id, side, item_id),
    CONSTRAINT match_items_match_fk FOREIGN KEY (match_id) REFERENCES matches (id),
    CONSTRAINT match_items_side_valid CHECK (side IN ('LEDGER', 'PSP', 'BANK', 'BATCH'))
);

-- INV-2: an item belongs to at most one active match. A reversed match keeps its rows with
-- active = false, so the item is free again without its history being deleted (FR-MAT-7).
CREATE UNIQUE INDEX match_items_active_item_unique ON match_items (side, item_id) WHERE active;

-- Append-only (TDD 8.4): V4 adds the trigger, and recon_app is granted SELECT and INSERT only.
CREATE TABLE match_events (
    id          BIGSERIAL   NOT NULL,
    match_id    UUID        NOT NULL,
    event_type  TEXT        NOT NULL,
    actor       TEXT        NOT NULL,
    reason      TEXT        NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT match_events_pk PRIMARY KEY (id),
    CONSTRAINT match_events_match_fk FOREIGN KEY (match_id) REFERENCES matches (id),
    CONSTRAINT match_events_event_type_valid CHECK (event_type IN ('CREATED', 'REVERSED')),
    CONSTRAINT match_events_actor_length CHECK (char_length(actor) BETWEEN 1 AND 100),
    CONSTRAINT match_events_reason_length CHECK (reason IS NULL OR char_length(reason) BETWEEN 1 AND 1000),
    -- FR-MAT-7: a reversal needs a reason.
    CONSTRAINT match_events_reversal_has_reason CHECK (event_type <> 'REVERSED' OR reason IS NOT NULL)
);

CREATE TABLE breaks (
    id                UUID        NOT NULL,
    break_type        TEXT        NOT NULL,
    item_side         TEXT        NOT NULL,
    item_id           UUID        NOT NULL,
    related_items     JSONB       NOT NULL,
    status            TEXT        NOT NULL,
    resolution_code   TEXT        NULL,
    opened_run_id     UUID        NULL,
    previous_break_id UUID        NULL,
    opened_at         TIMESTAMPTZ NOT NULL,
    resolved_at       TIMESTAMPTZ NULL,
    CONSTRAINT breaks_pk PRIMARY KEY (id),
    CONSTRAINT breaks_opened_run_fk FOREIGN KEY (opened_run_id) REFERENCES reconciliation_runs (id),
    CONSTRAINT breaks_previous_break_fk FOREIGN KEY (previous_break_id) REFERENCES breaks (id),
    -- FR-BRK-1: the closed set of TDD 8.3.
    CONSTRAINT breaks_type_valid CHECK (break_type IN (
        'MISSING_IN_PSP', 'MISSING_IN_LEDGER', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH', 'DUPLICATE_LINE',
        'AMBIGUOUS_MATCH', 'MISSING_SETTLEMENT', 'BATCH_AMOUNT_MISMATCH', 'UNEXPECTED_BANK_LINE')),
    CONSTRAINT breaks_item_side_valid CHECK (item_side IN ('LEDGER', 'PSP', 'BANK', 'BATCH')),
    CONSTRAINT breaks_status_valid CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED')),
    CONSTRAINT breaks_resolution_code_valid CHECK (resolution_code IS NULL OR resolution_code IN (
        'MATCHED_LATE', 'MATCHED_MANUALLY', 'ADJUSTMENT_REQUIRED_IN_LEDGER', 'PSP_ERROR_CONFIRMED',
        'BANK_ERROR_CONFIRMED', 'WRITTEN_OFF', 'FALSE_POSITIVE', 'DUPLICATE_CONFIRMED')),
    CONSTRAINT breaks_related_items_array CHECK (jsonb_typeof(related_items) = 'array'),
    CONSTRAINT breaks_resolution_code_when_resolved CHECK ((status = 'RESOLVED') = (resolution_code IS NOT NULL)),
    CONSTRAINT breaks_resolved_at_when_resolved CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL)),
    CONSTRAINT breaks_resolved_after_opened CHECK (resolved_at IS NULL OR resolved_at >= opened_at),
    -- FR-BRK-3: a reopened break points at an earlier one, never at itself.
    CONSTRAINT breaks_not_its_own_previous CHECK (previous_break_id IS DISTINCT FROM id)
);

-- INV-7, FR-BRK-2: an item has at most one break that is not resolved.
CREATE UNIQUE INDEX breaks_one_unresolved_per_item ON breaks (item_side, item_id) WHERE status <> 'RESOLVED';

-- Append-only (TDD 8.4): V4 adds the trigger, and recon_app is granted SELECT and INSERT only.
CREATE TABLE break_events (
    id              BIGSERIAL   NOT NULL,
    break_id        UUID        NOT NULL,
    from_status     TEXT        NULL,
    to_status       TEXT        NOT NULL,
    resolution_code TEXT        NULL,
    actor           TEXT        NOT NULL,
    reason          TEXT        NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT break_events_pk PRIMARY KEY (id),
    CONSTRAINT break_events_break_fk FOREIGN KEY (break_id) REFERENCES breaks (id),
    CONSTRAINT break_events_from_status_valid CHECK (from_status IS NULL OR from_status IN ('OPEN', 'INVESTIGATING', 'RESOLVED')),
    CONSTRAINT break_events_to_status_valid CHECK (to_status IN ('OPEN', 'INVESTIGATING', 'RESOLVED')),
    CONSTRAINT break_events_resolution_code_valid CHECK (resolution_code IS NULL OR resolution_code IN (
        'MATCHED_LATE', 'MATCHED_MANUALLY', 'ADJUSTMENT_REQUIRED_IN_LEDGER', 'PSP_ERROR_CONFIRMED',
        'BANK_ERROR_CONFIRMED', 'WRITTEN_OFF', 'FALSE_POSITIVE', 'DUPLICATE_CONFIRMED')),
    CONSTRAINT break_events_actor_length CHECK (char_length(actor) BETWEEN 1 AND 100),
    CONSTRAINT break_events_reason_length CHECK (reason IS NULL OR char_length(reason) BETWEEN 1 AND 1000),
    CONSTRAINT break_events_resolution_code_when_resolved CHECK ((to_status = 'RESOLVED') = (resolution_code IS NOT NULL)),
    -- FR-BRK-3: RESOLVED is terminal, so no event leaves it; reopening opens a new break.
    CONSTRAINT break_events_not_from_resolved CHECK (from_status IS DISTINCT FROM 'RESOLVED'),
    -- The event that opens a break has no previous status and opens it.
    CONSTRAINT break_events_opening_event_opens CHECK (from_status IS NOT NULL OR to_status = 'OPEN')
);
