-- JdbcRunStore records a run and reads it back. Recording its outcome is an UPDATE that the run
-- orchestration issues, so that grant lands with it in Phase 5. A run is never deleted: its
-- matches and breaks point at it.
GRANT SELECT, INSERT ON reconciliation_runs TO recon_app;
