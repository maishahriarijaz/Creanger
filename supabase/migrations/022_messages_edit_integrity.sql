-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 022: MESSAGE EDIT INTEGRITY
-- ============================================================================
--
-- Mandatory Fix 4 (idempotent send must not mutate an existing message) and
-- Mandatory Fix 5 (edit history must be tamper-proof / RPC-only).
--
--  1. send_text_message(): a retry with the SAME client_message_id no longer
--     rewrites content / bumps edited_at. The ON CONFLICT branch is DO NOTHING
--     and the pre-existing message id is returned. A client crash-loop can no
--     longer silently overwrite what the user typed.
--  2. edit_message(): NEW SECURITY DEFINER RPC that performs an edit ATOMICALLY:
--     it locks the message row (serializing concurrent edits), asserts the
--     caller is the author, and writes BOTH messages.content/edited_at AND the
--     message_edits history row in one function call. Clients can no longer
--     update content directly (removed from the UPDATE column grant) and can no
--     longer INSERT fabricated edit-history rows (INSERT revoked, policy
--     dropped). Edit history is now an append-only log written only by the RPC.
--  3. Idempotent edit: editing to the current content is a no-op and does not
--     append a history row.
-- ============================================================================

-- ============================================================================
-- 1. FIX 4: IDEMPOTENT SEND WITHOUT CONTENT MUTATION
-- ============================================================================

CREATE OR REPLACE FUNCTION public.send_text_message(
    p_chat_id UUID,
    p_client_message_id TEXT,
    p_content TEXT,
    p_reply_to_message_id UUID DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SET search_path = public
AS $$
DECLARE
    v_id UUID;
BEGIN
    IF p_client_message_id IS NULL THEN
        RAISE EXCEPTION 'client_message_id is required for idempotent send';
    END IF;

    INSERT INTO public.messages (
        chat_id, sender_id, message_type, content, reply_to_message_id,
        client_message_id, status
    )
    VALUES (
        p_chat_id, public.current_user_id(), 'text', p_content, p_reply_to_message_id,
        p_client_message_id, 'sent'
    )
    ON CONFLICT (chat_id, sender_id, client_message_id)
        WHERE client_message_id IS NOT NULL
    DO NOTHING
    RETURNING id INTO v_id;

    -- A retry with the same client id must NEVER overwrite the original
    -- content or bump edited_at. Return the pre-existing row instead.
    IF v_id IS NULL THEN
        SELECT id INTO v_id
        FROM public.messages
        WHERE chat_id = p_chat_id
          AND sender_id = public.current_user_id()
          AND client_message_id = p_client_message_id;
    END IF;

    RETURN v_id;
END;
$$;

GRANT EXECUTE ON FUNCTION public.send_text_message(UUID, TEXT, TEXT, UUID) TO authenticated;

-- ============================================================================
-- 2. FIX 5: edit_message() RPC - ATOMIC EDIT + APPEND-ONLY HISTORY
-- ============================================================================

DROP FUNCTION IF EXISTS public.edit_message(UUID, TEXT);

CREATE OR REPLACE FUNCTION public.edit_message(
    p_message_id UUID,
    p_new_content TEXT
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_previous_content TEXT;
    v_next_seq INTEGER;
    v_now TIMESTAMPTZ := NOW();
BEGIN
    IF v_user_id IS NULL OR p_message_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;

    IF p_new_content IS NULL OR btrim(p_new_content) = '' THEN
        RAISE EXCEPTION 'new content cannot be empty';
    END IF;

    -- Row lock serializes concurrent edits on the same message so
    -- edit_sequence is computed race-free and the edit is atomic.
    SELECT content INTO v_previous_content
    FROM public.messages
    WHERE id = p_message_id
      AND deleted_at IS NULL
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'message not found or already deleted';
    END IF;

    -- Authorization: only the message author may edit (owner identity is the
    -- caller, never caller-supplied).
    IF NOT EXISTS (
        SELECT 1 FROM public.messages
        WHERE id = p_message_id AND sender_id = v_user_id
    ) THEN
        RAISE EXCEPTION 'only the message author may edit this message';
    END IF;

    -- Idempotent edit: same content is a no-op (no fabricated history row).
    IF v_previous_content IS NOT DISTINCT FROM p_new_content THEN
        RETURN p_message_id;
    END IF;

    UPDATE public.messages
    SET content = p_new_content,
        edited_at = v_now
    WHERE id = p_message_id;

    SELECT COALESCE(MAX(edit_sequence), 0) + 1 INTO v_next_seq
    FROM public.message_edits
    WHERE message_id = p_message_id;

    INSERT INTO public.message_edits (message_id, previous_content, edit_sequence, created_at)
    VALUES (p_message_id, v_previous_content, v_next_seq, v_now);

    RETURN p_message_id;
END;
$$;

REVOKE ALL ON FUNCTION public.edit_message(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.edit_message(UUID, TEXT) TO authenticated;

-- ============================================================================
-- 3. LEAST PRIVILEGE: NO CLIENT WRITES TO EDIT STATE
-- ============================================================================
-- Content + edited_at are written exclusively by edit_message(). Clients keep
-- delivery lifecycle columns (status / scheduled_at / deleted_at for soft
-- self-delete).

REVOKE UPDATE ON messages FROM authenticated;
GRANT UPDATE (deleted_at, scheduled_at, status, updated_at) ON messages TO authenticated;

-- message_edits is an append-only history log. Only edit_message() (SECURITY
-- DEFINER) may INSERT. Reading history stays open to chat members.

DROP POLICY IF EXISTS message_edits_insert_sender ON message_edits;
REVOKE ALL ON message_edits FROM authenticated;
GRANT SELECT ON message_edits TO authenticated;
