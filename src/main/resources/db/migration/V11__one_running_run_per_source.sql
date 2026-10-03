-- TDD 5.3: one run per source at a time, enforced by the database. A run's RUNNING row is committed
-- in a transaction of its own before its work starts, so a second run for the same source fails
-- here, at its insert, whether it was started by an operator or after an ingestion, and whichever
-- instance started it. The row leaves the index when the run is set COMPLETED or FAILED.
CREATE UNIQUE INDEX reconciliation_runs_one_running_per_source
    ON reconciliation_runs (source_code) WHERE status = 'RUNNING';
