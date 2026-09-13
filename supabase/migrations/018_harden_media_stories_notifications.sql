-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 018: HARDEN MEDIA / STORIES /
-- NOTIFICATIONS / POLLS
-- ============================================================================
--
--  1. message_attachments: a user can no longer attach ANOTHER user's private
--     media id to their message - media must be owned by the sender.
--  2. media SELECT policy rewritten via is_chat_member() (no recursion) and
--     scoped to media attached to messages in chats the caller belongs to.
--  3. stories: the SELECT policy self-referenced `stories` (recursion) and is
--     now can_view_story(); INSERT requires the story's media to be owned by
--     the user; UPDATE/ownership tightened.
--  4. story_views / story_reactions: inserts now require the story to actually
--     be viewable by the caller (no arbitrary view/reaction on private stories).
--  5. notifications: client may only update read_at (column grant); can no
--     longer rewrite actor_id/type/payload/ownership.
--  6. poll_votes: anonymous-poll vote identity is protected (no more
--     SELECT USING(TRUE)); votes require membership in the poll's chat;
--     single-choice polls reject a second vote.
--  7. saved_messages: media-only saves were impossible (content NOT NULL +
--     non-empty CHECK); fixed.
--  8. drafts: insert/update now require chat membership.
-- ============================================================================

-- ============================================================================
-- 1. MEDIA / MESSAGE_ATTACHMENTS
-- ============================================================================

DROP POLICY IF EXISTS media_select_owner ON media;
CREATE POLICY media_select_owner ON media
    FOR SELECT USING (
        owner_id = current_user_id()
        OR id IN (
            SELECT media_id FROM public.message_attachments
            WHERE message_id IN (
                SELECT id FROM public.messages WHERE public.is_chat_member(chat_id)
            )
        )
    );

DROP POLICY IF EXISTS media_update_owner ON media;
CREATE POLICY media_update_owner ON media
    FOR UPDATE USING (owner_id = current_user_id())
    WITH CHECK (owner_id = current_user_id());

-- Attaching requires ownership of the media.
DROP POLICY IF EXISTS message_attachments_insert_sender ON message_attachments;
CREATE POLICY message_attachments_insert_sender ON message_attachments
    FOR INSERT WITH CHECK (
        message_id IN (
            SELECT id FROM public.messages WHERE sender_id = current_user_id()
        )
        AND media_id IN (
            SELECT id FROM public.media WHERE owner_id = current_user_id()
        )
    );

DROP POLICY IF EXISTS message_attachments_delete_sender ON message_attachments;
CREATE POLICY message_attachments_delete_sender ON message_attachments
    FOR DELETE USING (
        message_id IN (
            SELECT id FROM public.messages WHERE sender_id = current_user_id()
        )
    );

-- ============================================================================
-- 2. STORIES
-- ============================================================================

DROP POLICY IF EXISTS stories_select_visible ON stories;
CREATE POLICY stories_select_visible ON stories
    FOR SELECT USING (
        user_id = current_user_id()
        OR public.can_view_story(id)
    );

DROP POLICY IF EXISTS stories_insert_owner ON stories;
CREATE POLICY stories_insert_owner ON stories
    FOR INSERT WITH CHECK (
        user_id = current_user_id()
        AND media_id IN (
            SELECT id FROM public.media WHERE owner_id = current_user_id()
        )
    );

DROP POLICY IF EXISTS stories_update_owner ON stories;
CREATE POLICY stories_update_owner ON stories
    FOR UPDATE USING (user_id = current_user_id())
    WITH CHECK (user_id = current_user_id());

DROP POLICY IF EXISTS stories_delete_owner ON stories;
CREATE POLICY stories_delete_owner ON stories
    FOR DELETE USING (user_id = current_user_id());

-- Story views: the viewer must actually be allowed to see the story.
DROP POLICY IF EXISTS story_views_insert_viewer ON story_views;
CREATE POLICY story_views_insert_viewer ON story_views
    FOR INSERT WITH CHECK (
        viewer_id = current_user_id()
        AND public.can_view_story(story_id)
    );

DROP POLICY IF EXISTS story_views_select_owner_or_viewer ON story_views;
CREATE POLICY story_views_select_owner_or_viewer ON story_views
    FOR SELECT USING (
        viewer_id = current_user_id()
        OR story_id IN (
            SELECT id FROM public.stories WHERE user_id = current_user_id()
        )
    );

-- Story reactions: only on stories the caller can view.
DROP POLICY IF EXISTS story_reactions_insert_own ON story_reactions;
CREATE POLICY story_reactions_insert_own ON story_reactions
    FOR INSERT WITH CHECK (
        user_id = current_user_id()
        AND public.can_view_story(story_id)
    );

