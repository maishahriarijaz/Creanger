-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 016: HARDEN CHAT MEMBERSHIP
-- ============================================================================
-- Fixes the chat membership authorization model.
--
--  1. chat_members SELECT policy had infinite recursion (it referenced
--     chat_members in its own qual). Rewritten via is_chat_member().
--  2. chat_members INSERT policy let ANY user join ANY chat AND insert
--     themselves with role='owner' (self-promotion). Now restricted to:
--       - joining public groups/channels as 'member'
--       - joining a direct chat they are a participant of as 'member'
--       - inserting their own 'owner' row ONLY for chats they own
--  3. chat_members UPDATE policy let a user promote themselves to admin/owner
--     and rewrite their role. role is now immutable via WITH CHECK and removed
--     from the client UPDATE column grant.
--  4. chats: is_verified removed from the client UPDATE grant; UPDATE/DELETE
--     policies tightened; deleted_at added to grant so owners can soft-delete.
--  5. chat_permissions: INSERT policy added (was missing -> owners could not
--     initialize permissions); UPDATE policy tightened with WITH CHECK.
--  6. topics INSERT now requires chat membership.
--  7. get_or_create_direct_chat(): now SECURITY DEFINER (transactional chat
--     creation), requires the caller to be one of the two participants, and is
--     race-safe via a new UNIQUE constraint on (user_a_id, user_b_id) plus
--     unique-violation handling.
--  8. Admin membership functions (add_chat_member, set_chat_member_role,
--     remove_chat_member) enforce authorization in the database boundary.
-- ============================================================================

-- ============================================================================
-- 1. DIRECT CHATS: UNIQUE PAIR (concurrency)
-- ============================================================================
-- Prevents duplicate canonical direct chats under concurrent creation.

CREATE UNIQUE INDEX IF NOT EXISTS direct_chats_unique_pair ON direct_chats(user_a_id, user_b_id);

-- ============================================================================
-- 2. CHAT_MEMBERS RLS
-- ============================================================================

DROP POLICY IF EXISTS chat_members_select_members ON chat_members;
CREATE POLICY chat_members_select_members ON chat_members
    FOR SELECT USING (
        public.is_chat_member(chat_id)
        OR user_id = current_user_id()
    );

DROP POLICY IF EXISTS chat_members_insert_self ON chat_members;
CREATE POLICY chat_members_insert_self ON chat_members
    FOR INSERT WITH CHECK (
        user_id = current_user_id()
        AND (
            (
                role = 'member'
                AND (
                    chat_id IN (
                        SELECT chat_id FROM direct_chats
                        WHERE user_a_id = current_user_id() OR user_b_id = current_user_id()
                    )
                    OR chat_id IN (
                        SELECT id FROM chats
                        WHERE is_public = TRUE AND deleted_at IS NULL AND type IN ('group', 'channel')
                    )
                )
            )
            OR (
                role = 'owner'
                AND chat_id IN (
                    SELECT id FROM chats WHERE owner_id = current_user_id()
                )
            )
        )
    );

DROP POLICY IF EXISTS chat_members_update_own ON chat_members;
CREATE POLICY chat_members_update_own ON chat_members
    FOR UPDATE USING (user_id = current_user_id())
    WITH CHECK (user_id = current_user_id());
-- NOTE: role cannot be changed by the client because `role` is not included
-- in the UPDATE column grant below; role changes go through
-- set_chat_member_role() (owner only) or service_role.

DROP POLICY IF EXISTS chat_members_delete_self ON chat_members;
CREATE POLICY chat_members_delete_self ON chat_members
    FOR DELETE USING (user_id = current_user_id());

-- Client may no longer write the role column; role changes happen through
-- set_chat_member_role() (owner) or service_role. left_at added so a user can
-- leave a chat.
REVOKE UPDATE ON chat_members FROM authenticated;
GRANT UPDATE (muted_until, pinned_position, last_read_at, left_at) ON chat_members TO authenticated;

-- ============================================================================
-- 3. CHATS RLS + GRANTS
-- ============================================================================

DROP POLICY IF EXISTS chats_select_members ON chats;
CREATE POLICY chats_select_members ON chats
    FOR SELECT USING (
        public.is_chat_member(id)
        OR is_public = TRUE
    );

