-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 017: MESSAGES SECURITY / IDEMPOTENCY /
-- ORDERING / SYNC
-- ============================================================================
-- The messages family (messages, message_replies, message_edits,
-- message_deletions, message_reactions, chat_read_state, message_deliveries,
-- message_forwards) previously had NO RLS, NO policies and NO grants at all -
-- it was both unusable and unsecured. This migration:
--
--  1. Enables RLS and adds correct policies + grants to every message table.
--  2. Enforces sender/chat-membership authorization, per-user deletion hiding,
--     channel posting restrictions, and edit/delete authorization.
--  3. Adds message idempotency: client_message_id + unique partial index so a
--     client retry cannot create duplicate messages.
--  4. Adds deterministic per-chat ordering: chat_sequences counter + messages
--     .chat_seq + (chat_id, chat_seq) unique index -> safe cursor for sync.
--  5. Adds updated_at for change tracking and a get_messages_since() sync API.
--  6. Validates reply_to_message_id / topic_id belong to the same chat.
--  7. Converts data-integrity triggers that write other tables (chats,
--     chat_read_state) to SECURITY DEFINER so they keep working now that RLS
--     blocks those writes for the invoking client.
--  8. Hardens RPCs: mark_chat_as_read() no longer trusts a caller-supplied
--     user id; get_user_chat_summary()/get_user_unread_count() ignore the
--     supplied id and always use the caller; search_messages_in_chat()
--     requires membership; adds send_text_message() idempotent send.
-- ============================================================================

-- ============================================================================
-- 1. SCHEMA CHANGES: IDEMPOTENCY + ORDERING + SYNC
-- ============================================================================

ALTER TABLE messages ADD COLUMN IF NOT EXISTS client_message_id TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS chat_seq BIGINT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Idempotency: one (chat, sender, client_message_id) key
CREATE UNIQUE INDEX IF NOT EXISTS messages_idempotency_key
    ON messages(chat_id, sender_id, client_message_id)
    WHERE client_message_id IS NOT NULL;

-- Deterministic per-chat ordering + sync cursor
CREATE UNIQUE INDEX IF NOT EXISTS messages_chat_seq_key ON messages(chat_id, chat_seq);

-- Per-chat monotonic counter (atomic, race-safe)
CREATE TABLE IF NOT EXISTS chat_sequences (
    chat_id UUID PRIMARY KEY REFERENCES chats(id) ON DELETE CASCADE,
    last_seq BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chat_sequences_nonneg CHECK (last_seq >= 0)
);

CREATE OR REPLACE FUNCTION public.assign_chat_seq()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_seq BIGINT;
BEGIN
    INSERT INTO public.chat_sequences (chat_id, last_seq)
    VALUES (NEW.chat_id, 1)
    ON CONFLICT (chat_id) DO UPDATE SET last_seq = public.chat_sequences.last_seq + 1
    RETURNING last_seq INTO v_seq;

    NEW.chat_seq := v_seq;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trigger_assign_chat_seq ON messages;
CREATE TRIGGER trigger_assign_chat_seq
    BEFORE INSERT ON messages
    FOR EACH ROW
    WHEN (NEW.chat_seq IS NULL)
    EXECUTE FUNCTION public.assign_chat_seq();

-- Change tracking for sync
CREATE OR REPLACE FUNCTION public.set_messages_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trigger_messages_updated_at ON messages;
CREATE TRIGGER trigger_messages_updated_at
    BEFORE UPDATE ON messages
    FOR EACH ROW
    EXECUTE FUNCTION public.set_messages_updated_at();

-- ============================================================================
-- 2. CONTEXT VALIDATION (reply / topic must belong to the same chat)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.validate_message_context()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_reply_chat UUID;
BEGIN
    IF NEW.reply_to_message_id IS NOT NULL THEN
        SELECT chat_id INTO v_reply_chat
        FROM public.messages WHERE id = NEW.reply_to_message_id;
        IF v_reply_chat IS NULL OR v_reply_chat <> NEW.chat_id THEN
            RAISE EXCEPTION 'reply_to_message must belong to the same chat';
        END IF;
    END IF;

    IF NEW.topic_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM public.topics
            WHERE id = NEW.topic_id AND chat_id = NEW.chat_id
        ) THEN
            RAISE EXCEPTION 'topic must belong to the same chat';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trigger_validate_message_context ON messages;
CREATE TRIGGER trigger_validate_message_context
    BEFORE INSERT OR UPDATE ON messages
    FOR EACH ROW
    EXECUTE FUNCTION public.validate_message_context();

