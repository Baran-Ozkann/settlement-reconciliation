-- JdbcBreakStore opens breaks, applies their transitions and reads them back with their history.
--
-- A transition changes three columns of a break and nothing else, so UPDATE is granted on those
-- three alone: a break's type, item, run and opening time cannot be rewritten through the
-- application's connection, whatever SQL reaches it. A break is never deleted: a resolved one stays,
-- and reopening creates a new one (FR-BRK-3).
GRANT SELECT, INSERT ON breaks TO recon_app;
GRANT UPDATE (status, resolution_code, resolved_at) ON breaks TO recon_app;

-- break_events is append-only (TDD 8.4, INV-6): SELECT and INSERT, and never more. The trigger in
-- V4 is the second defence. The id sequence is granted USAGE because a BIGSERIAL insert draws from
-- it.
GRANT SELECT, INSERT ON break_events TO recon_app;
GRANT USAGE ON SEQUENCE break_events_id_seq TO recon_app;
