-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 019: HARDEN ADMIN / AUDIT INTEGRITY
-- ============================================================================
--
--  1. Admin policies self-referenced admin_users (infinite recursion). Rewritten
--     via the SECURITY DEFINER is_active_admin() helper, which is also
--     expiry-aware (an expired admin no longer counts as active).
--  2. premium_requests: a user could INSERT with status='approved' or UPDATE
--     their own request to 'approved'/rewrite review fields. Now:
--       - INSERT forced to status='pending', reviewed_* NULL
--       - UPDATE only while status='pending' and cannot change status
--       - client UPDATE grant narrowed to (reason, requested_features)
--  3. premium_entitlements SELECT policy self-referenced admin_users
--     (recursion) - rewritten via is_active_admin().
--  4. Audit-history FK integrity: critical history must not vanish because a
--     target user/admin is deleted (ON DELETE CASCADE). Changed to SET NULL:
--       - moderation_actions.target_user_id
--       - user_reports.reported_user_id
--       - user_restrictions.user_id
--       - admin_actions.admin_user_id
--  5. audit_logs / admin_actions are append-only (UPDATE blocked) so history
--     cannot be forged. DELETE remains available to the service role for
--     documented retention.
--  6. get_public_profile respects privacy_settings.last_seen_visibility.
-- ============================================================================

-- ============================================================================
-- 1. ADMIN POLICIES (recursion-free, expiry-aware)
-- ============================================================================

DROP POLICY IF EXISTS admin_users_select_own_or_super ON admin_users;
CREATE POLICY admin_users_select_own_or_super ON admin_users
    FOR SELECT USING (
        user_id = current_user_id()
        OR public.is_active_admin('super_admin')
    );

DROP POLICY IF EXISTS admin_permissions_select_admins ON admin_permissions;
CREATE POLICY admin_permissions_select_admins ON admin_permissions
    FOR SELECT USING (
        public.is_active_admin()
    );

DROP POLICY IF EXISTS admin_actions_select_visible ON admin_actions;
CREATE POLICY admin_actions_select_visible ON admin_actions
    FOR SELECT USING (
        admin_user_id IN (SELECT id FROM public.admin_users WHERE user_id = current_user_id())
        OR public.is_active_admin('super_admin')
    );

DROP POLICY IF EXISTS audit_logs_select_visible ON audit_logs;
CREATE POLICY audit_logs_select_visible ON audit_logs
    FOR SELECT USING (
        user_id = current_user_id()
        OR public.is_active_admin('super_admin')
        OR public.is_active_admin('readonly')
    );

DROP POLICY IF EXISTS moderation_actions_select_visible ON moderation_actions;
CREATE POLICY moderation_actions_select_visible ON moderation_actions
    FOR SELECT USING (
        target_user_id = current_user_id()
        OR public.is_active_admin()
    );

-- ============================================================================
-- 2. PREMIUM REQUESTS: NO CLIENT-SIDE APPROVAL
-- ============================================================================

DROP POLICY IF EXISTS premium_requests_insert_own ON premium_requests;
CREATE POLICY premium_requests_insert_own ON premium_requests
    FOR INSERT WITH CHECK (
        user_id = current_user_id()
        AND status = 'pending'
        AND reviewed_by IS NULL
        AND reviewed_at IS NULL
    );

DROP POLICY IF EXISTS premium_requests_update_own ON premium_requests;
CREATE POLICY premium_requests_update_own ON premium_requests
    FOR UPDATE USING (
        user_id = current_user_id() AND status = 'pending'
    )
    WITH CHECK (
        user_id = current_user_id() AND status = 'pending'
    );

REVOKE UPDATE ON premium_requests FROM authenticated;
GRANT UPDATE (reason, requested_features) ON premium_requests TO authenticated;

-- ============================================================================
-- 3. PREMIUM ENTITLEMENTS (recursion fix)
-- ============================================================================

