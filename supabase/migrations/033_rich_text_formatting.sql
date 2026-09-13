-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 033: RICH TEXT FORMATTING
-- ============================================================================
-- Add rich text formatting support to messages table and update RPCs
-- ============================================================================

-- ============================================================================
-- 1. ADD rich_text COLUMN TO MESSAGES TABLE
-- ============================================================================

ALTER TABLE public.messages
ADD COLUMN rich_text JSONB DEFAULT NULL;

COMMENT ON COLUMN messages.rich_text IS 'Rich text formatting entities (TL_iv.RichText compatible format)';

-- Index for searching rich text content
CREATE INDEX idx_messages_rich_text_search ON messages USING gin(rich_text) WHERE rich_text IS NOT NULL;

-- ============================================================================
-- 2. UPDATE send_text_message RPC TO SUPPORT RICH TEXT
-- ============================================================================

CREATE OR REPLACE FUNCTION public.send_text_message(
    p_chat_id UUID,
    p_client_message_id TEXT,
    p_content TEXT,
    p_rich_text JSONB DEFAULT NULL,
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
        chat_id, sender_id, message_type, content, rich_text, reply_to_message_id,
        client_message_id, status
    )
    VALUES (
        p_chat_id, public.current_user_id(), 'text', p_content, p_rich_text, p_reply_to_message_id,
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

REVOKE ALL ON FUNCTION public.send_text_message(UUID, TEXT, TEXT, JSONB, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.send_text_message(UUID, TEXT, TEXT, JSONB, UUID) TO authenticated;

-- ============================================================================
-- 2. UPDATE edit_message RPC TO SUPPORT RICH TEXT
-- ============================================================================

DROP FUNCTION IF EXISTS public.edit_message(UUID, TEXT);

CREATE OR REPLACE FUNCTION public.edit_message(
    p_message_id UUID,
    p_new_content TEXT,
    p_rich_text JSONB DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_previous_content TEXT;
    v_previous_rich_text JSONB;
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
    SELECT content, rich_text INTO v_previous_content, v_previous_rich_text
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

    -- Idempotent edit: same content AND rich_text is a no-op (no fabricated history row).
    IF v_previous_content IS NOT DISTINCT FROM p_new_content
       AND (v_previous_rich_text IS NOT DISTINCT FROM p_rich_text OR (v_previous_rich_text IS NULL AND p_rich_text IS NULL)) THEN
        RETURN p_message_id;
    END IF;

    UPDATE public.messages
    SET content = p_new_content,
        rich_text = p_rich_text,
        edited_at = v_now
    WHERE id = p_message_id;

    SELECT COALESCE(MAX(edit_sequence), 0) + 1 INTO v_next_seq
    FROM public.message_edits
    WHERE message_id = p_message_id;

    INSERT INTO public.message_edits (message_id, previous_content, previous_rich_text, edit_sequence, created_at)
    VALUES (p_message_id, v_previous_content, v_previous_rich_text, v_next_seq, v_now);

    RETURN p_message_id;
END;
$$;

REVOKE ALL ON FUNCTION public.edit_message(UUID, TEXT, JSONB) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.edit_message(UUID, TEXT, JSONB) TO authenticated;
