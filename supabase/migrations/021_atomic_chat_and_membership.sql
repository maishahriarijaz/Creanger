-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 021: ATOMIC CHAT CREATION +
-- MEMBERSHIP LIFECYCLE
-- ============================================================================
--
-- Mandatory Fix 1 (atomic group chat creation) and Mandatory Fix 2
-- (removed-member self-rejoin) and Mandatory Fix 6 (direct chat integrity).
--
--  1. create_group_chat(): SECURITY DEFINER RPC that atomically creates the
--     chat, the owner membership, permissions and read-state in ONE function
--     call. A chat can no longer be left "orphaned" (chat row without an owner
--     membership row). Owner identity is always current_user_id() - the caller
--     can never impersonate another owner.
--  2. Client direct INSERT into `chats` is revoked. Group/channel creation has
--     a single atomic path (create_group_chat); service_role keeps full access.
--     chat_members_insert_self no longer allows self-inserting an 'owner' row
--     (that role branch only existed to patch the old 2-step client flow).
--  3. leave_chat(): SECURITY DEFINER RPC for voluntary leave. `left_at` is
--     REMOVED from the client UPDATE grant, so a removed user can no longer
--     flip left_at = NULL to self-rejoin. Re-admission is a privileged action
--     (add_chat_member(), owner/service only).
--  4. direct_chats: client INSERT revoked; canonical creation is exclusively
--     get_or_create_direct_chat(). Removes the arbitrary participant-injection
--     path (direct INSERT required only that the caller be one participant,
--     bypassing the full chat/member/read-state bootstrap).
-- ============================================================================

-- ============================================================================
-- 1. create_group_chat(): ATOMIC CHAT + OWNER MEMBERSHIP
-- ============================================================================
-- Leasts-privilege: SECURITY DEFINER is required to write chats/chat_members/
-- chat_permissions/chat_read_state in one transaction. The function performs
-- its OWN authorization (caller must be the authenticated user) and validates
-- every input. It does NOT accept an owner parameter.

DROP FUNCTION IF EXISTS public.create_group_chat(TEXT, BOOLEAN, public.chat_type, TEXT);