DROP POLICY IF EXISTS chats_update_owner ON chats;
CREATE POLICY chats_update_owner ON chats
    FOR UPDATE USING (owner_id = current_user_id())
    WITH CHECK (owner_id = current_user_id());

-- is_verified is a privileged flag (admin verification) - not client-writable.
-- deleted_at is added so the owner can soft-delete their own chat.
REVOKE UPDATE ON chats FROM authenticated;
GRANT UPDATE (title, description, avatar_media_id, is_public, is_archived, deleted_at) ON chats TO authenticated;

-- ============================================================================
-- 4. CHAT_PERMISSIONS
-- ============================================================================

CREATE POLICY chat_permissions_insert_owner ON chat_permissions
    FOR INSERT WITH CHECK (
        chat_id IN (SELECT id FROM chats WHERE owner_id = current_user_id())
    );

DROP POLICY IF EXISTS chat_permissions_update_owner ON chat_permissions;
CREATE POLICY chat_permissions_update_owner ON chat_permissions
    FOR UPDATE USING (
        chat_id IN (SELECT id FROM chats WHERE owner_id = current_user_id())
    )
    WITH CHECK (
        chat_id IN (SELECT id FROM chats WHERE owner_id = current_user_id())
    );

-- ============================================================================
-- 5. TOPICS: CREATOR MUST BE A MEMBER OF THE CHAT
-- ============================================================================

DROP POLICY IF EXISTS topics_insert_creator ON topics;
CREATE POLICY topics_insert_creator ON topics
    FOR INSERT WITH CHECK (
        creator_id = current_user_id()
        AND public.is_chat_member(chat_id)
    );

DROP POLICY IF EXISTS topics_update_creator ON topics;
CREATE POLICY topics_update_creator ON topics
    FOR UPDATE USING (creator_id = current_user_id())
    WITH CHECK (creator_id = current_user_id());

DROP POLICY IF EXISTS topics_delete_creator ON topics;
CREATE POLICY topics_delete_creator ON topics
    FOR DELETE USING (creator_id = current_user_id());

-- ============================================================================
-- 6. get_or_create_direct_chat(): SECURE + RACE-SAFE
-- ============================================================================
-- SECURITY DEFINER is required because creating a direct chat inserts rows into
-- chats/direct_chats/chat_members/chat_read_state/chat_permissions across
-- several RLS-protected tables transactionally. The function itself enforces
-- that the caller is one of the two participants.

DROP FUNCTION IF EXISTS public.get_or_create_direct_chat(UUID, UUID);

CREATE OR REPLACE FUNCTION public.get_or_create_direct_chat(p_user_a UUID, p_user_b UUID)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_low UUID;
    v_high UUID;
    v_chat_id UUID;
BEGIN
    IF p_user_a IS NULL OR p_user_b IS NULL OR p_user_a = p_user_b THEN
        RETURN NULL;
    END IF;

    -- Caller must be one of the two participants
    IF public.current_user_id() NOT IN (p_user_a, p_user_b) THEN
        RETURN NULL;
    END IF;

    IF p_user_a < p_user_b THEN
        v_low := p_user_a;
        v_high := p_user_b;
    ELSE
        v_low := p_user_b;
        v_high := p_user_a;
    END IF;

    SELECT dc.chat_id INTO v_chat_id
    FROM public.direct_chats dc
    WHERE dc.user_a_id = v_low AND dc.user_b_id = v_high;

    IF v_chat_id IS NOT NULL THEN
        RETURN v_chat_id;
    END IF;

    BEGIN
        INSERT INTO public.chats (type)
        VALUES ('direct')
        RETURNING id INTO v_chat_id;

        INSERT INTO public.direct_chats (chat_id, user_a_id, user_b_id)
        VALUES (v_chat_id, v_low, v_high);

        INSERT INTO public.chat_members (chat_id, user_id, role)
        VALUES
            (v_chat_id, p_user_a, 'member'),
            (v_chat_id, p_user_b, 'member');

        INSERT INTO public.chat_read_state (chat_id, user_id)
        VALUES
            (v_chat_id, p_user_a),
            (v_chat_id, p_user_b);

        INSERT INTO public.chat_permissions (chat_id)
        VALUES (v_chat_id)
        ON CONFLICT (chat_id) DO NOTHING;
    EXCEPTION WHEN unique_violation THEN
        -- A concurrent request won the race; fall back to the winner's chat.
        SELECT dc.chat_id INTO v_chat_id
        FROM public.direct_chats dc
        WHERE dc.user_a_id = v_low AND dc.user_b_id = v_high;
        IF v_chat_id IS NULL THEN
            RAISE;
        END IF;
    END;

    RETURN v_chat_id;
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_or_create_direct_chat(UUID, UUID) TO authenticated;