DROP POLICY IF EXISTS story_reactions_update_own ON story_reactions;
CREATE POLICY story_reactions_update_own ON story_reactions
    FOR UPDATE USING (user_id = current_user_id())
    WITH CHECK (user_id = current_user_id());

DROP POLICY IF EXISTS story_reactions_delete_own ON story_reactions;
CREATE POLICY story_reactions_delete_own ON story_reactions
    FOR DELETE USING (user_id = current_user_id());

-- ============================================================================
-- 3. NOTIFICATIONS: CLIENT CAN ONLY TOUCH read_at
-- ============================================================================

REVOKE UPDATE ON notifications FROM authenticated;
GRANT UPDATE (read_at) ON notifications TO authenticated;

-- ============================================================================
-- 4. POLL VOTES
-- ============================================================================

-- Anonymous polls must not leak voter identity.
DROP POLICY IF EXISTS poll_votes_select_all ON poll_votes;
CREATE POLICY poll_votes_select_visible ON poll_votes
    FOR SELECT USING (
        user_id = current_user_id()
        OR poll_id IN (
            SELECT id FROM public.polls p
            WHERE p.message_id IN (
                SELECT id FROM public.messages WHERE public.is_chat_member(chat_id)
            )
            AND (
                NOT p.is_anonymous
                OR p.message_id IN (
                    SELECT id FROM public.messages WHERE sender_id = current_user_id()
                )
            )
        )
    );

-- Voting requires membership in the chat that contains the poll.
DROP POLICY IF EXISTS poll_votes_insert_own ON poll_votes;
CREATE POLICY poll_votes_insert_own ON poll_votes
    FOR INSERT WITH CHECK (
        user_id = current_user_id()
        AND poll_id IN (
            SELECT id FROM public.polls
            WHERE message_id IN (
                SELECT id FROM public.messages WHERE public.is_chat_member(chat_id)
            )
        )
    );

DROP POLICY IF EXISTS poll_votes_delete_own ON poll_votes;
CREATE POLICY poll_votes_delete_own ON poll_votes
    FOR DELETE USING (user_id = current_user_id());

-- Single-choice polls: one vote per user.
CREATE OR REPLACE FUNCTION public.validate_poll_vote()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_multiple BOOLEAN;
BEGIN
    SELECT allows_multiple INTO v_multiple
    FROM public.polls WHERE id = NEW.poll_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'poll not found';
    END IF;

    IF NOT v_multiple THEN
        IF EXISTS (
            SELECT 1 FROM public.poll_votes
            WHERE poll_id = NEW.poll_id
              AND user_id = NEW.user_id
              AND id <> NEW.id
        ) THEN
            RAISE EXCEPTION 'single-choice poll: a vote has already been cast';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trigger_validate_poll_vote ON poll_votes;
CREATE TRIGGER trigger_validate_poll_vote
    BEFORE INSERT OR UPDATE ON poll_votes
    FOR EACH ROW
    EXECUTE FUNCTION public.validate_poll_vote();

-- ============================================================================
-- 5. SAVED MESSAGES: MEDIA-ONLY SAVES
-- ============================================================================

ALTER TABLE saved_messages DROP CONSTRAINT IF EXISTS saved_messages_content_not_empty;
ALTER TABLE saved_messages ADD CONSTRAINT saved_messages_content_not_empty
    CHECK (content <> '' OR is_media_only = TRUE);

-- ============================================================================
-- 6. DRAFTS: CHAT MEMBERSHIP REQUIRED
-- ============================================================================

DROP POLICY IF EXISTS drafts_insert_own ON drafts;
CREATE POLICY drafts_insert_own ON drafts
    FOR INSERT WITH CHECK (
        user_id = current_user_id()
        AND public.is_chat_member(chat_id)
    );

DROP POLICY IF EXISTS drafts_update_own ON drafts;
CREATE POLICY drafts_update_own ON drafts
    FOR UPDATE USING (user_id = current_user_id())
    WITH CHECK (user_id = current_user_id());

-- ============================================================================
-- 7. STORY COUNT TRIGGERS -> SECURITY DEFINER (RLS would block invoker writes)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.increment_story_view_count()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    UPDATE public.stories SET view_count = view_count + 1 WHERE id = NEW.story_id;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.update_story_reaction_count()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE public.stories SET reaction_count = reaction_count + 1 WHERE id = NEW.story_id;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE public.stories SET reaction_count = GREATEST(0, reaction_count - 1) WHERE id = OLD.story_id;
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$;