DROP POLICY IF EXISTS premium_entitlements_select_own ON premium_entitlements;
CREATE POLICY premium_entitlements_select_own ON premium_entitlements
    FOR SELECT USING (
        user_id = current_user_id()
        OR public.is_active_admin()
    );

-- ============================================================================
-- 4. AUDIT-HISTORY FOREIGN KEY INTEGRITY
-- ============================================================================
-- Never destroy history when the referenced user/admin is deleted. Columns are
-- made nullable where required and the FK switched to SET NULL.

ALTER TABLE public.moderation_actions
    DROP CONSTRAINT IF EXISTS moderation_actions_target_user_id_fkey;
ALTER TABLE public.moderation_actions
    ADD CONSTRAINT moderation_actions_target_user_id_fkey
    FOREIGN KEY (target_user_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE public.user_reports
    ALTER COLUMN reported_user_id DROP NOT NULL;
ALTER TABLE public.user_reports
    DROP CONSTRAINT IF EXISTS user_reports_reported_user_id_fkey;
ALTER TABLE public.user_reports
    ADD CONSTRAINT user_reports_reported_user_id_fkey
    FOREIGN KEY (reported_user_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE public.user_restrictions
    ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE public.user_restrictions
    DROP CONSTRAINT IF EXISTS user_restrictions_user_id_fkey;
ALTER TABLE public.user_restrictions
    ADD CONSTRAINT user_restrictions_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE public.admin_actions
    ALTER COLUMN admin_user_id DROP NOT NULL;
ALTER TABLE public.admin_actions
    DROP CONSTRAINT IF EXISTS admin_actions_admin_user_id_fkey;
ALTER TABLE public.admin_actions
    ADD CONSTRAINT admin_actions_admin_user_id_fkey
    FOREIGN KEY (admin_user_id) REFERENCES public.admin_users(id) ON DELETE SET NULL;

-- ============================================================================
-- 5. APPEND-ONLY AUDIT (anti-forgery)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.prevent_audit_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; updates are not permitted', TG_TABLE_NAME;
END;
$$;

DROP TRIGGER IF EXISTS trigger_audit_logs_append_only ON audit_logs;
CREATE TRIGGER trigger_audit_logs_append_only
    BEFORE UPDATE ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION public.prevent_audit_update();

DROP TRIGGER IF EXISTS trigger_admin_actions_append_only ON admin_actions;
CREATE TRIGGER trigger_admin_actions_append_only
    BEFORE UPDATE ON admin_actions
    FOR EACH ROW
    EXECUTE FUNCTION public.prevent_audit_update();

-- ============================================================================
-- 6. get_public_profile(): RESPECT LAST-SEEN PRIVACY
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_public_profile(p_user_id UUID)
RETURNS TABLE (
    user_id UUID,
    username CITEXT,
    first_name TEXT,
    last_name TEXT,
    bio TEXT,
    avatar_url TEXT,
    presence_status public.presence_status,
    last_seen_at TIMESTAMPTZ
)
LANGUAGE plpgsql
STABLE
SET search_path = public
AS $$
DECLARE
    v_last_seen_vis public.privacy_visibility;
BEGIN
    SELECT last_seen_visibility INTO v_last_seen_vis
    FROM public.privacy_settings WHERE user_id = p_user_id;

    RETURN QUERY
    SELECT
        p.user_id,
        p.username,
        p.first_name,
        p.last_name,
        p.bio,
        m.public_url,
        COALESCE(pres.status, 'offline'::public.presence_status),
        CASE
            WHEN COALESCE(v_last_seen_vis, 'everyone'::public.privacy_visibility) = 'everyone'
            THEN pres.last_seen_at
            ELSE NULL
        END
    FROM public.profiles p
    LEFT JOIN public.media m ON m.id = p.avatar_media_id
    LEFT JOIN public.user_presence pres ON pres.user_id = p.user_id
    WHERE p.user_id = p_user_id;
END;
$$;
