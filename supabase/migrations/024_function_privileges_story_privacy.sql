-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 024: FUNCTION PRIVILEGES + STORY PRIVACY
-- ============================================================================
--
-- Mandatory Fix 3 (no PUBLIC EXECUTE on SECURITY DEFINER functions),
-- Mandatory Fix 8 (close_friends story privacy must fail closed, never leak),
-- Mandatory Fix 9 (information-disclosure RPCs must require authentication and
-- enforce membership / self-scoping).
--
--  1. FIX 3: SECURITY DEFINER helpers used inside RLS policies
--     (is_chat_member / is_chat_admin / is_chat_owner / is_active_admin /
--     can_view_story) were PUBLIC-executable. PUBLIC could probe chat
--     membership / admin status for arbitrary chat_ids, and `anon` could call
--     can_view_story() to probe story visibility. PUBLIC EXECUTE is revoked;
--     EXECUTE is granted only to `authenticated` (policy evaluation runs as
--     the querying role) and `service_role`.
--  2. FIX 9: information-disclosure RPCs are locked to `authenticated` +
--     `service_role` and hardened:
--       - get_poll_option_vote_count(): returns 0 unless the caller is a
--         member of the chat that contains the poll.
--       - user_has_premium_feature() / user_has_active_restriction(): only the
--         caller's OWN row is visible; querying another user returns FALSE.
--       - message_is_deleted_for_user(): self-scoped + membership required.
--       - get_user_chat_summary() / get_user_unread_count(): authenticated only
--         (they already resolve the caller internally).
--       - maintenance jobs (cleanup_expired_email_verifications,
--         expire_user_restrictions, auto_close_expired_polls,
--         detect_refresh_token_reuse): service_role only - no public probing.
--  3. FIX 8: can_view_story() is re-declared with an EXPLICIT fail-closed
--     branch for close_friends / nobody. There is no audience table for these
--     privacy levels, so non-owners can never satisfy the predicate. A future
--     close-friends audience MUST be added inside this function - fail-open by
--     omission is impossible. No data change, no behavior change.
-- ============================================================================

-- ============================================================================
-- 1. FIX 3: SECURITY DEFINER RLS HELPERS - AUTHENTICATED + SERVICE ONLY
-- ============================================================================
-- These run inside policy expressions as the querying role (`authenticated`),
-- so `authenticated` MUST retain EXECUTE. PUBLIC (and therefore `anon`) is
-- revoked - it previously enabled membership/admin/story-visibility probing.

