-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 040: TODO COMPLETION
-- ============================================================================
-- Closes every remaining TODO documented across the *.md reports:
--
--   1. close_friends stories: real audience table (was fail-closed placeholder).
--   2. Poll autocloser: wired trigger + service_role cron function
--      (was commented out in 013, service_role-only in 024).
--   3. send_text_message hardening: SECURITY DEFINER + explicit membership
--      check (was RLS-only, see FINAL_HARDENING §5.3).
--   4. Document plane debt (PHASE E2 §6): FTS index, document search RPC,
--      bulk delete RPC, preview_url column, recent-media preview RPC.
--   5. Sticker backend (docs/archive/CREANGER_LOCAL_FEATURES.md):
--      search / install / uninstall / trending RPCs over the existing
--      006 tables (no new tables, no schema duplication).
--
-- CONVENTIONS: SECURITY DEFINER + SET search_path = public,
-- REVOKE ALL FROM PUBLIC, GRANT to authenticated/service_role only.
-- Forward-only, idempotent (IF NOT EXISTS / OR REPLACE).
-- ============================================================================

-- ============================================================================
-- 1. CLOSE FRIENDS AUDIENCE
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.story_close_friends (
    owner_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    friend_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT story_close_friends_unique UNIQUE (owner_id, friend_id),
    CONSTRAINT story_close_friends_no_self CHECK (owner_id <> friend_id)
);

COMMENT ON TABLE public.story_close_friends IS
    'Close-friends audience lists. Owner manages their own list; can_view_story() resolves close_friends privacy against it.';

CREATE INDEX IF NOT EXISTS idx_story_close_friends_owner ON public.story_close_friends(owner_id);
CREATE INDEX IF NOT EXISTS idx_story_close_friends_friend ON public.story_close_friends(friend_id);

ALTER TABLE public.story_close_friends ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS story_close_friends_select_own ON public.story_close_friends;
CREATE POLICY story_close_friends_select_own ON public.story_close_friends
    FOR SELECT USING (owner_id = public.current_user_id() OR friend_id = public.current_user_id());

DROP POLICY IF EXISTS story_close_friends_insert_own ON public.story_close_friends;
CREATE POLICY story_close_friends_insert_own ON public.story_close_friends
    FOR INSERT WITH CHECK (owner_id = public.current_user_id());

DROP POLICY IF EXISTS story_close_friends_delete_own ON public.story_close_friends;
CREATE POLICY story_close_friends_delete_own ON public.story_close_friends
    FOR DELETE USING (owner_id = public.current_user_id());

GRANT SELECT, INSERT, DELETE ON public.story_close_friends TO authenticated;
GRANT ALL ON public.story_close_friends TO service_role;

