-- ============================================================================
-- 039_profile_birthday.sql
--
-- The self Profile screen shows bio + birthday from the profiles table.
-- bio already exists (001); birthday is new:
--   * birthday - the user's birth date (DATE, NULL = not set)
--
-- No RLS change: profiles_update_own / profiles_insert_own are row-based and
-- column-agnostic, so the existing own-row policies cover the new column.
-- No realtime change: profiles is intentionally excluded from the realtime
-- publication (020), columns do not affect that.
-- ============================================================================

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS birthday DATE;

COMMENT ON COLUMN public.profiles.birthday IS
    'User birth date shown on the self Profile screen. NULL = not set.';