-- ============================================================================
-- 3. MAINTENANCE TRIGGERS -> SECURITY DEFINER
-- ============================================================================
-- These write rows in other RLS-protected tables (chats.updated_at,
-- chat_read_state) on behalf of the invoking client; RLS would otherwise
-- silently block or fail them.

CREATE OR REPLACE FUNCTION public.update_chat_on_message()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    UPDATE public.chats SET updated_at = NEW.created_at WHERE id = NEW.chat_id;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.update_chat_on_member_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    UPDATE public.chats SET updated_at = NOW() WHERE id = NEW.chat_id;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.increment_unread_on_message()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    UPDATE public.chat_read_state
    SET unread_count = unread_count + 1, updated_at = NOW()
    WHERE chat_id = NEW.chat_id AND user_id <> NEW.sender_id;

    INSERT INTO public.chat_read_state (chat_id, user_id, last_read_message_id, last_read_at, unread_count)
    VALUES (NEW.chat_id, NEW.sender_id, NEW.id, NOW(), 0)
    ON CONFLICT (chat_id, user_id)
    DO UPDATE SET
        last_read_message_id = EXCLUDED.last_read_message_id,
        last_read_at = NOW(),
        unread_count = 0,
        updated_at = NOW();

    RETURN NEW;
END;
$$;

-- ============================================================================
-- 4. RLS + POLICIES + GRANTS
-- ============================================================================

ALTER TABLE messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_replies ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_edits ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_deletions ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_reactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_read_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_forwards ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- messages
-- ---------------------------------------------------------------------------

CREATE POLICY messages_select_member ON messages
    FOR SELECT USING (
        public.is_chat_member(chat_id)
        AND deleted_at IS NULL
        AND NOT EXISTS (
            SELECT 1 FROM public.message_deletions md
            WHERE md.message_id = messages.id
              AND (md.user_id = current_user_id() OR md.is_for_everyone = TRUE)
        )
    );

CREATE POLICY messages_insert_member ON messages
    FOR INSERT WITH CHECK (
        sender_id = current_user_id()
        AND public.is_chat_member(chat_id)
        AND is_system_message = FALSE
        AND (
            chat_id NOT IN (SELECT id FROM public.chats WHERE type = 'channel')
            OR EXISTS (
                SELECT 1 FROM public.chat_members cm
                WHERE cm.chat_id = messages.chat_id
                  AND cm.user_id = current_user_id()
                  AND cm.left_at IS NULL
                  AND cm.role IN ('admin', 'owner')
            )
        )
    );

CREATE POLICY messages_update_sender ON messages
    FOR UPDATE USING (sender_id = current_user_id())
    WITH CHECK (sender_id = current_user_id());
-- NOTE: chat_id / message_type / sender_id cannot be rewritten by the client
-- because they are not included in the UPDATE column grant below.

CREATE POLICY messages_delete_sender ON messages
    FOR DELETE USING (sender_id = current_user_id());

GRANT SELECT, INSERT ON messages TO authenticated;
GRANT UPDATE (content, edited_at, deleted_at, scheduled_at, status, updated_at) ON messages TO authenticated;
GRANT DELETE ON messages TO authenticated;
GRANT ALL ON messages TO service_role;

-- ---------------------------------------------------------------------------
-- message_replies
-- ---------------------------------------------------------------------------

CREATE POLICY message_replies_select_member ON message_replies
    FOR SELECT USING (
        message_id IN (
            SELECT id FROM public.messages WHERE public.is_chat_member(chat_id)
        )
    );

CREATE POLICY message_replies_insert_sender ON message_replies
    FOR INSERT WITH CHECK (
        message_id IN (
            SELECT id FROM public.messages WHERE sender_id = current_user_id()
        )
    );

CREATE POLICY message_replies_delete_sender ON message_replies
    FOR DELETE USING (
        message_id IN (
            SELECT id FROM public.messages WHERE sender_id = current_user_id()
        )
    );

GRANT SELECT, INSERT, DELETE ON message_replies TO authenticated;
GRANT ALL ON message_replies TO service_role;

-- ---------------------------------------------------------------------------
-- message_edits (append-only edit history)
-- ---------------------------------------------------------------------------

CREATE POLICY message_edits_select_member ON message_edits
    FOR SELECT USING (
        message_id IN (
            SELECT id FROM public.messages WHERE public.is_chat_member(chat_id)
        )
    );

CREATE POLICY message_edits_insert_sender ON message_edits
    FOR INSERT WITH CHECK (
        message_id IN (
            SELECT id FROM public.messages WHERE sender_id = current_user_id()
        )
    );

GRANT SELECT, INSERT ON message_edits TO authenticated;
GRANT ALL ON message_edits TO service_role;

-- ---------------------------------------------------------------------------
-- message_deletions
-- ---------------------------------------------------------------------------