-- Owner-scoped manage RPCs (idempotent).
CREATE OR REPLACE FUNCTION public.add_close_friend(p_friend_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
BEGIN
    IF v_user_id IS NULL OR p_friend_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;
    IF p_friend_id = v_user_id THEN
        RAISE EXCEPTION 'cannot add self as close friend';
    END IF;
    INSERT INTO public.story_close_friends (owner_id, friend_id)
    VALUES (v_user_id, p_friend_id)
    ON CONFLICT (owner_id, friend_id) DO NOTHING;
END;
$$;

CREATE OR REPLACE FUNCTION public.remove_close_friend(p_friend_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
BEGIN
    IF v_user_id IS NULL OR p_friend_id IS NULL THEN
        RETURN;
    END IF;
    DELETE FROM public.story_close_friends
    WHERE owner_id = v_user_id AND friend_id = p_friend_id;
END;
$$;

REVOKE ALL ON FUNCTION public.add_close_friend(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.add_close_friend(UUID) TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.remove_close_friend(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.remove_close_friend(UUID) TO authenticated, service_role;

-- can_view_story now resolves close_friends against the audience table.
-- Owner always sees own stories; close friend sees owner's close_friends
-- stories; nobody stays owner-only (fail closed).
CREATE OR REPLACE FUNCTION public.can_view_story(p_story_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.stories s
        WHERE s.id = p_story_id
          AND s.deleted_at IS NULL
          AND (s.expires_at > NOW() OR s.is_pinned = TRUE)
          AND (
              s.user_id = public.current_user_id()
              OR s.privacy = 'everyone'
              OR (s.privacy = 'custom' AND EXISTS (
                      SELECT 1 FROM public.story_custom_audience sca
                      WHERE sca.story_id = s.id
                        AND sca.user_id = public.current_user_id()
                  ))
              OR (s.privacy = 'close_friends' AND EXISTS (
                      SELECT 1 FROM public.story_close_friends scf
                      WHERE scf.owner_id = s.user_id
                        AND scf.friend_id = public.current_user_id()
                  ))
              -- nobody: owner-only (first branch); no extra branch on purpose.
          )
    );
$$;

REVOKE ALL ON FUNCTION public.can_view_story(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.can_view_story(UUID) TO authenticated, service_role;

-- Stories SELECT must also allow close friends (policies are OR-ed).
DROP POLICY IF EXISTS stories_select_close_friends ON public.stories;
CREATE POLICY stories_select_close_friends ON public.stories
    FOR SELECT USING (
        privacy = 'close_friends'
        AND deleted_at IS NULL
        AND (expires_at > NOW() OR is_pinned = TRUE)
        AND EXISTS (
            SELECT 1 FROM public.story_close_friends scf
            WHERE scf.owner_id = stories.user_id
              AND scf.friend_id = public.current_user_id()
        )
    );

-- ============================================================================
-- 2. POLL AUTOCLOSER (wired)
-- ============================================================================

-- Service-role cron entrypoint: closes every expired poll, returns count.
CREATE OR REPLACE FUNCTION public.close_expired_polls()
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_count INTEGER := 0;
BEGIN
    UPDATE public.polls
    SET closed_at = COALESCE(closed_at, NOW())
    WHERE closes_at IS NOT NULL
      AND closes_at <= NOW()
      AND closed_at IS NULL;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

REVOKE ALL ON FUNCTION public.close_expired_polls() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.close_expired_polls() TO service_role;

-- Row-level auto-close: an insert/update whose closes_at already passed is
-- stored closed immediately (no cron needed for the single-row path).
CREATE OR REPLACE FUNCTION public.trg_polls_autoclose()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF NEW.closes_at IS NOT NULL AND NEW.closes_at <= NOW() AND NEW.closed_at IS NULL THEN
        NEW.closed_at := NEW.closes_at;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_polls_autoclose ON public.polls;
CREATE TRIGGER trg_polls_autoclose
    BEFORE INSERT OR UPDATE OF closes_at ON public.polls
    FOR EACH ROW
    EXECUTE FUNCTION public.trg_polls_autoclose();

REVOKE ALL ON FUNCTION public.trg_polls_autoclose() FROM PUBLIC;

-- Votes on closed/expired polls are rejected (fail closed).
CREATE OR REPLACE FUNCTION public.trg_poll_votes_block_closed()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_closed_at TIMESTAMPTZ;
    v_closes_at TIMESTAMPTZ;
BEGIN
    SELECT closed_at, closes_at INTO v_closed_at, v_closes_at
    FROM public.polls WHERE id = NEW.poll_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'poll not found';
    END IF;
    IF v_closed_at IS NOT NULL OR (v_closes_at IS NOT NULL AND v_closes_at <= NOW()) THEN
        RAISE EXCEPTION 'poll is closed';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_poll_votes_block_closed ON public.poll_votes;
CREATE TRIGGER trg_poll_votes_block_closed
    BEFORE INSERT ON public.poll_votes
    FOR EACH ROW
    EXECUTE FUNCTION public.trg_poll_votes_block_closed();

REVOKE ALL ON FUNCTION public.trg_poll_votes_block_closed() FROM PUBLIC;

-- Legacy name from 013/024 keeps working as a service_role alias.
CREATE OR REPLACE FUNCTION public.auto_close_expired_polls()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    PERFORM public.close_expired_polls();
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION public.auto_close_expired_polls() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.auto_close_expired_polls() TO service_role;

-- ============================================================================
-- 3. send_text_message HARDENING (SECURITY DEFINER + membership gate)
-- ============================================================================
-- Was RLS-only (FINAL_HARDENING §5.3). Now DEFINER with its own explicit
-- authorization; RLS stays enabled as defense-in-depth. Idempotency
-- semantics unchanged (first write wins, never mutates content).

CREATE OR REPLACE FUNCTION public.send_text_message(
    p_chat_id UUID,
    p_client_message_id TEXT,
    p_content TEXT,
    p_rich_text JSONB DEFAULT NULL,
    p_reply_to_message_id UUID DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_id UUID;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;
    IF p_chat_id IS NULL OR p_client_message_id IS NULL THEN
        RAISE EXCEPTION 'chat_id and client_message_id are required for idempotent send';
    END IF;
    IF p_content IS NULL OR btrim(p_content) = '' THEN
        RAISE EXCEPTION 'content cannot be empty';
    END IF;
    IF NOT public.is_chat_member(p_chat_id) THEN
        RAISE EXCEPTION 'not a member of this chat';
    END IF;
    IF p_reply_to_message_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM public.messages m
        WHERE m.id = p_reply_to_message_id AND m.chat_id = p_chat_id
    ) THEN
        RAISE EXCEPTION 'reply target must belong to the same chat';
    END IF;

    INSERT INTO public.messages (
        chat_id, sender_id, message_type, content, rich_text, reply_to_message_id,
        client_message_id, status
    )
    VALUES (
        p_chat_id, v_user_id, 'text', p_content, p_rich_text, p_reply_to_message_id,
        p_client_message_id, 'sent'
    )
    ON CONFLICT (chat_id, sender_id, client_message_id)
        WHERE client_message_id IS NOT NULL
    DO NOTHING
    RETURNING id INTO v_id;

    IF v_id IS NULL THEN
        SELECT id INTO v_id
        FROM public.messages
        WHERE chat_id = p_chat_id
          AND sender_id = v_user_id
          AND client_message_id = p_client_message_id;
    END IF;

    RETURN v_id;
END;
$$;

REVOKE ALL ON FUNCTION public.send_text_message(UUID, TEXT, TEXT, JSONB, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.send_text_message(UUID, TEXT, TEXT, JSONB, UUID) TO authenticated, service_role;

-- 4-arg legacy overload routes to the canonical 5-arg implementation (038 §5).
CREATE OR REPLACE FUNCTION public.send_text_message(
    p_chat_id UUID,
    p_client_message_id TEXT,
    p_content TEXT,
    p_reply_to_message_id UUID
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN public.send_text_message(p_chat_id, p_client_message_id, p_content, NULL, p_reply_to_message_id);
END;
$$;

REVOKE ALL ON FUNCTION public.send_text_message(UUID, TEXT, TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.send_text_message(UUID, TEXT, TEXT, UUID) TO authenticated, service_role;

-- ============================================================================
-- 4. DOCUMENT PLANE DEBT (E2 §6)
-- ============================================================================

ALTER TABLE public.media ADD COLUMN IF NOT EXISTS preview_url TEXT;
COMMENT ON COLUMN public.media.preview_url IS
    'Short preview/animated URL for video/document (provider-neutral, e.g. Cloudinary eager).';

ALTER TABLE public.media ADD COLUMN IF NOT EXISTS thumbnail_url TEXT;

-- Full-text search index for message content (chat-scoped search).
CREATE INDEX IF NOT EXISTS idx_messages_content_fts
    ON public.messages USING gin(to_tsvector('english', content))
    WHERE deleted_at IS NULL;

-- Chat-list document preview index.
CREATE INDEX IF NOT EXISTS idx_messages_chat_created
    ON public.messages(chat_id, created_at DESC)
    WHERE deleted_at IS NULL;

-- Document-scoped search: membership-gated, filters media messages by text.
CREATE OR REPLACE FUNCTION public.search_documents_in_chat(
    p_chat_id UUID,
    p_search_query TEXT,
    p_limit INTEGER DEFAULT 50
)
RETURNS TABLE (
    message_id UUID,
    content TEXT,
    sender_id UUID,
    created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF NOT public.is_chat_member(p_chat_id) THEN
        RETURN;
    END IF;
    RETURN QUERY
    SELECT m.id, m.content, m.sender_id, m.created_at
    FROM public.messages m
    WHERE m.chat_id = p_chat_id
      AND m.deleted_at IS NULL
      AND m.message_type IN ('document', 'image', 'video', 'audio', 'voice')
      AND (p_search_query IS NULL OR m.content ILIKE '%' || p_search_query || '%')
    ORDER BY m.created_at DESC
    LIMIT p_limit;
END;
$$;

REVOKE ALL ON FUNCTION public.search_documents_in_chat(UUID, TEXT, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.search_documents_in_chat(UUID, TEXT, INTEGER) TO authenticated, service_role;

-- Bulk soft-delete for own messages (atomic, idempotent).
CREATE OR REPLACE FUNCTION public.bulk_delete_messages(p_message_ids UUID[])
RETURNS UUID[]
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_deleted UUID[];
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;
    IF p_message_ids IS NULL OR array_length(p_message_ids, 1) IS NULL THEN
        RETURN ARRAY[]::UUID[];
    END IF;
    UPDATE public.messages
    SET deleted_at = COALESCE(deleted_at, NOW())
    WHERE id = ANY (p_message_ids)
      AND sender_id = v_user_id
      AND deleted_at IS NULL
    RETURNING ARRAY_AGG(id) INTO v_deleted;
    RETURN COALESCE(v_deleted, ARRAY[]::UUID[]);
END;
$$;

REVOKE ALL ON FUNCTION public.bulk_delete_messages(UUID[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.bulk_delete_messages(UUID[]) TO authenticated, service_role;

-- Recent media for chat-list document preview (membership-gated).
CREATE OR REPLACE FUNCTION public.get_recent_media(
    p_chat_id UUID,
    p_limit INTEGER DEFAULT 4
)
RETURNS TABLE (
    message_id UUID,
    public_url TEXT,
    thumbnail_url TEXT,
    mime_type TEXT,
    created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF NOT public.is_chat_member(p_chat_id) THEN
        RETURN;
    END IF;
    RETURN QUERY
    SELECT m.id, med.public_url, med.thumbnail_url, med.mime_type, m.created_at
    FROM public.messages m
    JOIN public.message_attachments ma ON ma.message_id = m.id
    JOIN public.media med ON med.id = ma.media_id
    WHERE m.chat_id = p_chat_id
      AND m.deleted_at IS NULL
      AND med.deleted_at IS NULL
    ORDER BY m.created_at DESC
    LIMIT p_limit;
END;
$$;

REVOKE ALL ON FUNCTION public.get_recent_media(UUID, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_recent_media(UUID, INTEGER) TO authenticated, service_role;

-- ============================================================================
-- 5. STICKER BACKEND (CREANGER_LOCAL_FEATURES.md "still needs backend")
-- ============================================================================
-- Tables already exist (006). These RPCs give the client the missing
-- server surface: global search, trending, install/uninstall (with
-- install_count kept server-side), recent/favorite sync is client-local
-- (LocalStickerStore) with install state server-persisted.

CREATE OR REPLACE FUNCTION public.search_stickers(
    p_query TEXT,
    p_limit INTEGER DEFAULT 50
)
RETURNS TABLE (
    sticker_id UUID,
    set_id UUID,
    emoji TEXT,
    sticker_type public.sticker_type,
    position INTEGER
)
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT s.id, s.set_id, s.emoji, s.type, s.position
    FROM public.stickers s
    JOIN public.sticker_sets ss ON ss.id = s.set_id
    WHERE ss.is_archived = FALSE
      AND (p_query IS NULL OR p_query = ''
           OR s.emoji = p_query
           OR EXISTS (SELECT 1 FROM unnest(s.keywords) k WHERE k ILIKE '%' || p_query || '%'))
    ORDER BY ss.is_official DESC, ss.install_count DESC, s.position ASC
    LIMIT p_limit;
END;
$$;

REVOKE ALL ON FUNCTION public.search_stickers(TEXT, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.search_stickers(TEXT, INTEGER) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.trending_sticker_sets(p_limit INTEGER DEFAULT 20)
RETURNS TABLE (
    set_id UUID,
    name TEXT,
    title TEXT,
    install_count INTEGER,
    is_official BOOLEAN
)
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT ss.id, ss.name, ss.title, ss.install_count, ss.is_official
    FROM public.sticker_sets ss
    WHERE ss.is_archived = FALSE
    ORDER BY ss.is_official DESC, ss.install_count DESC, ss.created_at DESC
    LIMIT p_limit;
END;
$$;

REVOKE ALL ON FUNCTION public.trending_sticker_sets(INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.trending_sticker_sets(INTEGER) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.install_sticker_set(p_set_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
BEGIN
    IF v_user_id IS NULL OR p_set_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;
    INSERT INTO public.user_sticker_sets (user_id, set_id)
    VALUES (v_user_id, p_set_id)
    ON CONFLICT (user_id, set_id) DO NOTHING;
    UPDATE public.sticker_sets
    SET install_count = install_count + 1
    WHERE id = p_set_id
      AND NOT EXISTS (
          SELECT 1 FROM public.user_sticker_sets uss2
          WHERE uss2.user_id = v_user_id AND uss2.set_id = p_set_id
          AND uss2.installed_at < NOW() - INTERVAL '1 second'
      );
END;
$$;

CREATE OR REPLACE FUNCTION public.uninstall_sticker_set(p_set_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
BEGIN
    IF v_user_id IS NULL OR p_set_id IS NULL THEN
        RETURN;
    END IF;
    DELETE FROM public.user_sticker_sets
    WHERE user_id = v_user_id AND set_id = p_set_id;
    UPDATE public.sticker_sets
    SET install_count = GREATEST(install_count - 1, 0)
    WHERE id = p_set_id;
END;
$$;

REVOKE ALL ON FUNCTION public.install_sticker_set(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.install_sticker_set(UUID) TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.uninstall_sticker_set(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.uninstall_sticker_set(UUID) TO authenticated, service_role;

-- Mask-sticker lookup for arbitrary photos/documents (keyword-driven).
CREATE OR REPLACE FUNCTION public.get_mask_stickers(p_limit INTEGER DEFAULT 20)
RETURNS TABLE (
    sticker_id UUID,
    set_id UUID,
    emoji TEXT
)
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT s.id, s.set_id, s.emoji
    FROM public.stickers s
    JOIN public.sticker_sets ss ON ss.id = s.set_id
    WHERE ss.is_archived = FALSE
      AND EXISTS (SELECT 1 FROM unnest(s.keywords) k WHERE k ILIKE '%mask%')
    ORDER BY ss.install_count DESC
    LIMIT p_limit;
END;
$$;

REVOKE ALL ON FUNCTION public.get_mask_stickers(INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_mask_stickers(INTEGER) TO authenticated, service_role;