REVOKE ALL ON FUNCTION public.is_chat_member(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_chat_member(UUID) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.is_chat_admin(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_chat_admin(UUID) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.is_chat_owner(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_chat_owner(UUID) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.is_active_admin(public.admin_role) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_active_admin(public.admin_role) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.can_view_story(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.can_view_story(UUID) TO authenticated, service_role;

-- ============================================================================
-- 2. FIX 9a: POLL VOTE COUNTS - MEMBERSHIP REQUIRED
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_poll_option_vote_count(p_option_id UUID)
RETURNS INTEGER
LANGUAGE plpgsql
STABLE
SET search_path = public
AS $$
DECLARE
    v_count INTEGER;
    v_chat_id UUID;
BEGIN
    -- Resolve the chat that contains this poll option; non-members get 0.
    SELECT m.chat_id INTO v_chat_id
    FROM public.poll_options po
    JOIN public.polls p ON p.id = po.poll_id
    JOIN public.messages m ON m.id = p.message_id
    WHERE po.id = p_option_id;

    IF v_chat_id IS NULL OR NOT public.is_chat_member(v_chat_id) THEN
        RETURN 0;
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM public.poll_votes
    WHERE option_id = p_option_id;

    RETURN v_count;
END;
$$;

REVOKE ALL ON FUNCTION public.get_poll_option_vote_count(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_poll_option_vote_count(UUID) TO authenticated, service_role;

-- ============================================================================
-- 3. FIX 9b: PREMIUM ENTITLEMENTS - SELF-SCOPED
-- ============================================================================

CREATE OR REPLACE FUNCTION public.user_has_premium_feature(p_user_id UUID, p_feature public.premium_feature)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
SET search_path = public
AS $$
BEGIN
    -- Only the caller's own entitlement status is visible.
    IF p_user_id IS DISTINCT FROM public.current_user_id() THEN
        RETURN FALSE;
    END IF;

    RETURN EXISTS (
        SELECT 1 FROM public.premium_entitlements
        WHERE user_id = p_user_id
          AND feature = p_feature
          AND is_active = TRUE
          AND (expires_at IS NULL OR expires_at > NOW())
    );
END;
$$;

REVOKE ALL ON FUNCTION public.user_has_premium_feature(UUID, public.premium_feature) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.user_has_premium_feature(UUID, public.premium_feature) TO authenticated, service_role;

-- ============================================================================
-- 4. FIX 9c: RESTRICTIONS - SELF-SCOPED
-- ============================================================================

CREATE OR REPLACE FUNCTION public.user_has_active_restriction(p_user_id UUID, p_type public.restriction_type)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
SET search_path = public
AS $$
BEGIN
    -- Only the caller's own restriction status is visible.
    IF p_user_id IS DISTINCT FROM public.current_user_id() THEN
        RETURN FALSE;
    END IF;

    RETURN EXISTS (
        SELECT 1 FROM public.user_restrictions
        WHERE user_id = p_user_id
          AND restriction_type = p_type
          AND is_active = TRUE
          AND (expires_at IS NULL OR expires_at > NOW())
    );
END;
$$;

REVOKE ALL ON FUNCTION public.user_has_active_restriction(UUID, public.restriction_type) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.user_has_active_restriction(UUID, public.restriction_type) TO authenticated, service_role;

-- ============================================================================
-- 5. FIX 9d: MESSAGE DELETION STATUS - SELF-SCOPED + MEMBERSHIP
-- ============================================================================
-- SECURITY DEFINER is required: the membership check reads messages.chat_id,
-- but messages SELECT RLS hides a message from the caller once the caller has
-- deleted it for themselves - which would make the chat lookup fail and
-- wrongly report FALSE. As the definer we resolve the true chat_id and then
-- apply the explicit self-scope + is_chat_member() authorization.

CREATE OR REPLACE FUNCTION public.message_is_deleted_for_user(p_message_id UUID, p_user_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_chat_id UUID;
BEGIN
    -- Callers may only query their own deletion view of a message.
    IF p_user_id IS DISTINCT FROM public.current_user_id() THEN
        RETURN FALSE;
    END IF;

    SELECT chat_id INTO v_chat_id
    FROM public.messages
    WHERE id = p_message_id;

    IF v_chat_id IS NULL OR NOT public.is_chat_member(v_chat_id) THEN
        RETURN FALSE;
    END IF;

    RETURN EXISTS (
        SELECT 1 FROM public.message_deletions
        WHERE message_id = p_message_id
          AND (user_id = p_user_id OR is_for_everyone = TRUE)
    ) OR EXISTS (
        SELECT 1 FROM public.messages
        WHERE id = p_message_id AND deleted_at IS NOT NULL
    );
END;
$$;

REVOKE ALL ON FUNCTION public.message_is_deleted_for_user(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.message_is_deleted_for_user(UUID, UUID) TO authenticated, service_role;

-- ============================================================================
-- 6. FIX 9e: CHAT SUMMARY / UNREAD COUNT - AUTHENTICATED ONLY
-- ============================================================================
-- Both already resolve the caller internally (the p_user_id argument is
-- ignored); they simply must not be PUBLIC.

REVOKE ALL ON FUNCTION public.get_user_chat_summary(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_user_chat_summary(UUID) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.get_user_unread_count(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_user_unread_count(UUID) TO authenticated, service_role;

-- ============================================================================
-- 7. FIX 9f: MAINTENANCE / INTERNAL FUNCTIONS - SERVICE_ROLE ONLY
-- ============================================================================
-- Scheduled jobs and the auth backend run as service_role; these must never be
-- invocable by clients (token-hash probing / poll manipulation etc.).

REVOKE ALL ON FUNCTION public.cleanup_expired_email_verifications() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.cleanup_expired_email_verifications() TO service_role;

REVOKE ALL ON FUNCTION public.expire_user_restrictions() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.expire_user_restrictions() TO service_role;

REVOKE ALL ON FUNCTION public.auto_close_expired_polls() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.auto_close_expired_polls() TO service_role;

REVOKE ALL ON FUNCTION public.detect_refresh_token_reuse(TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.detect_refresh_token_reuse(TEXT, UUID) TO service_role;

-- ============================================================================
-- 8. FIX 8: STORY PRIVACY - EXPLICIT FAIL-CLOSED close_friends / nobody
-- ============================================================================
-- Behavior is UNCHANGED (close_friends / nobody were already unreachable for
-- non-owners). The predicate now names them explicitly so a future audience
-- feature cannot silently open up by omission.

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
              s.privacy = 'everyone'
              OR (s.privacy = 'custom' AND EXISTS (
                      SELECT 1 FROM public.story_custom_audience sca
                      WHERE sca.story_id = s.id
                        AND sca.user_id = public.current_user_id()
                  ))
              -- close_friends / nobody FAIL CLOSED: no audience rows exist, so
              -- only the owner (via the stories policy user_id branch) can view.
              -- Any future close-friends audience MUST be added here.
              OR (s.privacy = 'close_friends' AND FALSE)
              OR (s.privacy = 'nobody' AND FALSE)
          )
    );
$$;

-- ============================================================================
-- 9. FIX 3 COMPLETION: NO PUBLIC EXECUTE REMAINS ON ANY SECURITY DEFINER
--    FUNCTION
-- ============================================================================
-- The earlier SECURITY DEFINER RPCs in 015/016/017 were only GRANTed to
-- `authenticated` - they were NEVER revoked from PUBLIC, so `anon` retained
-- EXECUTE (verifiable via proacl: `=X/postgres`). This block revokes PUBLIC
-- from every remaining SECURITY DEFINER function. Client-facing RPCs keep
-- EXECUTE for authenticated + service_role; trigger functions become
-- postgres-owner-only (triggers fire regardless of EXECUTE grants, so this is
-- purely defense-in-depth).

REVOKE ALL ON FUNCTION public.add_chat_member(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.add_chat_member(UUID, UUID) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.remove_chat_member(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.remove_chat_member(UUID, UUID) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.set_chat_member_role(UUID, UUID, public.chat_member_role) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.set_chat_member_role(UUID, UUID, public.chat_member_role) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.get_or_create_direct_chat(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_or_create_direct_chat(UUID, UUID) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.revoke_own_session(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.revoke_own_session(UUID) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.verify_email_code(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.verify_email_code(UUID, TEXT) TO authenticated, service_role;

-- Trigger functions are never directly invokable; remove the default PUBLIC
-- EXECUTE so they exist purely as trigger callbacks.
REVOKE ALL ON FUNCTION public.assign_chat_seq() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.increment_story_view_count() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.increment_unread_on_message() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.update_chat_on_member_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.update_chat_on_message() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.update_story_reaction_count() FROM PUBLIC;

-- ============================================================================
-- 10. RLS COMPLETENESS: chat_sequences IS INTERNAL - FAIL CLOSED
-- ============================================================================
-- chat_sequences is a monotonically increasing per-chat counter written ONLY
-- by the SECURITY DEFINER assign_chat_seq() trigger. It is not user-facing and
-- clients hold no grants on it, but it must still be RLS-enabled (with no
-- policies = fail closed) so no future accidental grant can expose it.

ALTER TABLE public.chat_sequences ENABLE ROW LEVEL SECURITY;
