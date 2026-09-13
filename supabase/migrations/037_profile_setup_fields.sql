-- ============================================================================
-- 037_profile_setup_fields.sql
--
-- Canonical Google-only account setup (Create Your Account screen) needs two
-- fields that did not exist on profiles:
--   * display_name        - the required Display Name from setup
--   * terms_accepted_at   - when the user checked the Creanger Agreement box
--
-- No RLS change: profiles_update_own / profiles_insert_own are row-based and
-- column-agnostic, so the existing own-row policies cover the new columns.
-- No realtime change: profiles is intentionally excluded from the realtime
-- publication (020), columns do not affect that.
-- ============================================================================

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS display_name TEXT,
    ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMPTZ;

COMMENT ON COLUMN public.profiles.display_name IS
    'Required display name chosen on the Google account setup screen.';
COMMENT ON COLUMN public.profiles.terms_accepted_at IS
    'When the user accepted the Creanger Agreement (setup checkbox). NULL = not accepted.';

-- Display names are free-form but bounded, matching first_name conventions.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'profiles_display_name_length'
    ) THEN
        ALTER TABLE public.profiles ADD CONSTRAINT profiles_display_name_length CHECK (
            display_name IS NULL OR (char_length(display_name) BETWEEN 1 AND 64)
        );
    END IF;
END
$$;
