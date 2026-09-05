-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 015: HARDEN IDENTITY & AUTHENTICATION
-- ============================================================================
-- Forward hardening of the identity/auth layer.
--
-- Fixes:
--  1. current_user_id(): hardened search_path + STABLE, idiomatic Supabase form.
--  2. Shared SECURITY DEFINER authorization helpers used by RLS policies across
--     the schema (is_chat_member, is_chat_admin, is_chat_owner, is_active_admin,
--     can_view_story). These exist so policies NEVER self-reference their own
--     table (which causes "infinite recursion detected in policy" errors) and
--     so membership/visibility logic is centralized and testable.
--  3. sessions: clients may only SELECT their own sessions. Creating/updating/
--     revoking sessions is a privileged, service-side operation. A
--     SECURITY DEFINER revoke_own_session() is provided for self-revocation.
--  4. user_identities: clients cannot insert an identity already marked
--     email_verified=TRUE, and cannot flip email_verified themselves.
--  5. email_verifications: clients cannot create/modify verification rows
--     (that would let them self-issue an OTP with a known hash). They may only
--     read their own rows, with code_hash hidden. Verification happens through
--     the SECURITY DEFINER verify_email_code() which proves the OTP.
--  6. devices: UPDATE policy gains WITH CHECK so user_id cannot be moved.
--  7. user_presence: missing INSERT/UPDATE grants added so the client can
--     actually update its own presence (the original policy was dead).
-- ============================================================================

-- ============================================================================
-- 1. HARDEN current_user_id()
-- ============================================================================
-- Idiomatic Supabase form: read the JWT claims set by the API layer.
-- NOTE: production relies on PostgREST/GoTrue setting request.jwt.* from a
-- verified JWT. Do NOT expose a set_config() RPC to clients (that would let
-- them forge request.jwt.claims).

CREATE OR REPLACE FUNCTION public.current_user_id()
RETURNS UUID
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
    SELECT NULLIF(
        COALESCE(
            NULLIF(current_setting('request.jwt.claim.sub', TRUE), ''),
            NULLIF(current_setting('request.jwt.claims', TRUE), '')::jsonb ->> 'sub'
        ),
        ''
    )::uuid;
$$;

-- ============================================================================
-- 2. SHARED SECURITY DEFINER AUTHORIZATION HELPERS
-- ============================================================================
-- SECURITY DEFINER is justified here: these are pure read-only membership /
-- visibility checks that must bypass RLS to be usable inside RLS policy
-- expressions without self-reference recursion. They take no user-supplied
-- identity that is trusted blindly - they always resolve the caller via
-- current_user_id(). search_path is fixed to avoid object hijacking.

CREATE OR REPLACE FUNCTION public.is_chat_member(p_chat_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.chat_members cm
        WHERE cm.chat_id = p_chat_id
          AND cm.user_id = public.current_user_id()
          AND cm.left_at IS NULL
    );
$$;

CREATE OR REPLACE FUNCTION public.is_chat_admin(p_chat_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.chat_members cm
        WHERE cm.chat_id = p_chat_id
          AND cm.user_id = public.current_user_id()
          AND cm.left_at IS NULL
          AND cm.role IN ('admin', 'owner')
    );
$$;

CREATE OR REPLACE FUNCTION public.is_chat_owner(p_chat_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.chat_members cm
        WHERE cm.chat_id = p_chat_id
          AND cm.user_id = public.current_user_id()
          AND cm.left_at IS NULL
          AND cm.role = 'owner'
    );
$$;

CREATE OR REPLACE FUNCTION public.is_active_admin(p_role public.admin_role DEFAULT NULL)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.admin_users a
        WHERE a.user_id = public.current_user_id()
          AND a.is_active = TRUE
          AND (a.expires_at IS NULL OR a.expires_at > NOW())
          AND (p_role IS NULL OR a.role = p_role)
    );
$$;

CREATE OR REPLACE FUNCTION public.can_view_story(p_story_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.stories s
        WHERE s.id = p_story_id
          AND s.deleted_at IS NULL
          AND (s.expires_at > NOW() OR s.is_pinned = TRUE)
          AND (
              s.privacy = 'everyone'
              OR (s.privacy = 'custom' AND EXISTS (
                      SELECT 1 FROM public.story_custom_audience sca
                      WHERE sca.story_id = s.id
                        AND sca.user_id = public.current_user_id()
                  ))
          )
    );
$$;

-- ============================================================================
-- 3. SESSIONS: CLIENT = SELECT ONLY + revoke_own_session()
-- ============================================================================
-- The client must not be able to create arbitrary sessions, extend expiry,
-- un-revoke, or otherwise manipulate session state. All writes are the auth
-- service's job (service_role). Self-revocation is exposed as a purpose-built
-- SECURITY DEFINER function that performs its own ownership check.

REVOKE INSERT, UPDATE, DELETE ON sessions FROM authenticated;

