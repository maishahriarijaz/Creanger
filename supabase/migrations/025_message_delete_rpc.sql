-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 025: MESSAGE DELETE (SOFT) RPC
-- ============================================================================
-- Follows the existing deletion model (audit-preserving soft delete, NOT a hard
-- delete): the messages.deleted_at column is the per-message tombstone, RLS
-- policies (messages_select_member, messages_update_sender) already hide
-- deleted rows from every chat member, and the schema's soft-delete strategy
-- documents messages.* as "deleted_at / not recoverable (privacy)".
--
-- Clients already hold UPDATE (deleted_at) and could PATCH messages directly;
-- the problem is that Row Level Security filters unauthorized writes to zero
-- rows, which PostgREST reports as a silent 204 success. An author-required
-- delete therefore cannot be distinguished from a non-author's no-op, and the
-- author cannot get an idempotent confirmation.
--
-- This migration adds delete_message(), a SECURITY DEFINER RPC mirroring the
-- edit_message() precedent (migration 022):
--
--  1. Authorization: only the message author may delete (sender is resolved
--     from the caller JWT via current_user_id(); never caller-supplied). A
--     non-author gets an observable P0001 error instead of a silent no-op.
--  2. Soft delete: sets messages.deleted_at = NOW(). The row and its
--     message_edits history remain intact on the server (audit-preserving);
--     it simply becomes invisible to every chat member via RLS.
--  3. Idempotent: deleting an already-soft-deleted message is a success that
--     returns the same id (a client retry after a timeout must not error).
--  4. No hard DELETE, no client INSERT into message_deletions — per-user
--     "delete for me" stays a separate mechanism (message_deletions), and the
--     messages_delete_sender hard-delete policy is deliberately NOT used.
-- ============================================================================

DROP FUNCTION IF EXISTS public.delete_message(UUID);

CREATE OR REPLACE FUNCTION public.delete_message(
    p_message_id UUID
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_sender_id UUID;
    v_deleted_at TIMESTAMPTZ;
BEGIN
    IF v_user_id IS NULL OR p_message_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;

    -- Row lock serializes a delete against a concurrent edit.
    SELECT sender_id, deleted_at INTO v_sender_id, v_deleted_at
    FROM public.messages
    WHERE id = p_message_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'message not found or already deleted';
    END IF;

    IF v_sender_id <> v_user_id THEN
        RAISE EXCEPTION 'only the message author may delete this message';
    END IF;

    -- Idempotent delete: a retry of an already-deleted message is a success.
    IF v_deleted_at IS NOT NULL THEN
        RETURN p_message_id;
    END IF;

    UPDATE public.messages
    SET deleted_at = NOW()
    WHERE id = p_message_id;

    RETURN p_message_id;
END;
$$;

REVOKE ALL ON FUNCTION public.delete_message(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.delete_message(UUID) TO authenticated;