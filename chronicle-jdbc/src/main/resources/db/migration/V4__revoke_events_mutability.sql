-- V4: Event Immutability Enforcement — database-level protection
-- [SECURITY] Even if application code has a bug, the database prevents event mutation.
-- Two complementary mechanisms:
--   1. REVOKE: removes UPDATE/DELETE privileges from PUBLIC (covers all roles without explicit grants)
--   2. TRIGGER: raises an exception for any attempted mutation, regardless of role grants
--
-- Note: In production, PUBLIC should be replaced with the specific application role.
-- The trigger provides defense-in-depth even if a role has been granted these privileges.

REVOKE UPDATE, DELETE ON events FROM PUBLIC;

CREATE OR REPLACE FUNCTION prevent_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- [SECURITY] Event Immutability — UPDATE and DELETE on events are categorically forbidden
    -- This function is the last line of defense: it fires even if REVOKE is bypassed
    RAISE EXCEPTION 'Events are immutable. UPDATE and DELETE are prohibited.';
END;
$$;

CREATE TRIGGER events_immutable
    BEFORE UPDATE OR DELETE ON events
    FOR EACH ROW
    EXECUTE FUNCTION prevent_event_mutation();
