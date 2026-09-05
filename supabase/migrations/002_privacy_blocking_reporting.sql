-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 002: PRIVACY, BLOCKING, REPORTING
-- ============================================================================
-- This migration creates the social/relationship tables:
-- privacy_settings, blocked_users, user_reports, message_reports, user_restrictions
--
-- NOTE: The contacts / phonebook-sync / phonebook-driven-auto-block feature has
-- been removed from Creanger product scope. There is no `contacts` table, and
-- privacy visibility tiers that referenced "contacts" have been collapsed to
-- 'everyone' / 'nobody' (see privacy_visibility / privacy_groups below).
-- ============================================================================

-- ============================================================================
-- ENUM TYPES
-- ============================================================================

CREATE TYPE privacy_visibility AS ENUM (
    'everyone',
    'nobody'
);

CREATE TYPE privacy_groups AS ENUM (
    'everyone',
    'nobody'
);

CREATE TYPE report_reason AS ENUM (
    'spam',
    'harassment',
    'hate_speech',
    'violence',
    'illegal_content',
    'impersonation',
    'scam',
    'copyright',
    'self_harm',
    'other'
);

CREATE TYPE report_status AS ENUM (
    'pending',
    'under_review',
    'resolved_action_taken',
    'resolved_no_action',
    'dismissed'
);

CREATE TYPE restriction_type AS ENUM (
    'mute',
    'temporary_restriction',
    'ban',
    'read_only',
    'media_restriction',
    'message_limit'
);

-- ============================================================================
-- PRIVACY_SETTINGS TABLE (Per-user privacy controls)
-- ============================================================================

CREATE TABLE privacy_settings (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    -- Visibility settings
    last_seen_visibility privacy_visibility NOT NULL DEFAULT 'everyone',
    profile_photo_visibility privacy_visibility NOT NULL DEFAULT 'everyone',
    email_visibility privacy_visibility NOT NULL DEFAULT 'nobody',
    phone_visibility privacy_visibility NOT NULL DEFAULT 'everyone',
    bio_visibility privacy_visibility NOT NULL DEFAULT 'everyone',
    -- Interaction settings
    read_receipts_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    groups_privacy privacy_groups NOT NULL DEFAULT 'everyone',
    forwards_privacy privacy_visibility NOT NULL DEFAULT 'everyone',
    voice_messages_privacy privacy_visibility NOT NULL DEFAULT 'everyone',
    -- Advanced settings
    allow_adding_to_groups BOOLEAN NOT NULL DEFAULT TRUE,
    show_link_previews BOOLEAN NOT NULL DEFAULT TRUE,
    -- Metadata
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE privacy_settings IS 'Per-user privacy configuration';
COMMENT ON COLUMN privacy_settings.last_seen_visibility IS 'Who can see last seen timestamp';
COMMENT ON COLUMN privacy_settings.read_receipts_enabled IS 'Whether to send read receipts';
COMMENT ON COLUMN privacy_settings.groups_privacy IS 'Who can add user to groups/channels';

CREATE TRIGGER update_privacy_settings_updated_at
    BEFORE UPDATE ON privacy_settings
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- BLOCKED_USERS TABLE (Blocking relationships)
-- ============================================================================

CREATE TABLE blocked_users (
    blocker_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT blocked_users_no_self_block CHECK (blocker_id <> blocked_id)
);

COMMENT ON TABLE blocked_users IS 'User blocking relationships';
COMMENT ON COLUMN blocked_users.blocker_id IS 'User who initiated the block';
COMMENT ON COLUMN blocked_users.blocked_id IS 'User who is blocked';

CREATE INDEX idx_blocked_users_blocked_id ON blocked_users(blocked_id);
CREATE INDEX idx_blocked_users_created_at ON blocked_users(created_at);

-- ============================================================================
-- USER_REPORTS TABLE (User-level reports)
-- ============================================================================

CREATE TABLE user_reports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reported_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason report_reason NOT NULL,
    description TEXT,
    status report_status NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    resolved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    resolution_notes TEXT,
    CONSTRAINT user_reports_no_self_report CHECK (reporter_id <> reported_user_id),
    CONSTRAINT user_reports_description_length CHECK (description IS NULL OR char_length(description) <= 5000)
);

COMMENT ON TABLE user_reports IS 'User-to-user abuse reports';

CREATE INDEX idx_user_reports_reporter_id ON user_reports(reporter_id);
CREATE INDEX idx_user_reports_reported_user_id ON user_reports(reported_user_id);
CREATE INDEX idx_user_reports_status ON user_reports(status) WHERE status IN ('pending', 'under_review');
CREATE INDEX idx_user_reports_created_at ON user_reports(created_at);

-- ============================================================================
-- MESSAGE_REPORTS TABLE (Message-level reports)
-- ============================================================================

CREATE TABLE message_reports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message_id UUID NOT NULL, -- FK to messages table (added later)
    chat_id UUID NOT NULL,    -- FK to chats table (added later)
    reason report_reason NOT NULL,
    description TEXT,
    status report_status NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    resolved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    resolution_notes TEXT,
    CONSTRAINT message_reports_description_length CHECK (description IS NULL OR char_length(description) <= 5000)
);

COMMENT ON TABLE message_reports IS 'Message-level abuse reports';

CREATE INDEX idx_message_reports_reporter_id ON message_reports(reporter_id);
CREATE INDEX idx_message_reports_message_id ON message_reports(message_id);
CREATE INDEX idx_message_reports_chat_id ON message_reports(chat_id);
CREATE INDEX idx_message_reports_status ON message_reports(status) WHERE status IN ('pending', 'under_review');
CREATE INDEX idx_message_reports_created_at ON message_reports(created_at);

