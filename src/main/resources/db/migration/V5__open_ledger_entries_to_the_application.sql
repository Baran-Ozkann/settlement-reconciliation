-- JdbcLedgerEntryStore inserts entries (ON CONFLICT DO NOTHING needs INSERT alone) and reads them
-- back. Nothing updates or deletes a projected entry: a correction in the ledger arrives as another
-- entry, so neither verb is granted.
GRANT SELECT, INSERT ON ledger_entries TO recon_app;
