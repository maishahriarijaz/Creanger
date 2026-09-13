-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 028: EDIT/DELETE CHANGE RECOVERY
-- ============================================================================
-- Message edits and soft-deletes are invisible to the two existing recovery
-- cursors:
--
--  1. get_messages_since() (migration 017) recovers only INSERTS: it filters
--     on messages.chat_seq > p_after_seq, and chat_seq is assigned once at
--     INSERT time. An edit_message() (migration 022) or delete_message()
--     (migration 025) changes the content/edited_at/deleted_at of a row the
--     client already knows, so its chat_seq never moves and the cursor sync
--     cannot revisit it.
--  2. get_message_statuses_since() (migration 026) recovers status changes
--     only (status IN ('delivered','read')); it returns neither content nor
--     deleted_at, so it can neither re-apply an edit nor drop a deleted row.
--
-- Every mutation on messages stamps messages.updated_at via the
-- set_messages_updated_at() BEFORE-UPDATE trigger (migration 017), so edits
-- and deletes are recoverable by updated_at -- the same anchor the status
-- watermark already uses. After a Realtime reconnect the client therefore
-- runs BOTH cursors: existing INSERTs via get_messages_since() + statuses via
-- get_message_statuses_since(), and this new RPC for edits/deletes.
--
-- Soft-delete tombstones are returned here explicitly (deleted_at IS NOT
-- NULL) so a client that missed the tombstone while offline can drop the
-- cached row; the member SELECT policy (migration 017) hides such rows from
-- ordinary queries, so this function must be SECURITY DEFINER to see them.
-- The caller's membership gates the whole result (mirroring is_chat_member(),
-- which itself resolves current_user_id() under SECURITY DEFINER), and rows
-- hidden by a per-user message_deletions mark are excluded exactly like the
-- member-select policy so a "delete for me/everyone" never resurrects.
--
-- Cursor semantics mirror get_message_statuses_since() (migration 026):
--   - p_after_updated_at is a watermark; NULL means "everything" (a follower
--     joining fresh). The client stores the newest updated_at it has applied
--     and resumes from it on the next reconnect.
--   - NULL p_after_updated_at is intended for THIS function only; every
--     subsequent call must pass a concrete watermark.
--   - rows are ordered updated_at ASC for an oldest-first apply (an edit then
--     a delete of the same row converges in the right order).
-- ============================================================================

DROP FUNCTION IF EXISTS public.get_message_changes_since(UUID, TIMESTAMPTZ, INTEGER);

CREATE OR REPLACE FUNCTION public.get_message_changes_since(
    p_chat_id UUID,
    p_after_updated_at TIMESTAMPTZ,
    p_limit INTEGER DEFAULT 200
)
RETURNS TABLE (
    chat_id UUID,
    message_id UUID,
    chat_seq BIGINT,
    sender_id UUID,
    message_type public.message_type,
    content TEXT,
    status public.message_status,
    client_message_id TEXT,
    reply_to_message_id UUID,
    created_at TIMESTAMPTZ,
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    -- Full projection (not just id/content/deleted) so a recovered edit row
    -- faithfully replaces the cached row without dropping status, reply
    -- metadata or the client_message_id (the local copy is trusted to differ
    -- from the server row ONLY in content/edited_at).
    RETURN QUERY
    SELECT m.chat_id, m.id, m.chat_seq, m.sender_id, m.message_type, m.content,
           m.status, m.client_message_id, m.reply_to_message_id,
           m.created_at, m.edited_at, m.deleted_at, m.updated_at
    FROM public.messages m
    WHERE m.chat_id = p_chat_id
      -- Non-members get nothing; members can see their own chat's rows only.
      AND public.is_chat_member(p_chat_id)
      AND (m.edited_at IS NOT NULL OR m.deleted_at IS NOT NULL)
      AND (p_after_updated_at IS NULL OR m.updated_at > p_after_updated_at)
      -- Mirror messages_select_member (migration 017): a per-user
      -- "delete for me"/"delete for everyone" mark must not surface the row.
      AND NOT EXISTS (
          SELECT 1 FROM public.message_deletions md
          WHERE md.message_id = m.id
            AND (md.user_id = public.current_user_id() OR md.is_for_everyone = TRUE)
      )
    ORDER BY m.updated_at ASC
    LIMIT p_limit;
END;
$$;

REVOKE ALL ON FUNCTION public.get_message_changes_since(UUID, TIMESTAMPTZ, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_message_changes_since(UUID, TIMESTAMPTZ, INTEGER) TO authenticated;