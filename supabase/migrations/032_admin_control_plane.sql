-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 032: ADMIN CONTROL PLANE
-- ============================================================================
-- The Admin Panel is a secure control plane over the EXISTING Creanger
-- schema. This migration adds the ONLY server-side boundary the admin UI is
-- allowed to call. It does NOT invent tables or duplicate concepts that
-- already exist:
--
--   - admin identity  -> existing admin_users + is_active_admin()
--   - permissions     -> existing admin_permissions (seeded per role)
--   - audit trail     -> existing admin_actions + audit_logs (append-only)
--   - moderation      -> existing moderation_actions + user_restrictions
--
-- SECURITY MODEL (mirrors edit_message / delete_message / send_media_message):
--   Admin UI
--     -> authenticated admin JWT (sub = users.id, role = authenticated)
--     -> SECURITY DEFINER RPC (this migration)
--        -> admin_require() resolves the caller from the JWT via
--           current_user_id(), never from caller-supplied arguments
--        -> checks is_active_admin() + admin_permissions for the operation
--     -> minimal privileged DB operation
--
-- NO existing RLS policy is disabled or relaxed. No USING(true) policy is
-- added to any user-facing table. The SECURITY DEFINER functions perform their
-- OWN explicit authorization and then do the smallest possible privileged
-- write. service_role is never exposed to the browser; the admin UI only ever
-- sends the admin's own user JWT.
--
-- CONVENTIONS FOLLOWED:
--   - SECURITY DEFINER + SET search_path = public (prevents object hijacking)
--   - REVOKE ALL ... FROM PUBLIC; GRANT EXECUTE ... TO authenticated,
--     service_role (mandatory fix 3 / migration 024 pattern)
--   - Every admin action writes an admin_actions row (previous/new state,
--     admin_user_id from the caller) - existing append-only audit
--   - Security events are additionally mirrored into audit_logs where the
--     existing audit_action enum has a matching value
--   - NEVER selects: password_credentials, refresh_tokens.token_hash,
--     email_verifications.code_hash, sessions.ip_hash, push_tokens.token,
--     OTP codes, JWT/signing secrets
-- ============================================================================

-- ============================================================================
-- 1. ADMIN AUTHORIZATION HELPER
-- ============================================================================
-- Returns the admin_users.id of the current caller (resolved from the JWT) and
-- enforces (a) an active, non-expired admin account and (b) the required
-- permission for the caller's role. super_admin is implicit-all; every other
-- role must own the permission row in admin_permissions.