CREATE OR REPLACE FUNCTION public.create_group_chat(
    p_title TEXT,
    p_is_public BOOLEAN DEFAULT FALSE,
    p_type public.chat_type DEFAULT 'group',
    p_description TEXT DEFAULT NULL
)
RETURNS TABLE (
    id UUID,
    type public.chat_type,
    title TEXT,
    description TEXT,
    owner_id UUID,
    is_public BOOLEAN,
    created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_owner_id UUID := public.current_user_id();
    v_chat_id UUID;
BEGIN
    -- Caller must be authenticated (owner identity is never caller-supplied).
    IF v_owner_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;

    -- Only groups and channels can be created by clients; direct chats are
    -- created exclusively by get_or_create_direct_chat().
    IF p_type IS NULL OR p_type NOT IN ('group', 'channel') THEN
        RAISE EXCEPTION 'invalid chat type; only group/channel can be created here';
    END IF;

    IF p_title IS NULL OR btrim(p_title) = '' THEN
        RAISE EXCEPTION 'title is required';
    END IF;

    IF p_type = 'channel' AND p_is_public IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'channels must be public';
    END IF;

    -- Atomic: chat + owner membership + permissions + read state.
    -- NOTE: the RETURNING column must be qualified - the function returns
    -- TABLE(id,...) which creates an implicit PL/pgSQL variable named `id`,
    -- making an unqualified `RETURNING id` ambiguous.
    INSERT INTO public.chats (type, title, description, owner_id, is_public)
    VALUES (p_type, p_title, p_description, v_owner_id, COALESCE(p_is_public, FALSE))
    RETURNING public.chats.id INTO v_chat_id;

    INSERT INTO public.chat_members (chat_id, user_id, role)
    VALUES (v_chat_id, v_owner_id, 'owner');

    INSERT INTO public.chat_permissions (chat_id)
    VALUES (v_chat_id);

    INSERT INTO public.chat_read_state (chat_id, user_id)
    VALUES (v_chat_id, v_owner_id);

    RETURN QUERY
    SELECT c.id, c.type, c.title, c.description, c.owner_id, c.is_public, c.created_at
    FROM public.chats c
    WHERE c.id = v_chat_id;
END;
$$;

REVOKE ALL ON FUNCTION public.create_group_chat(TEXT, BOOLEAN, public.chat_type, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_group_chat(TEXT, BOOLEAN, public.chat_type, TEXT) TO authenticated;

-- ============================================================================
-- 2. CLIENT CANNOT DIRECTLY INSERT chats
-- ============================================================================
-- Group/channel creation now flows exclusively through create_group_chat().
-- Service role (server-side provisioning) keeps full access.

DROP POLICY IF EXISTS chats_insert_owner ON chats;
REVOKE INSERT ON chats FROM authenticated;

-- The 'owner' self-insert branch in chat_members_insert_self existed to patch
-- the old two-step client flow; with chats INSERT revoked it is dead code and
-- is removed so a malformed owner membership can never be inserted by a client.
DROP POLICY IF EXISTS chat_members_insert_self ON chat_members;
CREATE POLICY chat_members_insert_self ON chat_members
    FOR INSERT WITH CHECK (
        user_id = current_user_id()
        AND role = 'member'
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
    );

-- ============================================================================
-- 3. MEMBERSHIP LIFECYCLE: VOLUNTARY LEAVE WITHOUT SELF-REJOIN
-- ============================================================================

DROP FUNCTION IF EXISTS public.leave_chat(UUID);

CREATE OR REPLACE FUNCTION public.leave_chat(p_chat_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_role public.chat_member_role;
    v_owner_count BIGINT;
    v_active_count BIGINT;
BEGIN
    IF v_user_id IS NULL OR p_chat_id IS NULL THEN
        RETURN FALSE;
    END IF;

    SELECT role INTO v_role
    FROM public.chat_members
    WHERE chat_id = p_chat_id AND user_id = v_user_id AND left_at IS NULL;

    IF NOT FOUND THEN
        RETURN FALSE; -- not an active member; nothing to leave
    END IF;

    -- The chat owner cannot silently abandon a chat that would be left with no
    -- active owner. Ownership transfer is a service-side concern.
    IF v_role = 'owner' THEN
        SELECT COUNT(*) INTO v_owner_count
        FROM public.chat_members
        WHERE chat_id = p_chat_id AND role = 'owner' AND left_at IS NULL;

        SELECT COUNT(*) INTO v_active_count
        FROM public.chat_members
        WHERE chat_id = p_chat_id AND left_at IS NULL;

        IF v_owner_count <= 1 THEN
            IF NOT EXISTS (SELECT 1 FROM public.chats WHERE id = p_chat_id AND type = 'channel') THEN
                RAISE EXCEPTION 'the last owner cannot leave; transfer ownership first';
            END IF;
        END IF;
        IF v_active_count <= 1 THEN
            RAISE EXCEPTION 'cannot leave: no other active members remain';
        END IF;
    END IF;

    UPDATE public.chat_members
    SET left_at = NOW(), updated_at = NOW()
    WHERE chat_id = p_chat_id AND user_id = v_user_id AND left_at IS NULL;

    RETURN FOUND;
END;
$$;

REVOKE ALL ON FUNCTION public.leave_chat(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.leave_chat(UUID) TO authenticated;

-- left_at is a lifecycle column: clients may no longer write it. Re-admission
-- happens exclusively through add_chat_member() (owner/service). A removed user
-- can no longer flip left_at = NULL on their own row.
REVOKE UPDATE ON chat_members FROM authenticated;
GRANT UPDATE (muted_until, pinned_position, last_read_at) ON chat_members TO authenticated;

-- ============================================================================
-- 4. DIRECT CHAT INTEGRITY: CREATION ONLY VIA get_or_create_direct_chat()
-- ============================================================================

DROP POLICY IF EXISTS direct_chats_insert_participant ON direct_chats;
REVOKE INSERT, UPDATE, DELETE ON direct_chats FROM authenticated;