-- JdbcMatchStore records a match, its items and its creation event, and reads them back.
--
-- match_events is append-only (TDD 8.4, INV-6): SELECT and INSERT, and never more. The trigger in
-- V4 is the second defence. The id sequence is granted USAGE because a BIGSERIAL insert draws from
-- it.
--
-- Reversing a match (FR-MAT-7) updates matches.status and match_items.active; that UPDATE lands with
-- the reversal in Phase 7. Neither table is ever deleted from: a reversed match is kept.
GRANT SELECT, INSERT ON matches TO recon_app;
GRANT SELECT, INSERT ON match_items TO recon_app;
GRANT SELECT, INSERT ON match_events TO recon_app;
GRANT USAGE ON SEQUENCE match_events_id_seq TO recon_app;