CREATE POLICY message_deletions_select_own ON message_deletions
    FOR SELECT USING (user_id = current_user_id());

CREATE POLICY message_deletions_insert_member ON message_deletions
    FOR INSERT WITH CHECK (
        user_id = current_user_id()
        AND message_id IN (
            SELECT id FROM public.messages WHERE public.is_chat_member(chat_id)
        )
        AND (
            is_for_everyone = FALSE
            OR message_id IN (
                SELECT id FROM public.messages WHERE sender_id = current_user_id()
            )
        )
    );

GRANT SELECT, INSERT ON message_deletions TO authenticated;
GRANT ALL ON message_deletions TO service_role;

-- ---------------------------------------------------------------------------
-- message_reactions
-- ---------------------------------------------------------------------------

CREATE POLICY message_reactions_select_member ON message_reactions
    FOR SELECT USING (
        message_id IN (
            SELECT id FROM public.messages WHERE public.is_chat_member(chat_id)
        )
    );

CREATE POLICY message_reactions_insert_member ON message_reactions
    FOR INSERT WITH CHECK (
        user_id = current_user_id()
        AND message_id IN (
            SELECT id FROM public.messages WHERE public.is_chat_member(chat_id)
        )
    );

CREATE POLICY message_reactions_update_own ON message_reactions
    FOR UPDATE USING (user_id = current_user_id())
    WITH CHECK (user_id = current_user_id());