CREATE OR REPLACE FUNCTION public.admin_require(p_permission TEXT DEFAULT NULL)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_admin_id UUID;
    v_role public.admin_role;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;

    SELECT a.id, a.role INTO v_admin_id, v_role
    FROM public.admin_users a
    WHERE a.user_id = v_user_id
      AND a.is_active = TRUE
      AND (a.expires_at IS NULL OR a.expires_at > NOW());

    IF v_admin_id IS NULL THEN
        RAISE EXCEPTION 'admin access required';
    END IF;

    IF p_permission IS NOT NULL
       AND v_role <> 'super_admin'
       AND NOT EXISTS (
           SELECT 1 FROM public.admin_permissions ap
           WHERE ap.role = v_role AND ap.permission = p_permission
       ) THEN
        RAISE EXCEPTION 'permission denied: %', p_permission;
    END IF;

    RETURN v_admin_id;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_require(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_require(TEXT) TO authenticated, service_role;

-- ============================================================================
-- 1b. WHO AM I (session bootstrap)
-- ============================================================================
-- Returns the caller's admin identity + effective permission set so the admin
-- UI can render the correct sections. Raises for non-admins (used as the
-- login/boundary gate).

CREATE OR REPLACE FUNCTION public.admin_whoami()
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_admin_id UUID;
    v_role public.admin_role;
    v_expires_at TIMESTAMPTZ;
    v_permissions TEXT[];
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;

    SELECT a.id, a.role, a.expires_at INTO v_admin_id, v_role, v_expires_at
    FROM public.admin_users a
    WHERE a.user_id = v_user_id
      AND a.is_active = TRUE
      AND (a.expires_at IS NULL OR a.expires_at > NOW());

    IF v_admin_id IS NULL THEN
        RAISE EXCEPTION 'admin access required';
    END IF;

    SELECT ARRAY(
        SELECT DISTINCT ap.permission
        FROM public.admin_permissions ap
        WHERE ap.role = v_role OR v_role = 'super_admin'
        ORDER BY 1
    ) INTO v_permissions;

    RETURN jsonb_build_object(
        'user_id', v_user_id,
        'admin_user_id', v_admin_id,
        'role', v_role,
        'expires_at', v_expires_at,
        'permissions', COALESCE(v_permissions, '{}'::TEXT[])
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_whoami() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_whoami() TO authenticated, service_role;

-- ============================================================================
-- 2. DASHBOARD METRICS
-- ============================================================================
-- Only metrics derivable from the actual schema. There is no media "state"
-- column, so failed/pending media is intentionally absent (nothing to derive
-- it from). "online now" = user_presence.status='online' maintained by clients;
-- it is NOT inferred from stale timestamps.

CREATE OR REPLACE FUNCTION public.admin_dashboard_stats()
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('users.read');
    v_stats JSONB;
BEGIN
    SELECT jsonb_build_object(
        'total_users',        (SELECT COUNT(*) FROM public.users WHERE deleted_at IS NULL),
        'active_users',       (SELECT COUNT(*) FROM public.users WHERE status = 'active' AND deleted_at IS NULL),
        'suspended_users',    (SELECT COUNT(*) FROM public.users WHERE status = 'suspended' AND deleted_at IS NULL),
        'new_users_24h',      (SELECT COUNT(*) FROM public.users WHERE deleted_at IS NULL AND created_at >= NOW() - INTERVAL '24 hours'),
        'new_users_7d',       (SELECT COUNT(*) FROM public.users WHERE deleted_at IS NULL AND created_at >= NOW() - INTERVAL '7 days'),
        'total_chats',        (SELECT COUNT(*) FROM public.chats WHERE deleted_at IS NULL),
        'total_messages',     (SELECT COUNT(*) FROM public.messages WHERE deleted_at IS NULL),
        'messages_today',     (SELECT COUNT(*) FROM public.messages WHERE deleted_at IS NULL AND created_at >= NOW() - INTERVAL '24 hours'),
        'media_messages',     (SELECT COUNT(*) FROM public.messages WHERE deleted_at IS NULL AND message_type IN ('image','video','document','audio','voice')),
        'total_media',        (SELECT COUNT(*) FROM public.media WHERE deleted_at IS NULL),
        'open_user_reports',  (SELECT COUNT(*) FROM public.user_reports WHERE status IN ('pending','under_review')),
        'open_message_reports', (SELECT COUNT(*) FROM public.message_reports WHERE status IN ('pending','under_review')),
        'active_sessions',    (SELECT COUNT(*) FROM public.sessions WHERE revoked_at IS NULL AND expires_at > NOW()),
        'online_now',         (SELECT COUNT(*) FROM public.user_presence WHERE status = 'online')
    ) INTO v_stats;

    RETURN v_stats;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_dashboard_stats() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_dashboard_stats() TO authenticated, service_role;

-- ============================================================================
-- 3. USERS
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_list_users(
    p_search TEXT DEFAULT NULL,
    p_status public.user_status DEFAULT NULL,
    p_sort TEXT DEFAULT 'created_at',
    p_direction TEXT DEFAULT 'desc',
    p_page INTEGER DEFAULT 1,
    p_page_size INTEGER DEFAULT 25
)
RETURNS TABLE (
    id UUID,
    username CITEXT,
    first_name TEXT,
    last_name TEXT,
    email CITEXT,
    email_verified BOOLEAN,
    status public.user_status,
    created_at TIMESTAMPTZ,
    presence_status public.presence_status,
    last_seen_at TIMESTAMPTZ,
    admin_role public.admin_role,
    total BIGINT
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('users.read');
    v_page INTEGER := GREATEST(COALESCE(p_page, 1), 1);
    v_page_size INTEGER := LEAST(GREATEST(COALESCE(p_page_size, 25), 1), 100);
    v_offset INTEGER := (v_page - 1) * v_page_size;
    v_direction TEXT := CASE WHEN lower(COALESCE(p_direction, 'desc')) = 'asc' THEN 'asc' ELSE 'desc' END;
    v_sort TEXT := lower(COALESCE(p_sort, 'created_at'));
BEGIN
    IF v_sort NOT IN ('created_at', 'username', 'email', 'last_seen_at', 'status') THEN
        v_sort := 'created_at';
    END IF;

    RETURN QUERY EXECUTE format(
        'SELECT u.id, pr.username, pr.first_name, pr.last_name,
                ui.email, ui.email_verified,
                u.status, u.created_at,
                pres.status AS presence_status, pres.last_seen_at,
                au.role AS admin_role,
                COUNT(*) OVER()::BIGINT AS total
         FROM public.users u
         LEFT JOIN public.profiles pr ON pr.user_id = u.id
         LEFT JOIN LATERAL (
             SELECT id.email, id.email_verified
             FROM public.user_identities id
             WHERE id.user_id = u.id AND id.email IS NOT NULL
             ORDER BY id.created_at LIMIT 1
         ) ui ON TRUE
         LEFT JOIN public.user_presence pres ON pres.user_id = u.id
         LEFT JOIN public.admin_users au ON au.user_id = u.id AND au.is_active = TRUE
         WHERE u.deleted_at IS NULL
           AND ($1::text IS NULL OR pr.username ILIKE ''%%'' || $1 || ''%%''
               OR pr.first_name ILIKE ''%%'' || $1 || ''%%''
               OR pr.last_name ILIKE ''%%'' || $1 || ''%%''
               OR ui.email ILIKE ''%%'' || $1 || ''%%'')
           AND ($2::public.user_status IS NULL OR u.status = $2)
         ORDER BY %I %s
         LIMIT $3 OFFSET $4',
        v_sort, v_direction
    ) USING p_search, p_status, v_page_size, v_offset;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_list_users(TEXT, public.user_status, TEXT, TEXT, INTEGER, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_list_users(TEXT, public.user_status, TEXT, TEXT, INTEGER, INTEGER) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_get_user(p_user_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('users.read');
    v_result JSONB;
BEGIN
    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'user_id is required';
    END IF;

    SELECT jsonb_build_object(
        'user', jsonb_build_object(
            'id', u.id, 'status', u.status, 'created_at', u.created_at,
            'updated_at', u.updated_at, 'deleted_at', u.deleted_at
        ),
        'profile', jsonb_build_object(
            'username', pr.username, 'first_name', pr.first_name, 'last_name', pr.last_name,
            'bio', pr.bio,
            'avatar_url', (SELECT m.public_url FROM public.media m WHERE m.id = pr.avatar_media_id)
        ),
        'identities', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'provider', id.provider, 'email', id.email,
                'email_verified', id.email_verified, 'created_at', id.created_at
            ) ORDER BY id.created_at)
            FROM public.user_identities id WHERE id.user_id = u.id
        ), '[]'::jsonb),
        'presence', jsonb_build_object(
            'status', COALESCE(pres.status, 'offline'::public.presence_status),
            'last_seen_at', pres.last_seen_at
        ),
        'admin', (SELECT jsonb_build_object(
            'role', au.role, 'is_active', au.is_active, 'assigned_at', au.assigned_at,
            'expires_at', au.expires_at
        ) FROM public.admin_users au WHERE au.user_id = u.id),
        'restrictions', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'id', ur.id, 'restriction_type', ur.restriction_type, 'reason', ur.reason,
                'issued_at', ur.issued_at, 'expires_at', ur.expires_at,
                'is_active', ur.is_active, 'revoked_at', ur.revoked_at
            ) ORDER BY ur.issued_at DESC)
            FROM public.user_restrictions ur WHERE ur.user_id = u.id
        ), '[]'::jsonb),
        'moderation_actions', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'id', ma.id, 'action_type', ma.action_type, 'reason', ma.reason,
                'created_at', ma.created_at, 'is_active', ma.is_active
            ) ORDER BY ma.created_at DESC)
            FROM public.moderation_actions ma WHERE ma.target_user_id = u.id
        ), '[]'::jsonb),
        'premium_entitlements', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'feature', pe.feature, 'is_active', pe.is_active,
                'expires_at', pe.expires_at, 'created_at', pe.created_at
            ) ORDER BY pe.created_at DESC)
            FROM public.premium_entitlements pe WHERE pe.user_id = u.id
        ), '[]'::jsonb),
        'counts', jsonb_build_object(
            'active_sessions', (SELECT COUNT(*) FROM public.sessions s WHERE s.user_id = u.id AND s.revoked_at IS NULL AND s.expires_at > NOW()),
            'total_sessions', (SELECT COUNT(*) FROM public.sessions s WHERE s.user_id = u.id),
            'devices', (SELECT COUNT(*) FROM public.devices d WHERE d.user_id = u.id AND d.revoked_at IS NULL),
            'chats', (SELECT COUNT(*) FROM public.chat_members cm WHERE cm.user_id = u.id AND cm.left_at IS NULL),
            'messages_sent', (SELECT COUNT(*) FROM public.messages m WHERE m.sender_id = u.id AND m.deleted_at IS NULL),
            'media_owned', (SELECT COUNT(*) FROM public.media me WHERE me.owner_id = u.id AND me.deleted_at IS NULL),
            'reports_filed', (SELECT COUNT(*) FROM public.user_reports ur WHERE ur.reporter_id = u.id) +
                             (SELECT COUNT(*) FROM public.message_reports mr WHERE mr.reporter_id = u.id),
            'reports_received', (SELECT COUNT(*) FROM public.user_reports ur WHERE ur.reported_user_id = u.id) +
                                (SELECT COUNT(*) FROM public.message_reports mr WHERE mr.message_id IN (
                                    SELECT m.id FROM public.messages m WHERE m.sender_id = u.id
                                ))
        )
    ) INTO v_result
    FROM public.users u
    LEFT JOIN public.profiles pr ON pr.user_id = u.id
    LEFT JOIN public.user_presence pres ON pres.user_id = u.id
    WHERE u.id = p_user_id;

    IF v_result IS NULL THEN
        RAISE EXCEPTION 'user not found';
    END IF;

    RETURN v_result;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_get_user(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_get_user(UUID) TO authenticated, service_role;

-- ============================================================================
-- 4. CHATS
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_list_chats(
    p_search TEXT DEFAULT NULL,
    p_type public.chat_type DEFAULT NULL,
    p_page INTEGER DEFAULT 1,
    p_page_size INTEGER DEFAULT 25
)
RETURNS TABLE (
    id UUID,
    type public.chat_type,
    title TEXT,
    username CITEXT,
    description TEXT,
    owner_id UUID,
    owner_name TEXT,
    is_public BOOLEAN,
    is_verified BOOLEAN,
    is_archived BOOLEAN,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    member_count BIGINT,
    last_message_at TIMESTAMPTZ,
    total BIGINT
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('moderation.read');
    v_page INTEGER := GREATEST(COALESCE(p_page, 1), 1);
    v_page_size INTEGER := LEAST(GREATEST(COALESCE(p_page_size, 25), 1), 100);
    v_offset INTEGER := (v_page - 1) * v_page_size;
BEGIN
    RETURN QUERY
    SELECT c.id, c.type, c.title, c.username, c.description,
           c.owner_id, op.first_name AS owner_name,
           c.is_public, c.is_verified, c.is_archived,
           c.created_at, c.updated_at, c.deleted_at,
           (SELECT COUNT(*) FROM public.chat_members cm WHERE cm.chat_id = c.id AND cm.left_at IS NULL) AS member_count,
           (SELECT MAX(m.created_at) FROM public.messages m WHERE m.chat_id = c.id AND m.deleted_at IS NULL) AS last_message_at,
           COUNT(*) OVER()::BIGINT AS total
    FROM public.chats c
    LEFT JOIN public.profiles op ON op.user_id = c.owner_id
    WHERE c.deleted_at IS NULL
      AND (p_search::text IS NULL
           OR c.title ILIKE '%' || p_search || '%'
           OR c.username::text ILIKE '%' || p_search || '%')
      AND (p_type::public.chat_type IS NULL OR c.type = p_type)
    ORDER BY c.updated_at DESC
    LIMIT p_page_size OFFSET v_offset;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_list_chats(TEXT, public.chat_type, INTEGER, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_list_chats(TEXT, public.chat_type, INTEGER, INTEGER) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_get_chat(p_chat_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('moderation.read');
    v_result JSONB;
BEGIN
    IF p_chat_id IS NULL THEN
        RAISE EXCEPTION 'chat_id is required';
    END IF;

    SELECT jsonb_build_object(
        'chat', jsonb_build_object(
            'id', c.id, 'type', c.type, 'title', c.title, 'username', c.username,
            'description', c.description, 'owner_id', c.owner_id,
            'is_verified', c.is_verified, 'is_public', c.is_public,
            'is_archived', c.is_archived, 'created_at', c.created_at,
            'updated_at', c.updated_at, 'deleted_at', c.deleted_at
        ),
        'direct', (SELECT jsonb_build_object(
            'user_a_id', dc.user_a_id, 'user_b_id', dc.user_b_id
        ) FROM public.direct_chats dc WHERE dc.chat_id = c.id),
        'members', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'user_id', cm.user_id,
                'name', COALESCE(pr.first_name, ''),
                'username', pr.username,
                'role', cm.role, 'joined_at', cm.joined_at, 'left_at', cm.left_at,
                'muted_until', cm.muted_until, 'last_read_at', cm.last_read_at
            ) ORDER BY cm.role, cm.joined_at)
            FROM public.chat_members cm
            LEFT JOIN public.profiles pr ON pr.user_id = cm.user_id
            WHERE cm.chat_id = c.id
        ), '[]'::jsonb),
        'permissions', (SELECT jsonb_build_object(
            'can_send_messages', cp.can_send_messages, 'can_send_media', cp.can_send_media,
            'can_add_members', cp.can_add_members, 'can_pin_messages', cp.can_pin_messages,
            'can_delete_messages', cp.can_delete_messages, 'can_invite_users_by_link', cp.can_invite_users_by_link
        ) FROM public.chat_permissions cp WHERE cp.chat_id = c.id),
        'counts', jsonb_build_object(
            'message_count', (SELECT COUNT(*) FROM public.messages m WHERE m.chat_id = c.id AND m.deleted_at IS NULL),
            'media_count', (SELECT COUNT(*) FROM public.message_attachments ma WHERE ma.message_id IN (
                SELECT m.id FROM public.messages m WHERE m.chat_id = c.id AND m.deleted_at IS NULL
            ))
        ),
        'reports', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'id', r.id, 'reporter_id', r.reporter_id, 'reason', r.reason,
                'status', r.status, 'created_at', r.created_at
            ))
            FROM (
                SELECT mr.id, mr.reporter_id, mr.reason, mr.status, mr.created_at
                FROM public.message_reports mr WHERE mr.chat_id = c.id
                ORDER BY mr.created_at DESC
                LIMIT 50
            ) r
        ), '[]'::jsonb)
    ) INTO v_result
    FROM public.chats c
    WHERE c.id = p_chat_id;

    IF v_result IS NULL THEN
        RAISE EXCEPTION 'chat not found';
    END IF;

    RETURN v_result;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_get_chat(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_get_chat(UUID) TO authenticated, service_role;

-- ============================================================================
-- 5. MESSAGES
-- ============================================================================
-- Paginated message inspector. Content search is restricted to a chat context
-- (bounded by the chat's own pagination) - a global ILIKE over a huge message
-- table is intentionally not offered.

CREATE OR REPLACE FUNCTION public.admin_list_messages(
    p_chat_id UUID DEFAULT NULL,
    p_search TEXT DEFAULT NULL,
    p_page INTEGER DEFAULT 1,
    p_page_size INTEGER DEFAULT 25
)
RETURNS TABLE (
    id UUID,
    chat_id UUID,
    chat_seq BIGINT,
    sender_id UUID,
    sender_name TEXT,
    message_type public.message_type,
    content TEXT,
    status public.message_status,
    scheduled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ,
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    reply_to_message_id UUID,
    client_message_id TEXT,
    media_count BIGINT,
    total BIGINT
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('moderation.read');
    v_page INTEGER := GREATEST(COALESCE(p_page, 1), 1);
    v_page_size INTEGER := LEAST(GREATEST(COALESCE(p_page_size, 25), 1), 100);
    v_offset INTEGER := (v_page - 1) * v_page_size;
BEGIN
    RETURN QUERY
    SELECT m.id, m.chat_id, m.chat_seq, m.sender_id,
           pr.first_name AS sender_name,
           m.message_type, m.content, m.status, m.scheduled_at,
           m.created_at, m.edited_at, m.deleted_at,
           m.reply_to_message_id, m.client_message_id,
           (SELECT COUNT(*) FROM public.message_attachments ma WHERE ma.message_id = m.id) AS media_count,
           COUNT(*) OVER()::BIGINT AS total
    FROM public.messages m
    LEFT JOIN public.profiles pr ON pr.user_id = m.sender_id
    WHERE (p_chat_id::uuid IS NULL OR m.chat_id = p_chat_id)
      AND (p_search::text IS NULL
           OR (m.content IS NOT NULL AND m.content ILIKE '%' || p_search || '%'))
    ORDER BY m.created_at DESC
    LIMIT p_page_size OFFSET v_offset;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_list_messages(UUID, TEXT, INTEGER, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_list_messages(UUID, TEXT, INTEGER, INTEGER) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_get_message(p_message_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('moderation.read');
    v_result JSONB;
BEGIN
    IF p_message_id IS NULL THEN
        RAISE EXCEPTION 'message_id is required';
    END IF;

    SELECT jsonb_build_object(
        'message', jsonb_build_object(
            'id', m.id, 'chat_id', m.chat_id, 'chat_seq', m.chat_seq,
            'sender_id', m.sender_id, 'topic_id', m.topic_id,
            'message_type', m.message_type, 'content', m.content,
            'status', m.status, 'scheduled_at', m.scheduled_at,
            'created_at', m.created_at, 'edited_at', m.edited_at,
            'deleted_at', m.deleted_at, 'is_system_message', m.is_system_message,
            'reply_to_message_id', m.reply_to_message_id,
            'client_message_id', m.client_message_id,
            'metadata', m.metadata
        ),
        'sender', jsonb_build_object(
            'id', m.sender_id,
            'name', COALESCE(pr.first_name, ''),
            'username', pr.username,
            'avatar_url', (SELECT me.public_url FROM public.media me WHERE me.id = pr.avatar_media_id)
        ),
        'chat', jsonb_build_object(
            'id', c.id, 'type', c.type, 'title', c.title
        ),
        'reply_to', (SELECT jsonb_build_object(
            'id', r.id, 'content', r.content, 'sender_id', r.sender_id,
            'message_type', r.message_type, 'created_at', r.created_at
        ) FROM public.messages r WHERE r.id = m.reply_to_message_id),
        'media', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'media_id', med.id, 'storage_provider', med.storage_provider,
                'storage_key', med.storage_key, 'public_url', med.public_url,
                'delivery_url', med.delivery_url, 'thumbnail_url', med.thumbnail_url,
                'mime_type', med.mime_type, 'size_bytes', med.size_bytes,
                'checksum', med.checksum, 'width', med.width, 'height', med.height,
                'duration', med.duration, 'position', ma.position, 'caption', ma.caption
            ) ORDER BY ma.position)
            FROM public.message_attachments ma
            JOIN public.media med ON med.id = ma.media_id
            WHERE ma.message_id = m.id
        ), '[]'::jsonb),
        'edits', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'edit_sequence', me.edit_sequence, 'previous_content', me.previous_content,
                'created_at', me.created_at
            ) ORDER BY me.edit_sequence)
            FROM public.message_edits me WHERE me.message_id = m.id
        ), '[]'::jsonb),
        'reactions', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'user_id', mr.user_id, 'reaction', mr.reaction,
                'is_custom_emoji', mr.is_custom_emoji, 'created_at', mr.created_at
            ) ORDER BY mr.created_at)
            FROM public.message_reactions mr WHERE mr.message_id = m.id
        ), '[]'::jsonb),
        'deletions', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'user_id', md.user_id, 'is_for_everyone', md.is_for_everyone,
                'deleted_at', md.deleted_at
            ) ORDER BY md.deleted_at)
            FROM public.message_deletions md WHERE md.message_id = m.id
        ), '[]'::jsonb),
        'reports', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'id', mr.id, 'reporter_id', mr.reporter_id, 'reason', mr.reason,
                'status', mr.status, 'created_at', mr.created_at
            ) ORDER BY mr.created_at DESC)
            FROM public.message_reports mr WHERE mr.message_id = m.id
        ), '[]'::jsonb)
    ) INTO v_result
    FROM public.messages m
    LEFT JOIN public.profiles pr ON pr.user_id = m.sender_id
    LEFT JOIN public.chats c ON c.id = m.chat_id
    WHERE m.id = p_message_id;

    IF v_result IS NULL THEN
        RAISE EXCEPTION 'message not found';
    END IF;

    RETURN v_result;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_get_message(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_get_message(UUID) TO authenticated, service_role;

-- ============================================================================
-- 6. MEDIA (provider-neutral - storage_provider rendered generically)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_list_media(
    p_provider public.storage_provider_type DEFAULT NULL,
    p_mime TEXT DEFAULT NULL,
    p_search TEXT DEFAULT NULL,
    p_page INTEGER DEFAULT 1,
    p_page_size INTEGER DEFAULT 25
)
RETURNS TABLE (
    id UUID,
    owner_id UUID,
    owner_name TEXT,
    storage_provider public.storage_provider_type,
    storage_key TEXT,
    public_url TEXT,
    delivery_url TEXT,
    thumbnail_url TEXT,
    mime_type TEXT,
    size_bytes BIGINT,
    checksum TEXT,
    width INTEGER,
    height INTEGER,
    duration INTEGER,
    created_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    message_id UUID,
    chat_id UUID,
    total BIGINT
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('moderation.read');
    v_page INTEGER := GREATEST(COALESCE(p_page, 1), 1);
    v_page_size INTEGER := LEAST(GREATEST(COALESCE(p_page_size, 25), 1), 100);
    v_offset INTEGER := (v_page - 1) * v_page_size;
BEGIN
    RETURN QUERY
    SELECT med.id, med.owner_id, pr.first_name AS owner_name,
           med.storage_provider, med.storage_key, med.public_url, med.delivery_url,
           med.thumbnail_url, med.mime_type, med.size_bytes, med.checksum,
           med.width, med.height, med.duration,
           med.created_at, med.deleted_at,
           ma.message_id, ms.chat_id,
           COUNT(*) OVER()::BIGINT AS total
    FROM public.media med
    LEFT JOIN public.profiles pr ON pr.user_id = med.owner_id
    LEFT JOIN LATERAL (
        SELECT a.message_id
        FROM public.message_attachments a
        WHERE a.media_id = med.id
        ORDER BY a.created_at LIMIT 1
    ) ma ON TRUE
    LEFT JOIN public.messages ms ON ms.id = ma.message_id
    WHERE (p_provider::public.storage_provider_type IS NULL OR med.storage_provider = p_provider)
      AND (p_mime::text IS NULL OR med.mime_type ILIKE p_mime || '%')
      AND (p_search::text IS NULL
           OR med.storage_key ILIKE '%' || p_search || '%'
           OR med.mime_type ILIKE '%' || p_search || '%')
    ORDER BY med.created_at DESC
    LIMIT p_page_size OFFSET v_offset;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_list_media(public.storage_provider_type, TEXT, TEXT, INTEGER, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_list_media(public.storage_provider_type, TEXT, TEXT, INTEGER, INTEGER) TO authenticated, service_role;

-- ============================================================================
-- 7. REPORTS / MODERATION QUEUE
-- ============================================================================
-- p_kind: 'user' | 'message' | 'all'

CREATE OR REPLACE FUNCTION public.admin_list_reports(
    p_kind TEXT DEFAULT 'all',
    p_status public.report_status DEFAULT NULL,
    p_page INTEGER DEFAULT 1,
    p_page_size INTEGER DEFAULT 25
)
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('moderation.read');
    v_page INTEGER := GREATEST(COALESCE(p_page, 1), 1);
    v_page_size INTEGER := LEAST(GREATEST(COALESCE(p_page_size, 25), 1), 100);
    v_offset INTEGER := (v_page - 1) * v_page_size;
    v_kind TEXT := lower(COALESCE(p_kind, 'all'));
    v_total BIGINT;
    v_rows JSONB;
BEGIN
    IF v_kind NOT IN ('user', 'message', 'all') THEN
        RAISE EXCEPTION 'invalid report kind; expected user, message or all';
    END IF;

    v_total := 0;
    IF v_kind IN ('user', 'all') THEN
        v_total := v_total + (
            SELECT COUNT(*) FROM public.user_reports r
            WHERE (p_status::public.report_status IS NULL OR r.status = p_status)
        );
    END IF;
    IF v_kind IN ('message', 'all') THEN
        v_total := v_total + (
            SELECT COUNT(*) FROM public.message_reports r
            WHERE (p_status::public.report_status IS NULL OR r.status = p_status)
        );
    END IF;

    v_rows := '[]'::jsonb;
    IF v_kind IN ('user', 'all') THEN
        v_rows := COALESCE((
            SELECT jsonb_agg(row_data ORDER BY row_data->>'created_at' DESC)
            FROM (
                SELECT jsonb_build_object(
                    'kind', 'user', 'id', r.id,
                    'reporter', jsonb_build_object('id', r.reporter_id, 'name', rp.first_name, 'username', rp.username),
                    'reported_user', jsonb_build_object('id', r.reported_user_id, 'name', ru.first_name, 'username', ru.username),
                    'reason', r.reason, 'description', r.description,
                    'status', r.status, 'created_at', r.created_at,
                    'resolved_at', r.resolved_at, 'resolved_by', r.resolved_by,
                    'resolution_notes', r.resolution_notes
                ) AS row_data
                FROM public.user_reports r
                LEFT JOIN public.profiles rp ON rp.user_id = r.reporter_id
                LEFT JOIN public.profiles ru ON ru.user_id = r.reported_user_id
                WHERE (p_status::public.report_status IS NULL OR r.status = p_status)
                ORDER BY r.created_at DESC
                LIMIT p_page_size OFFSET v_offset
            ) user_reports_part
        ), '[]'::jsonb);
    END IF;

    IF v_kind IN ('message', 'all') THEN
        v_rows := v_rows || COALESCE((
            SELECT jsonb_agg(row_data ORDER BY row_data->>'created_at' DESC)
            FROM (
                SELECT jsonb_build_object(
                    'kind', 'message', 'id', r.id,
                    'reporter', jsonb_build_object('id', r.reporter_id, 'name', rp.first_name, 'username', rp.username),
                    'message', jsonb_build_object(
                        'id', r.message_id,
                        'content', m.content,
                        'message_type', m.message_type,
                        'sender', jsonb_build_object('id', m.sender_id, 'name', mp.first_name, 'username', mp.username),
                        'created_at', m.created_at
                    ),
                    'chat', jsonb_build_object('id', r.chat_id, 'title', c.title, 'type', c.type),
                    'reason', r.reason, 'description', r.description,
                    'status', r.status, 'created_at', r.created_at,
                    'resolved_at', r.resolved_at, 'resolved_by', r.resolved_by,
                    'resolution_notes', r.resolution_notes
                ) AS row_data
                FROM public.message_reports r
                LEFT JOIN public.profiles rp ON rp.user_id = r.reporter_id
                LEFT JOIN public.messages m ON m.id = r.message_id
                LEFT JOIN public.profiles mp ON mp.user_id = m.sender_id
                LEFT JOIN public.chats c ON c.id = r.chat_id
                WHERE (p_status::public.report_status IS NULL OR r.status = p_status)
                ORDER BY r.created_at DESC
                LIMIT p_page_size OFFSET v_offset
            ) message_reports_part
        ), '[]'::jsonb);
    END IF;

    RETURN jsonb_build_object(
        'items', v_rows,
        'total', v_total,
        'page', v_page,
        'page_size', v_page_size
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_list_reports(TEXT, public.report_status, INTEGER, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_list_reports(TEXT, public.report_status, INTEGER, INTEGER) TO authenticated, service_role;

-- ============================================================================
-- 8. SESSIONS / DEVICES
-- ============================================================================
-- NEVER returns token values, hashes, OTP codes or ip_hash.

CREATE OR REPLACE FUNCTION public.admin_list_sessions(
    p_user_id UUID DEFAULT NULL,
    p_page INTEGER DEFAULT 1,
    p_page_size INTEGER DEFAULT 25
)
RETURNS TABLE (
    id UUID,
    user_id UUID,
    user_name TEXT,
    device_id UUID,
    device_name TEXT,
    platform public.device_platform,
    os_version TEXT,
    app_version TEXT,
    created_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoke_reason public.session_revoke_reason,
    user_agent TEXT,
    total BIGINT
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('users.read');
    v_page INTEGER := GREATEST(COALESCE(p_page, 1), 1);
    v_page_size INTEGER := LEAST(GREATEST(COALESCE(p_page_size, 25), 1), 100);
    v_offset INTEGER := (v_page - 1) * v_page_size;
BEGIN
    RETURN QUERY
    SELECT s.id, s.user_id, pr.first_name AS user_name,
           s.device_id, d.device_name, d.platform, d.os_version, d.app_version,
           s.created_at, s.last_used_at, s.expires_at,
           s.revoked_at, s.revoke_reason, s.user_agent,
           COUNT(*) OVER()::BIGINT AS total
    FROM public.sessions s
    LEFT JOIN public.profiles pr ON pr.user_id = s.user_id
    LEFT JOIN public.devices d ON d.id = s.device_id
    WHERE (p_user_id::uuid IS NULL OR s.user_id = p_user_id)
    ORDER BY s.last_used_at DESC
    LIMIT p_page_size OFFSET v_offset;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_list_sessions(UUID, INTEGER, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_list_sessions(UUID, INTEGER, INTEGER) TO authenticated, service_role;

-- ============================================================================
-- 9. AUDIT LOG / ADMIN ACTION TRAIL
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_list_audit(
    p_action TEXT DEFAULT NULL,
    p_page INTEGER DEFAULT 1,
    p_page_size INTEGER DEFAULT 25
)
RETURNS TABLE (
    id UUID,
    kind TEXT,
    action TEXT,
    actor_type TEXT,
    actor_name TEXT,
    user_id UUID,
    target_type TEXT,
    target_id UUID,
    detail JSONB,
    created_at TIMESTAMPTZ,
    total BIGINT
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('audit.read');
    v_page INTEGER := GREATEST(COALESCE(p_page, 1), 1);
    v_page_size INTEGER := LEAST(GREATEST(COALESCE(p_page_size, 25), 1), 100);
    v_offset INTEGER := (v_page - 1) * v_page_size;
BEGIN
    RETURN QUERY
    SELECT al.id, 'audit_log'::text, al.action::text, al.actor_type,
           pr.first_name AS actor_name, al.user_id,
           NULL::text AS target_type, NULL::uuid AS target_id,
           al.metadata AS detail, al.created_at,
           COUNT(*) OVER()::BIGINT AS total
    FROM public.audit_logs al
    LEFT JOIN public.profiles pr ON pr.user_id = al.actor_id
    WHERE (p_action::text IS NULL OR al.action::text = p_action)
    UNION ALL
    SELECT aa.id, 'admin_action'::text, aa.action, 'admin'::text,
           pr.first_name AS actor_name, au.user_id,
           aa.target_type, aa.target_id,
           jsonb_build_object('previous_state', aa.previous_state, 'new_state', aa.new_state, 'metadata', aa.metadata) AS detail,
           aa.created_at,
           COUNT(*) OVER()::BIGINT AS total
    FROM public.admin_actions aa
    LEFT JOIN public.admin_users au ON au.id = aa.admin_user_id
    LEFT JOIN public.profiles pr ON pr.user_id = au.user_id
    WHERE (p_action::text IS NULL OR aa.action = p_action)
    ORDER BY created_at DESC
    LIMIT p_page_size OFFSET v_offset;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_list_audit(TEXT, INTEGER, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_list_audit(TEXT, INTEGER, INTEGER) TO authenticated, service_role;

-- ============================================================================
-- 10. ADMIN ACCOUNTS + MODERATION ACTIONS
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_list_admin_users()
RETURNS TABLE (
    id UUID,
    user_id UUID,
    username CITEXT,
    first_name TEXT,
    email CITEXT,
    role public.admin_role,
    assigned_by_name TEXT,
    assigned_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    is_active BOOLEAN,
    created_at TIMESTAMPTZ,
    permissions TEXT[]
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('users.read');
BEGIN
    RETURN QUERY
    SELECT au.id, au.user_id, pr.username, pr.first_name,
           ui.email, au.role, ap2.first_name AS assigned_by_name,
           au.assigned_at, au.expires_at, au.is_active, au.created_at,
           ARRAY(
               SELECT perm.permission FROM public.admin_permissions perm
               WHERE perm.role = au.role
               ORDER BY perm.permission
           ) AS permissions
    FROM public.admin_users au
    LEFT JOIN public.profiles pr ON pr.user_id = au.user_id
    LEFT JOIN LATERAL (
        SELECT id.email FROM public.user_identities id
        WHERE id.user_id = au.user_id AND id.email IS NOT NULL
        ORDER BY id.created_at LIMIT 1
    ) ui ON TRUE
    LEFT JOIN public.profiles ap2 ON ap2.user_id = au.assigned_by
    ORDER BY au.created_at;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_list_admin_users() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_list_admin_users() TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_list_moderation_actions(
    p_target_user_id UUID DEFAULT NULL,
    p_page INTEGER DEFAULT 1,
    p_page_size INTEGER DEFAULT 25
)
RETURNS TABLE (
    id UUID,
    action_type public.moderation_action_type,
    target_user_id UUID,
    target_user_name TEXT,
    target_content_type TEXT,
    target_content_id UUID,
    moderator_id UUID,
    moderator_name TEXT,
    reason TEXT,
    expires_at TIMESTAMPTZ,
    is_active BOOLEAN,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ,
    total BIGINT
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('moderation.read');
    v_page INTEGER := GREATEST(COALESCE(p_page, 1), 1);
    v_page_size INTEGER := LEAST(GREATEST(COALESCE(p_page_size, 25), 1), 100);
    v_offset INTEGER := (v_page - 1) * v_page_size;
BEGIN
    RETURN QUERY
    SELECT ma.id, ma.action_type, ma.target_user_id, tpr.first_name AS target_user_name,
           ma.target_content_type, ma.target_content_id,
           ma.moderator_id, mpr.first_name AS moderator_name,
           ma.reason, ma.expires_at, ma.is_active, ma.revoked_at, ma.created_at,
           COUNT(*) OVER()::BIGINT AS total
    FROM public.moderation_actions ma
    LEFT JOIN public.profiles tpr ON tpr.user_id = ma.target_user_id
    LEFT JOIN public.profiles mpr ON mpr.user_id = ma.moderator_id
    WHERE (p_target_user_id::uuid IS NULL OR ma.target_user_id = p_target_user_id)
    ORDER BY ma.created_at DESC
    LIMIT p_page_size OFFSET v_offset;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_list_moderation_actions(UUID, INTEGER, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_list_moderation_actions(UUID, INTEGER, INTEGER) TO authenticated, service_role;

-- ============================================================================
-- 11. GLOBAL SEARCH (indexed/schema-backed fields only)
-- ============================================================================
-- Searches users by username/email/id, chats by title/username/id and messages
-- by id / client_message_id. Content body search is intentionally excluded
-- (an unbounded ILIKE over a massive message table). Result-capped.

CREATE OR REPLACE FUNCTION public.admin_search_global(
    p_query TEXT,
    p_limit INTEGER DEFAULT 25
)
RETURNS TABLE (
    kind TEXT,
    id UUID,
    label TEXT,
    sublabel TEXT,
    created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('users.read');
    v_limit INTEGER := LEAST(GREATEST(COALESCE(p_limit, 25), 1), 100);
    v_query TEXT;
BEGIN
    IF p_query IS NULL OR btrim(p_query) = '' THEN
        RETURN;
    END IF;
    v_query := btrim(p_query);

    RETURN QUERY
    SELECT * FROM (
        SELECT 'user'::text AS kind, u.id,
               COALESCE(pr.first_name, '') || COALESCE(' ' || pr.last_name, '') AS label,
               COALESCE(pr.username::text, '') AS sublabel,
               u.created_at
        FROM public.users u
        LEFT JOIN public.profiles pr ON pr.user_id = u.id
        WHERE u.deleted_at IS NULL
          AND (
              u.id::text = v_query
              OR pr.username::text ILIKE v_query || '%'
              OR pr.first_name ILIKE v_query || '%'
              OR EXISTS (
                  SELECT 1 FROM public.user_identities id
                  WHERE id.user_id = u.id AND id.email::text ILIKE v_query || '%'
              )
          )
        UNION ALL
        SELECT 'chat'::text, c.id,
               COALESCE(c.title, c.type::text),
               COALESCE(c.username::text, ''),
               c.created_at
        FROM public.chats c
        WHERE c.deleted_at IS NULL
          AND (c.id::text = v_query OR c.username::text ILIKE v_query || '%' OR c.title ILIKE v_query || '%')
        UNION ALL
        SELECT 'message'::text, m.id,
               m.message_type::text,
               COALESCE(m.client_message_id, ''),
               m.created_at
        FROM public.messages m
        WHERE m.id::text = v_query OR m.client_message_id = v_query
    ) results
    ORDER BY created_at DESC
    LIMIT v_limit;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_search_global(TEXT, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_search_global(TEXT, INTEGER) TO authenticated, service_role;

-- ============================================================================
-- 12. ADMIN ACTIONS (write RPCs)
-- ============================================================================
-- Every write RPC: (a) admin_require() permission gate, (b) minimal privileged
-- write, (c) append to admin_actions with previous/new state, (d) mirror into
-- audit_logs where the existing audit_action enum has a matching value.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 12a. RESOLVE / REOPEN REPORTS
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.admin_resolve_report(
    p_kind TEXT,
    p_report_id UUID,
    p_status public.report_status,
    p_notes TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('moderation.write');
    v_target_table TEXT;
    v_previous JSONB;
    v_new JSONB;
    v_found BOOLEAN := FALSE;
BEGIN
    IF p_report_id IS NULL OR p_kind IS NULL OR p_status IS NULL THEN
        RAISE EXCEPTION 'report kind, id and status are required';
    END IF;
    IF p_status NOT IN ('resolved_action_taken', 'resolved_no_action', 'dismissed') THEN
        RAISE EXCEPTION 'invalid resolution status; use resolved_action_taken, resolved_no_action or dismissed';
    END IF;

    IF p_kind = 'message' THEN
        SELECT jsonb_build_object('status', status, 'created_at', created_at)
               INTO v_previous
        FROM public.message_reports WHERE id = p_report_id;
        IF FOUND THEN
            UPDATE public.message_reports
            SET status = p_status, resolved_at = NOW(),
                resolved_by = public.current_user_id(),
                resolution_notes = p_notes
            WHERE id = p_report_id;
            v_found := TRUE;
            v_target_table := 'message_report';
        END IF;
    ELSIF p_kind = 'user' THEN
        SELECT jsonb_build_object('status', status, 'created_at', created_at)
               INTO v_previous
        FROM public.user_reports WHERE id = p_report_id;
        IF FOUND THEN
            UPDATE public.user_reports
            SET status = p_status, resolved_at = NOW(),
                resolved_by = public.current_user_id(),
                resolution_notes = p_notes
            WHERE id = p_report_id;
            v_found := TRUE;
            v_target_table := 'user_report';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid report kind; expected user or message';
    END IF;

    IF NOT v_found THEN
        RAISE EXCEPTION 'report not found';
    END IF;

    v_new := jsonb_build_object('status', p_status, 'resolved_by', public.current_user_id(), 'resolution_notes', p_notes);

    INSERT INTO public.admin_actions (admin_user_id, action, target_type, target_id, previous_state, new_state, metadata)
    VALUES (v_admin_id, 'report.resolve', v_target_table, p_report_id, v_previous, v_new,
            jsonb_build_object('notes', p_notes));

    RETURN jsonb_build_object('success', TRUE, 'report_id', p_report_id, 'status', p_status);
END;
$$;

REVOKE ALL ON FUNCTION public.admin_resolve_report(TEXT, UUID, public.report_status, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_resolve_report(TEXT, UUID, public.report_status, TEXT) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_reopen_report(
    p_kind TEXT,
    p_report_id UUID
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('moderation.write');
    v_target_table TEXT;
    v_previous JSONB;
    v_found BOOLEAN := FALSE;
BEGIN
    IF p_report_id IS NULL OR p_kind IS NULL THEN
        RAISE EXCEPTION 'report kind and id are required';
    END IF;

    IF p_kind = 'message' THEN
        SELECT jsonb_build_object('status', status, 'resolved_at', resolved_at)
               INTO v_previous
        FROM public.message_reports WHERE id = p_report_id;
        IF FOUND THEN
            UPDATE public.message_reports
            SET status = 'under_review', resolved_at = NULL, resolved_by = NULL
            WHERE id = p_report_id;
            v_found := TRUE;
            v_target_table := 'message_report';
        END IF;
    ELSIF p_kind = 'user' THEN
        SELECT jsonb_build_object('status', status, 'resolved_at', resolved_at)
               INTO v_previous
        FROM public.user_reports WHERE id = p_report_id;
        IF FOUND THEN
            UPDATE public.user_reports
            SET status = 'under_review', resolved_at = NULL, resolved_by = NULL
            WHERE id = p_report_id;
            v_found := TRUE;
            v_target_table := 'user_report';
        END IF;
    ELSE
        RAISE EXCEPTION 'invalid report kind; expected user or message';
    END IF;

    IF NOT v_found THEN
        RAISE EXCEPTION 'report not found';
    END IF;

    INSERT INTO public.admin_actions (admin_user_id, action, target_type, target_id, previous_state, new_state)
    VALUES (v_admin_id, 'report.reopen', v_target_table, p_report_id, v_previous,
            jsonb_build_object('status', 'under_review', 'resolved_at', NULL));

    RETURN jsonb_build_object('success', TRUE, 'report_id', p_report_id, 'status', 'under_review');
END;
$$;

REVOKE ALL ON FUNCTION public.admin_reopen_report(TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_reopen_report(TEXT, UUID) TO authenticated, service_role;

-- ---------------------------------------------------------------------------
-- 12b. REVOKE SESSION (through the safe server-side path)
-- ---------------------------------------------------------------------------
-- Mirrors revoke_own_session() but for any user: sets revoked_at, revokes the
-- whole refresh-token family, records the admin action and an audit_log entry.

CREATE OR REPLACE FUNCTION public.admin_revoke_session(
    p_session_id UUID,
    p_reason TEXT DEFAULT 'admin_action'
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('users.suspend');
    v_session_user UUID;
    v_family UUID;
    v_previous JSONB;
BEGIN
    IF p_session_id IS NULL THEN
        RAISE EXCEPTION 'session_id is required';
    END IF;

    SELECT user_id, jsonb_build_object('revoked_at', revoked_at) INTO v_session_user, v_previous
    FROM public.sessions WHERE id = p_session_id;

    IF v_session_user IS NULL THEN
        RAISE EXCEPTION 'session not found';
    END IF;

    UPDATE public.sessions
    SET revoked_at = COALESCE(revoked_at, NOW()),
        revoke_reason = COALESCE(revoke_reason, 'admin_action')
    WHERE id = p_session_id
      AND revoked_at IS NULL;

    -- Revoke the whole refresh-token family for this session
    SELECT family_id INTO v_family
    FROM public.refresh_tokens
    WHERE session_id = p_session_id
    LIMIT 1;

    IF v_family IS NOT NULL THEN
        UPDATE public.refresh_tokens
        SET revoked_at = COALESCE(revoked_at, NOW())
        WHERE family_id = v_family AND revoked_at IS NULL;
    END IF;

    INSERT INTO public.admin_actions (admin_user_id, action, target_type, target_id, previous_state, new_state, metadata)
    VALUES (v_admin_id, 'session.revoke', 'session', p_session_id, v_previous,
            jsonb_build_object('revoked_at', NOW(), 'revoke_reason', 'admin_action'),
            jsonb_build_object('reason', p_reason));

    INSERT INTO public.audit_logs (user_id, action, actor_type, actor_id, metadata)
    VALUES (v_session_user, 'session_revoked', 'admin', public.current_user_id(),
            jsonb_build_object('session_id', p_session_id, 'admin_action', TRUE, 'reason', p_reason));

    RETURN jsonb_build_object('success', TRUE, 'session_id', p_session_id, 'revoked_at', NOW());
END;
$$;

REVOKE ALL ON FUNCTION public.admin_revoke_session(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_revoke_session(UUID, TEXT) TO authenticated, service_role;

-- ---------------------------------------------------------------------------
-- 12c. SUSPEND / REINSTATE USER
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.admin_suspend_user(
    p_user_id UUID,
    p_reason TEXT DEFAULT NULL,
    p_expires_at TIMESTAMPTZ DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('users.suspend');
    v_previous JSONB;
    v_restriction_type public.restriction_type;
    v_target_status public.user_status;
BEGIN
    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'user_id is required';
    END IF;

    SELECT jsonb_build_object('status', status, 'updated_at', updated_at), status
           INTO v_previous, v_target_status
    FROM public.users WHERE id = p_user_id;

    IF v_target_status IS NULL THEN
        RAISE EXCEPTION 'user not found';
    END IF;

    UPDATE public.users
    SET status = 'suspended', updated_at = NOW()
    WHERE id = p_user_id;

    v_restriction_type := CASE WHEN p_expires_at IS NULL THEN 'ban'::public.restriction_type
                               ELSE 'temporary_restriction'::public.restriction_type END;

    INSERT INTO public.user_restrictions (user_id, restriction_type, reason, issued_by, expires_at, is_active)
    VALUES (p_user_id, v_restriction_type, p_reason, public.current_user_id(), p_expires_at, TRUE);

    INSERT INTO public.admin_actions (admin_user_id, action, target_type, target_id, previous_state, new_state, metadata)
    VALUES (v_admin_id, 'user.suspend', 'user', p_user_id, v_previous,
            jsonb_build_object('status', 'suspended'),
            jsonb_build_object('reason', p_reason, 'expires_at', p_expires_at,
                               'restriction_type', v_restriction_type));

    INSERT INTO public.audit_logs (user_id, action, actor_type, actor_id, metadata)
    VALUES (p_user_id, 'account_suspended', 'admin', public.current_user_id(),
            jsonb_build_object('reason', p_reason, 'expires_at', p_expires_at));

    RETURN jsonb_build_object('success', TRUE, 'user_id', p_user_id, 'status', 'suspended');
END;
$$;

REVOKE ALL ON FUNCTION public.admin_suspend_user(UUID, TEXT, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_suspend_user(UUID, TEXT, TIMESTAMPTZ) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_reinstate_user(
    p_user_id UUID,
    p_reason TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('users.suspend');
    v_previous JSONB;
    v_target_status public.user_status;
BEGIN
    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'user_id is required';
    END IF;

    SELECT jsonb_build_object('status', status), status INTO v_previous, v_target_status
    FROM public.users WHERE id = p_user_id;

    IF v_target_status IS NULL THEN
        RAISE EXCEPTION 'user not found';
    END IF;

    UPDATE public.users
    SET status = 'active', updated_at = NOW()
    WHERE id = p_user_id;

    UPDATE public.user_restrictions
    SET is_active = FALSE, revoked_at = NOW(), revoked_by = public.current_user_id(),
        revocation_reason = COALESCE(revocation_reason, p_reason)
    WHERE user_id = p_user_id AND is_active = TRUE;

    INSERT INTO public.admin_actions (admin_user_id, action, target_type, target_id, previous_state, new_state, metadata)
    VALUES (v_admin_id, 'user.reinstate', 'user', p_user_id, v_previous,
            jsonb_build_object('status', 'active'),
            jsonb_build_object('reason', p_reason));

    INSERT INTO public.audit_logs (user_id, action, actor_type, actor_id, metadata)
    VALUES (p_user_id, 'account_reinstated', 'admin', public.current_user_id(),
            jsonb_build_object('reason', p_reason));

    RETURN jsonb_build_object('success', TRUE, 'user_id', p_user_id, 'status', 'active');
END;
$$;

REVOKE ALL ON FUNCTION public.admin_reinstate_user(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_reinstate_user(UUID, TEXT) TO authenticated, service_role;

-- ---------------------------------------------------------------------------
-- 12d. MODERATOR CONTENT REMOVAL (soft delete, audit-preserving)
-- ---------------------------------------------------------------------------
-- Like delete_message() this is a soft delete (messages.deleted_at tombstone);
-- the row + history stay intact server-side and become invisible to chat
-- members via the existing RLS policy. It additionally records a
-- moderation_actions row + admin_actions + audit_logs.

CREATE OR REPLACE FUNCTION public.admin_delete_message(
    p_message_id UUID,
    p_reason TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_admin_id UUID := public.admin_require('moderation.write');
    v_sender_id UUID;
    v_previous JSONB;
BEGIN
    IF p_message_id IS NULL THEN
        RAISE EXCEPTION 'message_id is required';
    END IF;

    SELECT sender_id, jsonb_build_object('deleted_at', deleted_at, 'content', content)
           INTO v_sender_id, v_previous
    FROM public.messages WHERE id = p_message_id;

    IF v_sender_id IS NULL THEN
        RAISE EXCEPTION 'message not found';
    END IF;

    UPDATE public.messages
    SET deleted_at = NOW(), updated_at = NOW()
    WHERE id = p_message_id
      AND deleted_at IS NULL;

    INSERT INTO public.moderation_actions (action_type, target_user_id, target_content_type, target_content_id,
                                           moderator_id, reason, is_active)
    VALUES ('remove_content', v_sender_id, 'message', p_message_id,
            public.current_user_id(), p_reason, TRUE);

    INSERT INTO public.admin_actions (admin_user_id, action, target_type, target_id, previous_state, new_state, metadata)
    VALUES (v_admin_id, 'content.remove', 'message', p_message_id, v_previous,
            jsonb_build_object('deleted_at', NOW()),
            jsonb_build_object('reason', p_reason));

    INSERT INTO public.audit_logs (user_id, action, actor_type, actor_id, metadata)
    VALUES (v_sender_id, 'content_removed', 'admin', public.current_user_id(),
            jsonb_build_object('message_id', p_message_id, 'reason', p_reason));

    RETURN jsonb_build_object('success', TRUE, 'message_id', p_message_id, 'deleted_at', NOW());
END;
$$;

REVOKE ALL ON FUNCTION public.admin_delete_message(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin_delete_message(UUID, TEXT) TO authenticated, service_role;
