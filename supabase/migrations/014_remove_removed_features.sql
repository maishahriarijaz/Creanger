-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 014: REMOVE UNUSED FEATURES
-- ============================================================================
-- REMOVED per product scope:
--   - Calls / VoIP / call signaling
--   - Telegram Stars / Payments / Gifts (no monetization) -- never had tables
--   - Bots / WebApps -- never had tables
--   - Secret Chats / Telegram E2E (not part of Creanger) -- never had tables
--   - Chat Folders -- never had tables
-- KEEP: Premium but model it as admin-request/approval based.
--
-- NOTE ON CONTACTS / PHONEBOOK SYNC / PHONEBOOK AUTO-BLOCK:
-- These were removed by editing the source migrations directly rather than
-- dropping objects here, since this schema has not shipped to production yet
-- (no live data to migrate away from). Specifically:
--   - `contacts` table, its enum usages, indexes, RLS policies, and grants
--     were removed from 002_privacy_blocking_reporting.sql (formerly
--     002_contacts_privacy_blocking_reporting.sql).
--   - privacy_visibility / privacy_groups enums no longer have a 'contacts'
--     tier; defaults changed from 'contacts' to 'everyone'.
--   - story_privacy enum no longer has a 'contacts' tier; the stories RLS
--     policy no longer queries the contacts table (007_stories.sql).
--   - message_type enum no longer has a 'contact' value (004_messages.sql).
--   - notification_type enum no longer has a 'contact_request' value
--     (008_notifications.sql).
--   - system_config no longer seeds 'contact.max_count' (010_admin_audit.sql).
--   - The duplicate/stale idx_contacts_created_at index in
--     013_indexes_and_triggers.sql was removed.
-- If this schema is ever applied to a database that already has these
-- objects (i.e. migrations 001-013 already ran in an earlier form), use
-- ALTER TABLE ... DROP COLUMN / DROP TABLE and the enum-rebuild pattern
-- (rename type, create new type without the value, alter columns, drop old
-- type) instead of re-running the edited source files.
-- ============================================================================

-- ============================================================================
-- 1. DROP CALLS / VOIP (whole feature)
-- ============================================================================
-- NOTE: calls / call_participants may or may not exist depending on whether
-- an older schema version created them. DROP TABLE IF EXISTS ... CASCADE below
-- drops any dependent triggers, so no separate DROP TRIGGER statements are
-- needed (DROP TRIGGER IF EXISTS ON a missing table is an error).

DROP INDEX IF EXISTS idx_calls_chat_id;
DROP INDEX IF EXISTS idx_calls_initiator_id;
DROP INDEX IF EXISTS idx_calls_status;
DROP INDEX IF EXISTS idx_calls_created_at;
DROP INDEX IF EXISTS idx_call_participants_call_id;
DROP INDEX IF EXISTS idx_call_participants_user_id;
DROP INDEX IF EXISTS idx_call_participants_status;

DROP TABLE IF EXISTS call_participants CASCADE;
DROP TABLE IF EXISTS calls CASCADE;

DROP TYPE IF EXISTS call_type CASCADE;
DROP TYPE IF EXISTS call_status CASCADE;
DROP TYPE IF EXISTS call_participant_role CASCADE;
DROP TYPE IF EXISTS call_participant_status CASCADE;

-- ============================================================================
-- 2. ADD PREMIUM ADMIN-REQUEST / APPROVAL SYSTEM (replaces payment-driven Premium)
-- ============================================================================

CREATE TYPE premium_request_status AS ENUM (
    'pending',
    'under_review',
    'approved',
    'rejected',
    'revoked',
    'expired'
);

CREATE TYPE premium_feature AS ENUM (
    'no_ads',
    'higher_upload_limit',
    'more_channels',
    'custom_emoji',
    'profile_badge',
    'voice_to_text',
    'advanced_chat_management',
    'profile_customization',
    'faster_downloads'
);

CREATE TABLE premium_requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status premium_request_status NOT NULL DEFAULT 'pending',
    requested_features premium_feature[] NOT NULL DEFAULT '{}',
    reason TEXT,
    admin_notes TEXT,
    reviewed_by UUID REFERENCES admin_users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT premium_requests_reason_length CHECK (reason IS NULL OR char_length(reason) <= 2000),
    CONSTRAINT premium_requests_admin_notes_length CHECK (admin_notes IS NULL OR char_length(admin_notes) <= 2000)
);