CREATE POLICY message_reactions_delete_own ON message_reactions
    FOR DELETE USING (user_id = current_user_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON message_reactions TO authenticated;
GRANT ALL ON message_reactions TO service_role;

-- ---------------------------------------------------------------------------
-- chat_read_state
-- ---------------------------------------------------------------------------

CREATE POLICY chat_read_state_select_own ON chat_read_state
    FOR SELECT USING (user_id = current_user_id());

CREATE POLICY chat_read_state_insert_own ON chat_read_state
    FOR INSERT WITH CHECK (user_id = current_user_id());

CREATE POLICY chat_read_state_update_own ON chat_read_state
    FOR UPDATE USING (user_id = current_user_id())
    WITH CHECK (user_id = current_user_id());

CREATE POLICY chat_read_state_delete_own ON chat_read_state
    FOR DELETE USING (user_id = current_user_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON chat_read_state TO authenticated;
GRANT ALL ON chat_read_state TO service_role;

-- ---------------------------------------------------------------------------
-- message_deliveries
-- ---------------------------------------------------------------------------

CREATE POLICY message_deliveries_select_own ON message_deliveries
    FOR SELECT USING (user_id = current_user_id());

CREATE POLICY message_deliveries_insert_own ON message_deliveries
    FOR INSERT WITH CHECK (user_id = current_user_id());

CREATE POLICY message_deliveries_delete_own ON message_deliveries
    FOR DELETE USING (user_id = current_user_id());

-- Prevent duplicate "all devices" delivery rows (NULL device_id is ignored by
-- the existing UNIQUE constraint).
CREATE UNIQUE INDEX IF NOT EXISTS message_deliveries_all_devices_unique
    ON message_deliveries(message_id, user_id) WHERE device_id IS NULL;

GRANT SELECT, INSERT, DELETE ON message_deliveries TO authenticated;
GRANT ALL ON message_deliveries TO service_role;

-- ---------------------------------------------------------------------------
-- message_forwards
-- ---------------------------------------------------------------------------

CREATE POLICY message_forwards_select_member ON message_forwards
    FOR SELECT USING (
        public.is_chat_member(forwarded_to_chat_id)
    );

CREATE POLICY message_forwards_insert_forwarder ON message_forwards
    FOR INSERT WITH CHECK (
        forwarded_by = current_user_id()
        AND public.is_chat_member(forwarded_to_chat_id)
        AND original_message_id IN (
            SELECT id FROM public.messages WHERE public.is_chat_member(chat_id)
        )
    );

GRANT SELECT, INSERT ON message_forwards TO authenticated;
GRANT ALL ON message_forwards TO service_role;

-- ============================================================================
-- 5. HARDENED RPCs
-- ============================================================================

-- mark_chat_as_read: never trusts a caller-supplied user id.
DROP FUNCTION IF EXISTS public.mark_chat_as_read(UUID, UUID);

CREATE OR REPLACE FUNCTION public.mark_chat_as_read(p_chat_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SET search_path = public
AS $$
DECLARE
    v_last_message_id UUID;
BEGIN
    IF NOT public.is_chat_member(p_chat_id) THEN
        RETURN;
    END IF;

    SELECT id INTO v_last_message_id
    FROM public.messages
    WHERE chat_id = p_chat_id AND deleted_at IS NULL
    ORDER BY chat_seq DESC NULLS LAST, created_at DESC
    LIMIT 1;

    INSERT INTO public.chat_read_state (chat_id, user_id, last_read_message_id, last_read_at, unread_count)
    VALUES (p_chat_id, public.current_user_id(), v_last_message_id, NOW(), 0)
    ON CONFLICT (chat_id, user_id)
    DO UPDATE SET
        last_read_message_id = EXCLUDED.last_read_message_id,
        last_read_at = NOW(),
        unread_count = 0,
        updated_at = NOW();
END;
$$;

GRANT EXECUTE ON FUNCTION public.mark_chat_as_read(UUID) TO authenticated;

-- get_user_chat_summary: always resolves the caller; supplied id is ignored.
CREATE OR REPLACE FUNCTION public.get_user_chat_summary(p_user_id UUID)
RETURNS TABLE (
    chat_id UUID,
    chat_type public.chat_type,
    title TEXT,
    last_message_at TIMESTAMPTZ,
    unread_count INTEGER,
    member_count BIGINT
)
LANGUAGE plpgsql
STABLE
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        c.id AS chat_id,
        c.type AS chat_type,
        c.title,
        MAX(m.created_at) AS last_message_at,
        COALESCE(crs.unread_count, 0) AS unread_count,
        (SELECT COUNT(*) FROM public.chat_members cm WHERE cm.chat_id = c.id AND cm.left_at IS NULL) AS member_count
    FROM public.chats c
    JOIN public.chat_members cm
      ON cm.chat_id = c.id AND cm.user_id = public.current_user_id() AND cm.left_at IS NULL
    LEFT JOIN public.messages m ON m.chat_id = c.id AND m.deleted_at IS NULL
    LEFT JOIN public.chat_read_state crs ON crs.chat_id = c.id AND crs.user_id = public.current_user_id()
    WHERE c.deleted_at IS NULL
    GROUP BY c.id, c.type, c.title, crs.unread_count
    ORDER BY MAX(m.created_at) DESC NULLS LAST;
END;
$$;

-- get_user_unread_count: always resolves the caller.
CREATE OR REPLACE FUNCTION public.get_user_unread_count(p_user_id UUID)
RETURNS INTEGER
LANGUAGE plpgsql
STABLE
SET search_path = public
AS $$
DECLARE
    v_count INTEGER;
BEGIN
    SELECT COALESCE(SUM(unread_count), 0) INTO v_count
    FROM public.chat_read_state
    WHERE user_id = public.current_user_id();
    RETURN v_count;
END;
$$;

-- search_messages_in_chat: requires chat membership.
DROP FUNCTION IF EXISTS public.search_messages_in_chat(UUID, TEXT, INTEGER);

CREATE OR REPLACE FUNCTION public.search_messages_in_chat(
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
STABLE
SET search_path = public
AS $$
BEGIN
    IF NOT public.is_chat_member(p_chat_id) THEN
        RETURN;
    END IF;

    RETURN QUERY
    SELECT m.id AS message_id, m.content, m.sender_id, m.created_at
    FROM public.messages m
    WHERE m.chat_id = p_chat_id
      AND m.deleted_at IS NULL
      AND m.content ILIKE '%' || p_search_query || '%'
    ORDER BY m.created_at DESC
    LIMIT p_limit;
END;
$$;

GRANT EXECUTE ON FUNCTION public.search_messages_in_chat(UUID, TEXT, INTEGER) TO authenticated;

-- ============================================================================
-- 6. SYNC / RECOVERY API + IDEMPOTENT SEND
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_messages_since(
    p_chat_id UUID,
    p_after_seq BIGINT,
    p_limit INTEGER DEFAULT 200
)
RETURNS TABLE (
    message_id UUID,
    chat_seq BIGINT,
    sender_id UUID,
    message_type public.message_type,
    content TEXT,
    created_at TIMESTAMPTZ,
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
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

    RETURN QUERY
    SELECT m.id, m.chat_seq, m.sender_id, m.message_type, m.content,
           m.created_at, m.edited_at, m.deleted_at, m.updated_at
    FROM public.messages m
    WHERE m.chat_id = p_chat_id
      AND m.chat_seq > p_after_seq
    ORDER BY m.chat_seq ASC
    LIMIT p_limit;
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_messages_since(UUID, BIGINT, INTEGER) TO authenticated;

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
    DO UPDATE SET
        content = EXCLUDED.content,
        edited_at = NOW(),
        updated_at = NOW()
    RETURNING id INTO v_id;

    RETURN v_id;
END;
$$;

GRANT EXECUTE ON FUNCTION public.send_text_message(UUID, TEXT, TEXT, UUID) TO authenticated;
