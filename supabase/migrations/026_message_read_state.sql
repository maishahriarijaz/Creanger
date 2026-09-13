-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 026: MESSAGE READ / DELIVERY STATE
-- ============================================================================
-- Per-message delivered/read state lives on messages.status (the enum added
-- in migration 004) and is advanced by recipients. The RLS policy
-- messages_update_sender (migration 017) already restricts direct UPDATE on
-- messages to the sender, and migration 022 narrowed the UPDATE column grant
-- to (content, edited_at, deleted_at, scheduled_at, status, updated_at).
--
-- The problem: a RECIPIENT who reads a message must advance messages.status
-- to 'read' (and 'delivered' when the client received it), but RLS blocks any
-- UPDATE by a non-sender (PostgREST would report a silent 204 on zero rows).
-- Direct writes are therefore not a viable channel, matching the
-- edit_message()/delete_message() precedent (migrations 022 / 025), which
-- route client mutations through SECURITY DEFINER RPCs.
--
-- This migration adds two RPCs (no table changes):
--
--  1. mark_message_status(p_message_id, p_status) — SECURITY DEFINER RPC that
--     advances a message's status to 'delivered' or 'read':
--      - authorization: only a CURRENT chat member may advance a status, and
--        the message author may never mark their own message (a sender cannot
--        read/deliver their own outgoing message)
--      - monotonic advance: pending < sent < delivered < read. A request that
--        does not advance (a retry echo, or an out-of-order delivered after a
--        read) is an idempotent no-op returning the same message id; a status
--        can never regress
--      - only 'delivered'/'read' are accepted as targets
--  2. get_message_statuses_since(p_chat_id, p_after_updated_at, p_limit) —
--     STABLE recovery API for reconnects. Status changes do NOT bump the
--     chat_seq cursor (get_messages_since cannot recover them), but the
--     set_messages_updated_at() BEFORE-UPDATE trigger (migration 017) stamps
--     every change, so status updates are recovered by updated_at. Returns
--     (message_id, status, updated_at) for status IN ('delivered','read') that
--     changed after the watermark cursor, ordered oldest first.
--
-- The row lock (FOR UPDATE) serializes a status advance against a concurrent
-- edit/delete so the monotonic check is race-free.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Status ordering (monotonic advance helper)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.message_status_rank(p_status public.message_status)
RETURNS INTEGER
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE p_status
        WHEN 'pending'   THEN 0
        WHEN 'sent'      THEN 1
        WHEN 'delivered' THEN 2
        WHEN 'read'      THEN 3
        WHEN 'failed'    THEN 0
        WHEN 'scheduled' THEN 0
        ELSE 0
    END
$$;

-- ============================================================================
-- 1. mark_message_status(): RECIPIENT-ONLY MONOTONIC ADVANCE RPC
-- ============================================================================

DROP FUNCTION IF EXISTS public.mark_message_status(UUID, public.message_status);

CREATE OR REPLACE FUNCTION public.mark_message_status(
    p_message_id UUID,
    p_status public.message_status
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_chat_id UUID;
    v_sender_id UUID;
    v_current_status public.message_status;
    v_target_rank INTEGER;
    v_current_rank INTEGER;
BEGIN
    IF v_user_id IS NULL OR p_message_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;

    -- Only delivery/read are recipient-maintained states; everything else is
    -- written by the sender/client or the send RPC.
    IF p_status NOT IN ('delivered', 'read') THEN
        RAISE EXCEPTION 'mark_message_status accepts only delivered or read';
    END IF;

    -- Row lock serializes this advance against a concurrent edit/delete.
    SELECT chat_id, sender_id, status
    INTO v_chat_id, v_sender_id, v_current_status
    FROM public.messages
    WHERE id = p_message_id
      AND deleted_at IS NULL
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'message not found or already deleted';
    END IF;

    -- Authorization: only chat members may mark; the author cannot mark an
    -- own message (a sender does not deliver/read their own outgoing message).
    IF NOT public.is_chat_member(v_chat_id) THEN
        RAISE EXCEPTION 'only chat members may mark message status';
    END IF;

    IF v_sender_id = v_user_id THEN
        RAISE EXCEPTION 'the message author cannot mark their own message';
    END IF;

    -- Monotonic advance + idempotent no-op: never regress, never re-apply.
    v_target_rank := public.message_status_rank(p_status);
    v_current_rank := public.message_status_rank(v_current_status);
    IF v_target_rank <= v_current_rank THEN
        RETURN p_message_id;
    END IF;

    UPDATE public.messages
    SET status = p_status
    WHERE id = p_message_id;

    RETURN p_message_id;
END;
$$;

REVOKE ALL ON FUNCTION public.mark_message_status(UUID, public.message_status) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mark_message_status(UUID, public.message_status) TO authenticated;

-- ============================================================================
-- 2. get_message_statuses_since(): RECONNECT RECOVERY BY updated_at
-- ============================================================================

DROP FUNCTION IF EXISTS public.get_message_statuses_since(UUID, TIMESTAMPTZ, INTEGER);

CREATE OR REPLACE FUNCTION public.get_message_statuses_since(
    p_chat_id UUID,
    p_after_updated_at TIMESTAMPTZ,
    p_limit INTEGER DEFAULT 200
)
RETURNS TABLE (
    message_id UUID,
    status public.message_status,
    updated_at TIMESTAMPTZ
)
LANGUAGE plpgsql
STABLE
SET search_path = public
AS $$
BEGIN
    IF NOT public.is_chat_member(p_chat_id) THEN
        RETURN;
    END IF;

    -- NULL cursor means "everything" (a follower joining fresh); afterwards
    -- the watermark is the newest updated_at already applied locally.
    RETURN QUERY
    SELECT m.id, m.status, m.updated_at
    FROM public.messages m
    WHERE m.chat_id = p_chat_id
      AND m.deleted_at IS NULL
      AND m.status IN ('delivered', 'read')
      AND (p_after_updated_at IS NULL OR m.updated_at > p_after_updated_at)
    ORDER BY m.updated_at ASC
    LIMIT p_limit;
END;
$$;

REVOKE ALL ON FUNCTION public.get_message_statuses_since(UUID, TIMESTAMPTZ, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_message_statuses_since(UUID, TIMESTAMPTZ, INTEGER) TO authenticated;