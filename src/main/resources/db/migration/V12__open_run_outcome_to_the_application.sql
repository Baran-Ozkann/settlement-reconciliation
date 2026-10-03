-- TDD 5.3: a run ends COMPLETED with its statistics, or FAILED, and a run a stopped instance left
-- RUNNING is set FAILED at startup. Those three columns are all the run lifecycle changes; the
-- source, the value-date range, the configuration snapshot, the start and who triggered the run
-- are written once, so the grant is on these columns alone (TDD 10).
GRANT UPDATE (status, stats, finished_at) ON reconciliation_runs TO recon_app;
