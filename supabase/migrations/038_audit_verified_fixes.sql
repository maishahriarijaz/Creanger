-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 038: VERIFIED AUDIT FIXES
-- ============================================================================
-- Addresses mismatches found in the SQL <-> UI <-> backend consistency audit:
--
--  1. message_edits.previous_rich_text was referenced by migration 033
--     (edit_message INSERT/SELECT) but never created -> every content edit
--     failed with 42703 undefined_column. Added here as nullable JSONB,
--     matching messages.rich_text (033).
--
--  2. Pin/unpin wrote messages.metadata via direct REST PATCH, but the
--     least-privilege UPDATE grant (022) excludes metadata -> 403. A
--     dedicated SECURITY DEFINER pin_message() RPC now owns the metadata
--     mutation; clients must not be granted UPDATE on metadata.
--
--  3. drafts uniqueness (user_id, chat_id, topic_id) never dedupes main-chat
--     drafts because topic_id IS NULL (NULL <> NULL). A partial unique index
--     covers the NULL case; the original constraint keeps covering topics.
--
--  4. Bridge username normalization: check_username_available() lowercases
--     before validating, but handle_new_supabase_user() validated the raw
--     metadata case-sensitively, so 'Alice' passed the check yet stored
--     NULL. The bridge now lowercases first, so check/setup/metadata/
--     persisted value all share one normalized representation.
--
--  5. send_text_message 4-arg overload (022) is routed to the canonical
--     5-arg implementation (033) so rich_text has exactly one behavior.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. message_edits.previous_rich_text (nullable JSONB, like messages.rich_text)
-- ----------------------------------------------------------------------------

ALTER TABLE public.message_edits
    ADD COLUMN IF NOT EXISTS previous_rich_text JSONB;

COMMENT ON COLUMN public.message_edits.previous_rich_text IS
    'Previous rich_text at edit time (nullable: plain-text history has none).';

-- ----------------------------------------------------------------------------
-- 2. pin_message() RPC - least-privilege metadata mutation
-- ----------------------------------------------------------------------------
-- Contract (mirrors the former client read-merge-PATCH):
--   p_pinned = TRUE  -> metadata = metadata || {"pinned": true}
--   p_pinned = FALSE -> metadata = metadata - 'pinned' - 'pinned_at'
-- Other metadata keys are preserved. Idempotent both ways.
-- Authorization: caller must be a chat member AND (the message sender OR a
-- chat admin/owner). Nothing else may change protected message columns
-- through this path.

CREATE OR REPLACE FUNCTION public.pin_message(
    p_message_id UUID,
    p_pinned BOOLEAN DEFAULT TRUE
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
    v_meta JSONB;
BEGIN
    IF v_user_id IS NULL OR p_message_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;

    SELECT chat_id, sender_id, COALESCE(metadata, '{}'::JSONB)
      INTO v_chat_id, v_sender_id, v_meta
      FROM public.messages
     WHERE id = p_message_id
       AND deleted_at IS NULL;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'message not found or already deleted';
    END IF;

    IF NOT public.is_chat_member(v_chat_id) THEN
        RAISE EXCEPTION 'not a chat member';
    END IF;

    IF v_sender_id IS DISTINCT FROM v_user_id
       AND NOT public.is_chat_admin(v_chat_id) THEN
        RAISE EXCEPTION 'only the sender or a chat admin may pin messages';
    END IF;

    IF COALESCE(p_pinned, TRUE) THEN
        v_meta := v_meta || jsonb_build_object('pinned', TRUE);
    ELSE
        v_meta := v_meta - 'pinned' - 'pinned_at';
    END IF;

    UPDATE public.messages
       SET metadata = v_meta,
           updated_at = NOW()
     WHERE id = p_message_id;

    RETURN p_message_id;
END;
$$;

REVOKE ALL ON FUNCTION public.pin_message(UUID, BOOLEAN) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.pin_message(UUID, BOOLEAN) TO authenticated;

-- ----------------------------------------------------------------------------
-- 3. Main-chat draft uniqueness (topic_id IS NULL)
-- ----------------------------------------------------------------------------

CREATE UNIQUE INDEX IF NOT EXISTS drafts_unique_main_chat
    ON public.drafts (user_id, chat_id)
    WHERE topic_id IS NULL;

-- ----------------------------------------------------------------------------
-- 4. Bridge: normalize username with LOWER() before validation/storage
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.handle_new_supabase_user()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_username text;
    v_display text;
BEGIN
    -- Canonical normalization (matches CreangerUsernamePolicy.normalize and
    -- check_username_available's LOWER()): availability check, metadata and
    -- the persisted value all share this representation, so 'Alice' stores
    -- 'alice' instead of falling back to NULL.
    v_username := LOWER(NULLIF(TRIM(COALESCE(NEW.raw_user_meta_data->>'username', '')), ''));
    -- Drop malformed usernames instead of failing the insert: only values
    -- satisfying profiles' CHECK may reach the table. CITEXT lowercases.
    IF v_username IS NOT NULL AND (
        v_username !~ '^[a-z0-9._]{3,30}$'
        OR LEFT(v_username, 1) = '.'
        OR RIGHT(v_username, 1) = '.'
        OR POSITION('..' IN v_username) > 0
    ) THEN
        v_username := NULL;
    END IF;

    v_display := COALESCE(
        NULLIF(TRIM(COALESCE(NEW.raw_user_meta_data->>'display_name', '')), ''),
        NULLIF(TRIM(COALESCE(NEW.raw_user_meta_data->>'name', '')), ''),
        'User'
    );

    INSERT INTO public.users (id, status)
    VALUES (NEW.id, 'active')
    ON CONFLICT (id) DO NOTHING;

    BEGIN
        INSERT INTO public.profiles (user_id, username, first_name)
        VALUES (NEW.id, v_username, v_display);
    EXCEPTION WHEN unique_violation THEN
        -- Requested username lost a race; leave it unclaimed.
        INSERT INTO public.profiles (user_id, username, first_name)
        VALUES (NEW.id, NULL, v_display)
        ON CONFLICT (user_id) DO NOTHING;
    END;

    RETURN NEW;
END;
$$;

-- ----------------------------------------------------------------------------
-- 5. Route the legacy 4-arg send_text_message to the canonical 5-arg body
-- ----------------------------------------------------------------------------
-- The signature is preserved for compatibility, but behavior is now defined
-- exactly once (rich_text defaults to NULL, same as plain-text sends).

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
BEGIN
    RETURN public.send_text_message(
        p_chat_id, p_client_message_id, p_content, NULL, p_reply_to_message_id);
END;
$$;

REVOKE ALL ON FUNCTION public.send_text_message(UUID, TEXT, TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.send_text_message(UUID, TEXT, TEXT, UUID) TO authenticated;
