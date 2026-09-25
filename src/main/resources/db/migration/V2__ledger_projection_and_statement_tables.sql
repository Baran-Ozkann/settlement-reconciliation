-- The items reconciliation works on: ledger entries projected from the ledger's events, and the
-- statement files operators upload with their PSP and bank lines (TDD 10).
--
-- Every constraint and index is named. A violation then says which rule it broke, and each one is
-- listed in the test suite's DatabaseMechanism with a statement that violates it and a proof that
-- the violation goes through when that mechanism alone is removed. Money is BIGINT minor units
-- with a CHAR(3) currency (TDD 6). The currency CHECK is the ISO format only: which currencies are
-- accepted is configuration, and a fixed list here would make a configuration change fail at
-- insert time in a migration that can no longer be edited.
--
-- No privileges are granted to recon_app here. Each table is opened to it by the migration that
-- lands with the code that uses it, and only for the verbs that code issues.

-- Exactly as TDD 10 writes it. tx_type has no CHECK by exception (FR-LED-9): the ledger may add a
-- type, and a CHECK would reject a real money movement. created_at and value_date are null
-- together on five-field history (FR-LED-7), and ledger_entry_id is null there too.
CREATE TABLE ledger_entries (
    id              UUID        NOT NULL,
    event_id        BIGINT      NOT NULL,
    ledger_entry_id BIGINT      NULL,
    transaction_id  UUID        NOT NULL,
    account_id      UUID        NOT NULL,
    source_code     TEXT        NOT NULL,
    amount          BIGINT      NOT NULL,
    currency        CHAR(3)     NOT NULL,
    tx_type         TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NULL,
    value_date      DATE        NULL,
    received_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT ledger_entries_pk PRIMARY KEY (id),
    -- The event-id header: the deduplication key (FR-LED-3, INV-3).
    CONSTRAINT ledger_entries_event_id_unique UNIQUE (event_id),
    CONSTRAINT ledger_entries_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ledger_entries_created_at_value_date_together CHECK ((created_at IS NULL) = (value_date IS NULL))
);

-- The same ledger entry under a second event-id is a ledger fault (FR-LED-8): rejected, never
-- skipped. Partial, because five-field history has no entry id and there may be many such rows.
CREATE UNIQUE INDEX ledger_entries_ledger_entry_id_unique
    ON ledger_entries (ledger_entry_id) WHERE ledger_entry_id IS NOT NULL;

