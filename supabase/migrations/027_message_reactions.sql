-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 027: MESSAGE REACTIONS RPCs
-- ============================================================================
-- Reactions already have their storage table (message_reactions, migration
-- 004), its RLS policies (migration 017) and its realtime publication
-- (migration 020). A member's add/remove is an INSERT/DELETE on
-- message_reactions; the UNIQUE (message_id, user_id, reaction) constraint
-- allows one row per (user, emoji) and is what makes a re-add idempotent.
--
-- The problem: the RLS policies scope writes to user_id = current_user_id()
-- and membership, but a direct PostgREST INSERT still exposes validation to
-- the client (empty reaction, deleted message, non-member) and would need the
-- client to always echo its own user_id. As with edit_message() /
-- delete_message() / mark_message_status() (migrations 022 / 025 / 026),
-- client mutations go through SECURITY DEFINER RPCs instead:
--
--  1. add_reaction(p_message_id, p_reaction, p_is_custom_emoji,
--     p_custom_emoji_id) — SECURITY DEFINER RPC that inserts one reaction row
--     for the authenticated user:
--      - authorization: only a CURRENT chat member of the message's chat may
--        react (the row is user_id = current_user_id(); never a client-supplied
--        sender)
--      - validation: the message must exist and not be deleted; the reaction
--        string must be non-empty (mirrors the table CHECK)
--      - idempotent: re-adding the SAME (message, user, reaction) is a no-op
--        that still returns the message id (ON CONFLICT DO NOTHING); adding a
--        DIFFERENT reaction to the same message adds a NEW row (the UNIQUE
--        constraint is per reaction)
--  2. remove_reaction(p_message_id, p_reaction, p_is_custom_emoji,
--     p_custom_emoji_id) — SECURITY DEFINER RPC that deletes the caller's
--     matching reaction row:
--      - scoped to user_id = current_user_id(): a user can only ever remove
--        their own reaction (mirrors the message_reactions_delete_own policy)
--      - idempotent: removing a reaction that is not (or no longer) present is
--        a no-op that still returns the message id
--
-- Recovery of reactions missed while Realtime was down needs NO watermark
-- RPC: reactions can be deleted, so a cursor alone cannot reconstruct
-- removals. The client re-fetches the current row set for its loaded message
-- window via the REST data plane (message_reactions?message_id=in.(...)),
-- which is scoped by the message_reactions_select_member RLS policy, and
-- REPLACES its per-message reaction state.
-- ============================================================================

-- ============================================================================
-- 1. add_reaction(): MEMBER-SCOPED, IDEMPOTENT ADD
-- ============================================================================

DROP FUNCTION IF EXISTS public.add_reaction(UUID, TEXT, BOOLEAN, UUID);

CREATE OR REPLACE FUNCTION public.add_reaction(
    p_message_id UUID,
    p_reaction TEXT,
    p_is_custom_emoji BOOLEAN DEFAULT FALSE,
    p_custom_emoji_id UUID DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_chat_id UUID;
BEGIN
    IF v_user_id IS NULL OR p_message_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;

    IF p_reaction IS NULL OR p_reaction = '' THEN
        RAISE EXCEPTION 'reaction must not be empty';
    END IF;

    -- The message must exist for a chat the caller is (still) a member of.
    SELECT chat_id
    INTO v_chat_id
    FROM public.messages
    WHERE id = p_message_id
      AND deleted_at IS NULL;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'message not found or already deleted';
    END IF;

    IF NOT public.is_chat_member(v_chat_id) THEN
        RAISE EXCEPTION 'only chat members may react';
    END IF;

    -- Idempotent: re-adding the exact same (message, user, reaction) is a
    -- no-op that still confirms the message id. A different reaction on the
    -- same message is a new row (the UNIQUE constraint is per reaction).
    INSERT INTO public.message_reactions
        (message_id, user_id, reaction, is_custom_emoji, custom_emoji_id)
    VALUES
        (p_message_id, v_user_id, p_reaction, p_is_custom_emoji,
         CASE WHEN p_is_custom_emoji THEN p_custom_emoji_id ELSE NULL END)
    ON CONFLICT ON CONSTRAINT message_reactions_unique DO NOTHING;

    RETURN p_message_id;
END;
$$;

REVOKE ALL ON FUNCTION public.add_reaction(UUID, TEXT, BOOLEAN, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.add_reaction(UUID, TEXT, BOOLEAN, UUID) TO authenticated;

-- ============================================================================
-- 2. remove_reaction(): OWN-SCOPED, IDEMPOTENT REMOVE
-- ============================================================================

DROP FUNCTION IF EXISTS public.remove_reaction(UUID, TEXT, BOOLEAN, UUID);

CREATE OR REPLACE FUNCTION public.remove_reaction(
    p_message_id UUID,
    p_reaction TEXT,
    p_is_custom_emoji BOOLEAN DEFAULT FALSE,
    p_custom_emoji_id UUID DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_chat_id UUID;
BEGIN
    IF v_user_id IS NULL OR p_message_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;

    IF p_reaction IS NULL OR p_reaction = '' THEN
        RAISE EXCEPTION 'reaction must not be empty';
    END IF;

    SELECT chat_id
    INTO v_chat_id
    FROM public.messages
    WHERE id = p_message_id
      AND deleted_at IS NULL;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'message not found or already deleted';
    END IF;

    -- Only the row the caller actually placed is ever removed; a reaction
    -- that is not (or no longer) present is an idempotent no-op.
    DELETE FROM public.message_reactions
    WHERE message_id = p_message_id
      AND user_id = v_user_id
      AND reaction = p_reaction
      AND (p_is_custom_emoji
           OR is_custom_emoji = FALSE
           OR custom_emoji_id = p_custom_emoji_id);

    RETURN p_message_id;
END;
$$;

REVOKE ALL ON FUNCTION public.remove_reaction(UUID, TEXT, BOOLEAN, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.remove_reaction(UUID, TEXT, BOOLEAN, UUID) TO authenticated;