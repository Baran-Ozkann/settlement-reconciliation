-- INV-6, TDD 8.4: match_events and break_events are append-only, and the database enforces it
-- twice. recon_app is granted SELECT and INSERT only on them (by the migrations that open them to
-- the application). These triggers are the second defence: they refuse UPDATE, DELETE and TRUNCATE
-- from any role, the owner included, so a later grant made by mistake still cannot rewrite history.
--
-- The trigger raises rather than skipping the row: a trigger that returned NULL would let the
-- caller believe the change had been made. Its SQLSTATE is its own, RC001, and the message names
-- the trigger, so a refusal by the trigger cannot be mistaken for a refusal by the privilege check
-- (42501). Only the table owner can disable a trigger, and recon_app owns nothing.

CREATE FUNCTION reject_audit_change() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'append-only: % on % refused by trigger %', TG_OP, TG_TABLE_NAME, TG_NAME
        USING ERRCODE = 'RC001';
END
$$;

CREATE TRIGGER match_events_append_only
    BEFORE UPDATE OR DELETE ON match_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_change();

-- TRUNCATE removes rows without firing row triggers, so it needs a statement trigger of its own.
CREATE TRIGGER match_events_no_truncate
    BEFORE TRUNCATE ON match_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_change();

CREATE TRIGGER break_events_append_only
    BEFORE UPDATE OR DELETE ON break_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_change();

CREATE TRIGGER break_events_no_truncate
    BEFORE TRUNCATE ON break_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_change();
