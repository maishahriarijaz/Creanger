-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 035: SIGNUP USERNAME METADATA FALLBACK
-- ============================================================================
-- Hardens the auth.users bridge trigger (034) against malformed username
-- metadata. GoTrue treats signup `data` as opaque, so a policy-violating
-- username (e.g. too short, bad characters) used to raise a CHECK violation
-- inside the AFTER INSERT trigger and abort the whole signup with GoTrue's
-- generic 500 unexpected_failure.
--
-- From now on: usernames that do not satisfy public.profiles' format
-- constraint are dropped to NULL at insert time (same fallback the collision
-- path already uses). The account is created normally with an UNCLAIMED
-- username; the app then routes the user through the claim/setup flow
-- (Google setup screen / register validation), which enforces the policy
-- client-side before any write. Signup can no longer be aborted by metadata.
-- ============================================================================

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
    -- Drop malformed usernames instead of failing the insert: only values
    -- satisfying profiles' CHECK may reach the table. CITEXT lowercases.
    IF v_username IS NOT NULL AND (
        v_username !~ '^[a-z0-9._]{3,30}$'
        OR LEFT(v_username, 1) = '.'
        OR RIGHT(v_username, 1) = '.'
        OR POSITION('..' IN v_username) > 0
    ) THEN
        v_username := NULL;
    END IF;

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
