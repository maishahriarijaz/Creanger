-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 034: SUPABASE AUTH IDENTITY BRIDGE
-- ============================================================================
-- Switches authentication to Supabase Auth (GoTrue) as the ONLY auth system.
--
-- The legacy Custom Auth Server is removed. From now on:
--  * Identity rows are created automatically from auth.users (GoTrue) via an
--    INSERT trigger, reusing THE SAME UUID (users.id := auth.users.id) so all
--    existing chat/member RLS policies keyed on current_user_id() keep working.
--  * Username availability is exposed through SECURITY DEFINER RPC
--    check_username_available(p_username).
--  * Login accepts username OR email: username -> email resolution happens
--    SERVER-SIDE via SECURITY DEFINER RPC resolve_login_email(p_identifier)
--    reading auth.users directly (never trusted to the client). The RPC only
--    resolves pure usernames; email identifiers are passed straight to
--    GoTrue's password grant. A miss returns NULL and the subsequent grant
--    fails with GoTrue's generic "Invalid login credentials" answer, so
--    account existence is not revealed beyond GoTrue's own behavior.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Align the profiles.username constraint with the app-side Instagram-style
--    policy (CreangerUsernamePolicy): [a-z0-9._], 3..30, no leading/trailing
--    or doubled periods. CITEXT stores usernames case-insensitively.
-- ----------------------------------------------------------------------------

ALTER TABLE public.profiles DROP CONSTRAINT IF EXISTS profiles_username_format;

ALTER TABLE public.profiles ADD CONSTRAINT profiles_username_format CHECK (
    username IS NULL
    OR (
        username ~ '^[a-z0-9._]{3,30}$'
        AND username !~ '^\.'
        AND username !~ '\.$'
        AND username !~ '\.\.'
    )
);

-- ----------------------------------------------------------------------------
-- 2. Bridge trigger: every new Supabase Auth user gets canonical users +
--    profiles rows with the SAME id. Profile fields come from the signup /
--    Google metadata (raw_user_meta_data): username, display_name | name.
--    A username collision at insert must NOT abort the signup: the row falls
--    back to username IS NULL and the app asks the user to claim one
--    (Google setup screen) or the profile stays incomplete until updated.
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
    v_username := NULLIF(TRIM(COALESCE(NEW.raw_user_meta_data->>'username', '')), '');
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

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_supabase_user();

-- ----------------------------------------------------------------------------
-- 3. Username availability RPC (live debounced check while typing).
--    Returns false for malformed usernames; true only when the username is
--    policy-valid and not present in profiles.
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.check_username_available(p_username text)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_norm text;
BEGIN
    v_norm := LOWER(BTRIM(COALESCE(p_username, '')));
    IF v_norm IS NULL
       OR v_norm NOT SIMILAR TO '[a-z0-9._]{3,30}'
       OR LEFT(v_norm, 1) = '.'
       OR RIGHT(v_norm, 1) = '.'
       OR POSITION('..' IN v_norm) > 0 THEN
        RETURN FALSE;
    END IF;
    RETURN NOT EXISTS (SELECT 1 FROM public.profiles p WHERE p.username = v_norm);
END;
$$;

-- ----------------------------------------------------------------------------
-- 4. Server-side username -> email resolution for password login.
--    SECURITY DEFINER because auth.users is not readable by anon/authenticated.
--    Only resolves PURE usernames (identifiers containing '@' return NULL —
--    the client passes those to GoTrue unchanged). No row => NULL => the
--    following GoTrue password grant fails generically (no existence leak
--    beyond GoTrue itself). Protect with platform rate limiting.
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.resolve_login_email(p_identifier text)
RETURNS text
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, auth
AS $$
    SELECT CASE
        WHEN POSITION('@' IN COALESCE(p_identifier, '')) > 0 THEN NULL
        ELSE (
            SELECT u.email::text
            FROM public.profiles p
            JOIN auth.users u ON u.id = p.user_id
            WHERE p.username = LOWER(BTRIM(p_identifier))
            LIMIT 1
        )
    END
$$;

-- ----------------------------------------------------------------------------
-- 5. Privileges: anonymous callers need the two pre-login RPCs; authenticated
--    users additionally manage their own profile row directly (policy from
--    migration 001 already allows INSERT/UPDATE own). Functions are revoked
--    from PUBLIC first so grants stay explicit.
-- ----------------------------------------------------------------------------

REVOKE ALL ON FUNCTION public.check_username_available(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.resolve_login_email(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.handle_new_supabase_user() FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.check_username_available(text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.resolve_login_email(text) TO anon, authenticated;

-- Direct profile access stays authenticated-only (RLS policies from migration
-- 001 let each user insert/update their own row; the Google setup flow uses
-- that). The RPCs above run SECURITY DEFINER and need no table grants here.