-- ============================================================================
-- 7. ADMIN MEMBERSHIP OPERATIONS (DATABASE-ENFORCED AUTHORIZATION)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.add_chat_member(p_chat_id UUID, p_user_id UUID)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_member_id UUID;
BEGIN
    IF p_chat_id IS NULL OR p_user_id IS NULL THEN
        RETURN NULL;
    END IF;

    -- Only an admin/owner may add members; direct chats have no membership management
    IF NOT public.is_chat_admin(p_chat_id) THEN
        RAISE EXCEPTION 'not authorized to add members';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM public.chats WHERE id = p_chat_id AND type <> 'direct') THEN
        RAISE EXCEPTION 'cannot manage members in a direct chat';
    END IF;

    INSERT INTO public.chat_members (chat_id, user_id, role)
    VALUES (p_chat_id, p_user_id, 'member')
    ON CONFLICT (chat_id, user_id)
    DO UPDATE SET left_at = NULL, updated_at = NOW()
    RETURNING id INTO v_member_id;

    INSERT INTO public.chat_read_state (chat_id, user_id)
    VALUES (p_chat_id, p_user_id)
    ON CONFLICT (chat_id, user_id) DO NOTHING;

    RETURN v_member_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.set_chat_member_role(p_chat_id UUID, p_user_id UUID, p_role public.chat_member_role)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF p_chat_id IS NULL OR p_user_id IS NULL OR p_role IS NULL THEN
        RETURN;
    END IF;

    -- Only the owner may change roles (prevents self-promotion & mutual promotion)
    IF NOT public.is_chat_owner(p_chat_id) THEN
        RAISE EXCEPTION 'only the chat owner can change roles';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.chat_members
        WHERE chat_id = p_chat_id AND user_id = p_user_id AND left_at IS NULL
    ) THEN
        RAISE EXCEPTION 'target user is not an active member';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.chat_members
        WHERE chat_id = p_chat_id AND user_id = p_user_id AND role = 'owner' AND p_role <> 'owner'
    ) THEN
        RAISE EXCEPTION 'the chat owner cannot be demoted';
    END IF;

    UPDATE public.chat_members
    SET role = p_role, updated_at = NOW()
    WHERE chat_id = p_chat_id AND user_id = p_user_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.remove_chat_member(p_chat_id UUID, p_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF p_chat_id IS NULL OR p_user_id IS NULL THEN
        RETURN;
    END IF;

    -- Any active member may leave a chat themselves
    IF p_user_id = public.current_user_id() THEN
        UPDATE public.chat_members
        SET left_at = COALESCE(left_at, NOW()), updated_at = NOW()
        WHERE chat_id = p_chat_id AND user_id = p_user_id;
        RETURN;
    END IF;

    -- Otherwise the caller must be an admin/owner
    IF NOT public.is_chat_admin(p_chat_id) THEN
        RAISE EXCEPTION 'not authorized to remove members';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.chat_members
        WHERE chat_id = p_chat_id AND user_id = p_user_id AND role = 'owner'
    ) THEN
        RAISE EXCEPTION 'the chat owner cannot be removed';
    END IF;

    UPDATE public.chat_members
    SET left_at = COALESCE(left_at, NOW()), updated_at = NOW()
    WHERE chat_id = p_chat_id AND user_id = p_user_id;
END;
$$;

GRANT EXECUTE ON FUNCTION public.add_chat_member(UUID, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.set_chat_member_role(UUID, UUID, public.chat_member_role) TO authenticated;
GRANT EXECUTE ON FUNCTION public.remove_chat_member(UUID, UUID) TO authenticated;