-- ============================================================================
-- USER_RESTRICTIONS TABLE (Moderation actions)
-- ============================================================================

CREATE TABLE user_restrictions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    restriction_type restriction_type NOT NULL,
    reason TEXT,
    metadata JSONB DEFAULT '{}',
    issued_by UUID REFERENCES users(id) ON DELETE SET NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    revoked_at TIMESTAMPTZ,
    revoked_by UUID REFERENCES users(id) ON DELETE SET NULL,
    revocation_reason TEXT,
    CONSTRAINT user_restrictions_future_expiry CHECK (expires_at IS NULL OR expires_at > issued_at),
    CONSTRAINT user_restrictions_reason_length CHECK (reason IS NULL OR char_length(reason) <= 2000)
);

COMMENT ON TABLE user_restrictions IS 'Moderation restrictions applied to users';
COMMENT ON COLUMN user_restrictions.restriction_type IS 'Type of restriction (mute, temp_restriction, ban, read_only, etc.)';
COMMENT ON COLUMN user_restrictions.metadata IS 'Flexible metadata (e.g., chat_ids for chat-specific mutes)';
COMMENT ON COLUMN user_restrictions.expires_at IS 'Expiration for temporary restrictions';
COMMENT ON COLUMN user_restrictions.is_active IS 'Whether restriction is currently active';

CREATE INDEX idx_user_restrictions_user_id ON user_restrictions(user_id) WHERE is_active = TRUE;
CREATE INDEX idx_user_restrictions_type ON user_restrictions(restriction_type) WHERE is_active = TRUE;
CREATE INDEX idx_user_restrictions_expires_at ON user_restrictions(expires_at) WHERE is_active = TRUE AND expires_at IS NOT NULL;
CREATE INDEX idx_user_restrictions_issued_by ON user_restrictions(issued_by);
CREATE INDEX idx_user_restrictions_issued_at ON user_restrictions(issued_at);

-- Function to check if user has active restriction of type
CREATE OR REPLACE FUNCTION user_has_active_restriction(p_user_id UUID, p_type restriction_type)
RETURNS BOOLEAN AS $$
DECLARE
    v_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_count
    FROM user_restrictions
    WHERE user_id = p_user_id
      AND restriction_type = p_type
      AND is_active = TRUE
      AND (expires_at IS NULL OR expires_at > NOW());
    RETURN v_count > 0;
END;
$$ LANGUAGE plpgsql STABLE;

-- Function to auto-expire restrictions (run via pg_cron or scheduled job)
CREATE OR REPLACE FUNCTION expire_user_restrictions()
RETURNS INTEGER AS $$
DECLARE
    updated_count INTEGER;
BEGIN
    UPDATE user_restrictions
    SET is_active = FALSE,
        revoked_at = NOW(),
        revocation_reason = 'expired'
    WHERE is_active = TRUE
      AND expires_at IS NOT NULL
      AND expires_at <= NOW();
    GET DIAGNOSTICS updated_count = ROW_COUNT;
    RETURN updated_count;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- RLS POLICIES
-- ============================================================================

ALTER TABLE privacy_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE blocked_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_restrictions ENABLE ROW LEVEL SECURITY;

-- Privacy Settings: Users can read all (for visibility checks), update own
CREATE POLICY privacy_settings_select_all ON privacy_settings
    FOR SELECT USING (TRUE);
CREATE POLICY privacy_settings_update_own ON privacy_settings
    FOR UPDATE USING (user_id = current_user_id());
CREATE POLICY privacy_settings_insert_own ON privacy_settings
    FOR INSERT WITH CHECK (user_id = current_user_id());

-- Blocked Users: Users can read/manage their own blocks
CREATE POLICY blocked_users_select_own ON blocked_users
    FOR SELECT USING (blocker_id = current_user_id());
CREATE POLICY blocked_users_insert_own ON blocked_users
    FOR INSERT WITH CHECK (blocker_id = current_user_id());
CREATE POLICY blocked_users_delete_own ON blocked_users
    FOR DELETE USING (blocker_id = current_user_id());

-- User Reports: Reporters can read own reports; moderators can read all
CREATE POLICY user_reports_select_own ON user_reports
    FOR SELECT USING (reporter_id = current_user_id());
CREATE POLICY user_reports_insert_own ON user_reports
    FOR INSERT WITH CHECK (reporter_id = current_user_id());
-- Moderators need service role or specific policy (handled via service_role)

-- Message Reports: Reporters can read own reports
CREATE POLICY message_reports_select_own ON message_reports
    FOR SELECT USING (reporter_id = current_user_id());
CREATE POLICY message_reports_insert_own ON message_reports
    FOR INSERT WITH CHECK (reporter_id = current_user_id());

-- User Restrictions: Users can read own active restrictions; moderators manage all
CREATE POLICY user_restrictions_select_own ON user_restrictions
    FOR SELECT USING (user_id = current_user_id());
-- Management by service role only

-- ============================================================================
-- GRANTS
-- ============================================================================

GRANT SELECT, UPDATE ON privacy_settings TO authenticated;
GRANT SELECT, INSERT, DELETE ON blocked_users TO authenticated;
GRANT SELECT, INSERT ON user_reports TO authenticated;
GRANT SELECT, INSERT ON message_reports TO authenticated;
GRANT SELECT ON user_restrictions TO authenticated;

GRANT ALL ON privacy_settings TO service_role;
GRANT ALL ON blocked_users TO service_role;
GRANT ALL ON user_reports TO service_role;
GRANT ALL ON message_reports TO service_role;
GRANT ALL ON user_restrictions TO service_role;