CREATE TABLE premium_entitlements (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feature premium_feature NOT NULL,
    granted_by_request_id UUID REFERENCES premium_requests(id) ON DELETE SET NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT premium_entitlements_user_feature_active_unique UNIQUE (user_id, feature)
);

CREATE TABLE premium_plans (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL,
    description TEXT,
    features premium_feature[] NOT NULL DEFAULT '{}',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    max_requests INTEGER,
    auto_approve BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT premium_plans_name_not_empty CHECK (name <> '')
);

-- Indexes
CREATE INDEX idx_premium_requests_user_id ON premium_requests(user_id);
CREATE INDEX idx_premium_requests_status ON premium_requests(status) WHERE status IN ('pending', 'under_review');
CREATE INDEX idx_premium_requests_created_at ON premium_requests(created_at DESC);
CREATE INDEX idx_premium_entitlements_user_id ON premium_entitlements(user_id) WHERE is_active = TRUE;
CREATE INDEX idx_premium_entitlements_feature ON premium_entitlements(feature) WHERE is_active = TRUE;
CREATE INDEX idx_premium_entitlements_expires_at ON premium_entitlements(expires_at) WHERE is_active = TRUE AND expires_at IS NOT NULL;

CREATE TRIGGER update_premium_requests_updated_at
    BEFORE UPDATE ON premium_requests
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_premium_entitlements_updated_at
    BEFORE UPDATE ON premium_entitlements
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_premium_plans_updated_at
    BEFORE UPDATE ON premium_plans
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Helper function: check if user has active premium feature
CREATE OR REPLACE FUNCTION user_has_premium_feature(p_user_id UUID, p_feature premium_feature)
RETURNS BOOLEAN AS $$
DECLARE
    v_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_count
    FROM premium_entitlements
    WHERE user_id = p_user_id
      AND feature = p_feature
      AND is_active = TRUE
      AND (expires_at IS NULL OR expires_at > NOW());
    RETURN v_count > 0;
END;
$$ LANGUAGE plpgsql STABLE;

-- Seed default premium plan
INSERT INTO premium_plans (name, description, features, is_active, max_requests, auto_approve) VALUES
    ('Creanger Premium', 'Premium features granted via admin approval', 
     ARRAY['no_ads', 'higher_upload_limit', 'more_channels', 'custom_emoji', 'profile_badge']::premium_feature[],
     TRUE, NULL, FALSE);

-- ============================================================================
-- 3. RLS POLICIES FOR PREMIUM TABLES
-- ============================================================================

ALTER TABLE premium_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE premium_entitlements ENABLE ROW LEVEL SECURITY;
ALTER TABLE premium_plans ENABLE ROW LEVEL SECURITY;

-- Premium requests: users can read/manage own; admins read all
CREATE POLICY premium_requests_select_own ON premium_requests
    FOR SELECT USING (
        user_id = current_user_id()
        OR current_user_id() IN (
            SELECT user_id FROM admin_users WHERE is_active = TRUE
        )
    );
CREATE POLICY premium_requests_insert_own ON premium_requests
    FOR INSERT WITH CHECK (user_id = current_user_id());
CREATE POLICY premium_requests_update_own ON premium_requests
    FOR UPDATE USING (user_id = current_user_id());
-- Admins can update (approve/reject) via service role

-- Premium entitlements: users read own; admins manage
CREATE POLICY premium_entitlements_select_own ON premium_entitlements
    FOR SELECT USING (
        user_id = current_user_id()
        OR current_user_id() IN (
            SELECT user_id FROM admin_users WHERE is_active = TRUE
        )
    );

-- Premium plans: readable by all authenticated users
CREATE POLICY premium_plans_select_all ON premium_plans
    FOR SELECT USING (is_active = TRUE);
-- Only admins can manage plans via service role

-- ============================================================================
-- 4. GRANTS FOR PREMIUM
-- ============================================================================

GRANT SELECT, INSERT, UPDATE ON premium_requests TO authenticated;
GRANT SELECT ON premium_entitlements TO authenticated;
GRANT SELECT ON premium_plans TO authenticated;

GRANT ALL ON premium_requests TO service_role;
GRANT ALL ON premium_entitlements TO service_role;
GRANT ALL ON premium_plans TO service_role;