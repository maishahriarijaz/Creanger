-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 036: PRESENCE RPC
-- ============================================================================
-- The user_presence table (migration 008) tracks lightweight presence:
--   user_id UUID PK, status presence_status, last_seen_at, updated_at.
--
-- Realtime: the table is in the supabase_realtime publication (migration
-- 020), so every client can observe presence changes live (SELECT policy is
-- user_presence_select_all). Typing indicators are delivered over the same
-- Realtime channel as ephemeral presence metadata per the table comment -
-- clients broadcast typing through their realtime presence object, NOT
-- through this table.
--
-- The problem: GRANTs on user_presence are SELECT only for `authenticated`
-- (migration 008), so a client cannot upsert its own row via PostgREST. As
-- with mark_message_status() / add_reaction() etc., client mutations go
-- through a SECURITY DEFINER RPC instead:
--
--   set_presence(p_status, p_touch_last_seen) — scoped to
--   current_user_id(); validates the enum value; upserts the caller's row;
--   optionally refreshes last_seen_at (heartbeat) or leaves it untouched
--   (pure online/offline transition). Idempotent by nature: setting the same
--   status twice is a no-op write that still normalizes timestamps.
--
-- No DELETE path: presence rows live for the lifetime of the account
-- (ON DELETE CASCADE from users).
--
-- Client recovery after reconnect is a plain REST read of user_presence for
-- the interesting peer ids (RLS: SELECT allowed for all authenticated).
-- ============================================================================

DROP FUNCTION IF EXISTS public.set_presence(TEXT, BOOLEAN);

CREATE OR REPLACE FUNCTION public.set_presence(
    p_status TEXT,
    p_touch_last_seen BOOLEAN DEFAULT TRUE
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;

    -- Validate against the enum without trusting the client cast.
    IF NOT EXISTS (SELECT 1 FROM pg_enum e
                   JOIN pg_type t ON t.oid = e.enumtypid
                   WHERE t.typname = 'presence_status' AND e.enumlabel = p_status) THEN
        RAISE EXCEPTION 'invalid presence status';
    END IF;

    INSERT INTO public.user_presence (user_id, status, last_seen_at)
    VALUES (
        v_user_id,
        p_status::public.presence_status,
        CASE WHEN p_touch_last_seen THEN NOW() ELSE TO_TIMESTAMP(0) END
    )
    ON CONFLICT (user_id) DO UPDATE SET
        status = EXCLUDED.status,
        last_seen_at = CASE
            WHEN p_touch_last_seen THEN NOW()
            ELSE public.user_presence.last_seen_at
        END,
        updated_at = NOW();
END;
$$;

REVOKE ALL ON FUNCTION public.set_presence(TEXT, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.set_presence(TEXT, BOOLEAN) TO authenticated;