CREATE TABLE statement_files (
    id                  UUID        NOT NULL,
    source_code         TEXT        NOT NULL,
    statement_reference TEXT        NOT NULL,
    sha256              CHAR(64)    NOT NULL,
    sanitized_filename  TEXT        NOT NULL,
    size_bytes          BIGINT      NOT NULL,
    line_count          INTEGER     NOT NULL,
    status              TEXT        NOT NULL,
    error_summary       JSONB       NULL,
    uploaded_by         TEXT        NOT NULL,
    received_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT statement_files_pk PRIMARY KEY (id),
    CONSTRAINT statement_files_status_valid CHECK (status IN ('INGESTED', 'REJECTED')),
    CONSTRAINT statement_files_source_code_format CHECK (source_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT statement_files_statement_reference_not_empty CHECK (statement_reference <> ''),
    CONSTRAINT statement_files_sha256_format CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- FR-ING-9: only the sanitized name is ever stored.
    CONSTRAINT statement_files_filename_format CHECK (sanitized_filename ~ '^[A-Za-z0-9._-]{1,100}$'),
    CONSTRAINT statement_files_size_nonnegative CHECK (size_bytes >= 0),
    CONSTRAINT statement_files_line_count_nonnegative CHECK (line_count >= 0),
    -- FR-ING-7: a rejection carries its line numbers and error codes.
    CONSTRAINT statement_files_rejected_has_error_summary CHECK (status <> 'REJECTED' OR error_summary IS NOT NULL)
);

-- FR-ING-3 rejects a file "already ingested successfully". A rejected file must stay re-uploadable,
-- so both uniqueness rules cover ingested files only.
CREATE UNIQUE INDEX statement_files_sha256_ingested_unique
    ON statement_files (sha256) WHERE status = 'INGESTED';
CREATE UNIQUE INDEX statement_files_reference_ingested_unique
    ON statement_files (source_code, statement_reference) WHERE status = 'INGESTED';

-- The rules of TDD 7.1, so a line the parser should have rejected cannot be stored either.
-- An empty transaction_reference is stored as NULL, never as ''.
CREATE TABLE psp_lines (
    id               UUID    NOT NULL,
    file_id          UUID    NOT NULL,
    source_code      TEXT    NOT NULL,
    line_id          TEXT    NOT NULL,
    reference        TEXT    NULL,
    batch_id         TEXT    NOT NULL,
    type             TEXT    NOT NULL,
    transaction_date DATE    NOT NULL,
    value_date       DATE    NOT NULL,
    gross_amount     BIGINT  NOT NULL,
    fee_amount       BIGINT  NOT NULL,
    net_amount       BIGINT  NOT NULL,
    currency         CHAR(3) NOT NULL,
    CONSTRAINT psp_lines_pk PRIMARY KEY (id),
    CONSTRAINT psp_lines_file_fk FOREIGN KEY (file_id) REFERENCES statement_files (id),
    -- FR-ING-4, INV-3: a line is stored once per source, across all files.
    CONSTRAINT psp_lines_source_line_unique UNIQUE (source_code, line_id),
    CONSTRAINT psp_lines_source_code_format CHECK (source_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT psp_lines_line_id_format CHECK (line_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    CONSTRAINT psp_lines_batch_id_format CHECK (batch_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    CONSTRAINT psp_lines_reference_length CHECK (reference IS NULL OR char_length(reference) BETWEEN 1 AND 64),
    CONSTRAINT psp_lines_type_valid CHECK (type IN ('PAYMENT', 'REFUND', 'CHARGEBACK')),
    CONSTRAINT psp_lines_dates_in_order CHECK (transaction_date <= value_date),
    CONSTRAINT psp_lines_fee_nonnegative CHECK (fee_amount >= 0),
    CONSTRAINT psp_lines_net_is_gross_minus_fee CHECK (net_amount = gross_amount - fee_amount),
    -- Written so it says nothing about an unknown type: that is psp_lines_type_valid's rule, and a
    -- violation should break exactly one named constraint.
    CONSTRAINT psp_lines_gross_sign_matches_type CHECK (
        (type <> 'PAYMENT' OR gross_amount > 0) AND (type NOT IN ('REFUND', 'CHARGEBACK') OR gross_amount < 0)),
    CONSTRAINT psp_lines_currency_format CHECK (currency ~ '^[A-Z]{3}$')
);

-- The rules of TDD 7.2. Optional texts are NULL when absent, never ''.
CREATE TABLE bank_lines (
    id                 UUID    NOT NULL,
    file_id            UUID    NOT NULL,
    source_code        TEXT    NOT NULL,
    line_id            TEXT    NOT NULL,
    booking_date       DATE    NOT NULL,
    value_date         DATE    NOT NULL,
    amount             BIGINT  NOT NULL,
    currency           CHAR(3) NOT NULL,
    reference          TEXT    NULL,
    extracted_batch_id TEXT    NULL,
    description        TEXT    NULL,
    CONSTRAINT bank_lines_pk PRIMARY KEY (id),
    CONSTRAINT bank_lines_file_fk FOREIGN KEY (file_id) REFERENCES statement_files (id),
    CONSTRAINT bank_lines_source_line_unique UNIQUE (source_code, line_id),
    CONSTRAINT bank_lines_source_code_format CHECK (source_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT bank_lines_line_id_format CHECK (line_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    CONSTRAINT bank_lines_amount_nonzero CHECK (amount <> 0),
    CONSTRAINT bank_lines_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT bank_lines_reference_length CHECK (reference IS NULL OR char_length(reference) BETWEEN 1 AND 140),
    CONSTRAINT bank_lines_extracted_batch_id_format CHECK (
        extracted_batch_id IS NULL OR extracted_batch_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    CONSTRAINT bank_lines_description_length CHECK (description IS NULL OR char_length(description) BETWEEN 1 AND 140)
);
