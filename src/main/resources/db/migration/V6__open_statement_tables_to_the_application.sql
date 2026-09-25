-- JdbcStatementStore inserts files and their lines and reads them back. A file is recorded once in
-- its final state - INGESTED with its lines, or REJECTED in a transaction of its own (TDD 5.3) - and
-- a stored line is never changed or removed, so UPDATE and DELETE are granted on none of the three.
GRANT SELECT, INSERT ON statement_files TO recon_app;
GRANT SELECT, INSERT ON psp_lines TO recon_app;
GRANT SELECT, INSERT ON bank_lines TO recon_app;