CREATE OR REPLACE FUNCTION public.revoke_own_session(p_session_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_family UUID;
BEGIN
    IF v_user_id IS NULL OR p_session_id IS NULL THEN
        RETURN;
    END IF;

    UPDATE public.sessions
    SET revoked_at = COALESCE(revoked_at, NOW()),
        revoke_reason = COALESCE(revoke_reason, 'user_requested')
    WHERE id = p_session_id
      AND user_id = v_user_id
      AND revoked_at IS NULL;

    IF FOUND THEN
        -- Revoke the whole token family for this session
        SELECT family_id INTO v_family
        FROM public.refresh_tokens
        WHERE session_id = p_session_id
        LIMIT 1;
        IF v_family IS NOT NULL THEN
            UPDATE public.refresh_tokens
            SET revoked_at = COALESCE(revoked_at, NOW())
            WHERE family_id = v_family
              AND revoked_at IS NULL;
        END IF;

        INSERT INTO public.audit_logs (user_id, action, metadata)
        VALUES (v_user_id, 'session_revoked', jsonb_build_object('session_id', p_session_id));
    END IF;
END;
$$;

GRANT EXECUTE ON FUNCTION public.revoke_own_session(UUID) TO authenticated;

-- ============================================================================
-- 4. USER_IDENTITIES: VERIFIED FLAG IS NOT CLIENT-WRITABLE
-- ============================================================================

DROP POLICY IF EXISTS user_identities_insert_own ON user_identities;
CREATE POLICY user_identities_insert_own ON user_identities
    FOR INSERT WITH CHECK (
        user_id = current_user_id()
        AND email_verified = FALSE
    );

DROP POLICY IF EXISTS user_identities_update_own ON user_identities;
CREATE POLICY user_identities_update_own ON user_identities
    FOR UPDATE USING (
        user_id = current_user_id()
        AND email_verified = FALSE
    )
    WITH CHECK (
        user_id = current_user_id()
        AND email_verified = FALSE
    );

-- ============================================================================
-- 5. EMAIL_VERIFICATIONS: CLIENT CANNOT SELF-ISSUE OTPs
-- ============================================================================
-- Verification rows are created by the auth service. Clients may only read
-- their own rows (code_hash hidden) and prove an OTP through verify_email_code().

REVOKE INSERT, UPDATE ON email_verifications FROM authenticated;

-- Hide code_hash from clients via column-level SELECT grant
REVOKE SELECT ON email_verifications FROM authenticated;
GRANT SELECT (id, user_id, email, purpose, expires_at, attempts, max_attempts, verified_at, created_at)
    ON email_verifications TO authenticated;

CREATE OR REPLACE FUNCTION public.verify_email_code(p_verification_id UUID, p_code TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_rec public.email_verifications%ROWTYPE;
    v_ok BOOLEAN := FALSE;
BEGIN
    IF v_user_id IS NULL OR p_verification_id IS NULL OR p_code IS NULL OR p_code = '' THEN
        RETURN FALSE;
    END IF;

    SELECT * INTO v_rec
    FROM public.email_verifications ev
    WHERE ev.id = p_verification_id AND ev.user_id = v_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    -- Idempotent success
    IF v_rec.verified_at IS NOT NULL THEN
        RETURN TRUE;
    END IF;

    IF v_rec.expires_at <= NOW() THEN
        RETURN FALSE;
    END IF;

    IF v_rec.attempts >= v_rec.max_attempts THEN
        RETURN FALSE;
    END IF;

    IF public.crypt(p_code, v_rec.code_hash) = v_rec.code_hash THEN
        UPDATE public.email_verifications
        SET verified_at = NOW()
        WHERE id = v_rec.id;

        UPDATE public.user_identities
        SET email_verified = TRUE
        WHERE user_id = v_user_id
          AND provider = 'email'
          AND email = v_rec.email;

        UPDATE public.users
        SET status = 'active'
        WHERE id = v_user_id AND status = 'pending_verification';

        v_ok := TRUE;
    ELSE
        UPDATE public.email_verifications
        SET attempts = attempts + 1
        WHERE id = v_rec.id;
    END IF;

    RETURN v_ok;
END;
$$;

GRANT EXECUTE ON FUNCTION public.verify_email_code(UUID, TEXT) TO authenticated;

-- ============================================================================
-- 6. DEVICES: OWNERSHIP CANNOT MOVE
-- ============================================================================

DROP POLICY IF EXISTS devices_update_own ON devices;
CREATE POLICY devices_update_own ON devices
    FOR UPDATE USING (user_id = current_user_id())
    WITH CHECK (user_id = current_user_id());

-- ============================================================================
-- 7. USER_PRESENCE: CLIENT CAN UPDATE OWN PRESENCE
-- ============================================================================
-- The original schema defined user_presence_update_own but granted the client
-- only SELECT, so the policy was dead. Add INSERT + UPDATE grants.

GRANT INSERT, UPDATE ON user_presence TO authenticated;

DROP POLICY IF EXISTS user_presence_update_own ON user_presence;
CREATE POLICY user_presence_update_own ON user_presence
    FOR UPDATE USING (user_id = current_user_id())
    WITH CHECK (user_id = current_user_id());

CREATE POLICY user_presence_insert_own ON user_presence
    FOR INSERT WITH CHECK (user_id = current_user_id());
