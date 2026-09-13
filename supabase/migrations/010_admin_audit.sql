-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 010: ADMIN, AUDIT, MODERATION, FEATURE FLAGS
-- ============================================================================
-- This migration creates the admin, audit, moderation, and feature flag tables:
-- admin_users, admin_permissions, admin_actions, audit_logs,
-- moderation_actions, feature_flags, system_config
-- ============================================================================

-- ============================================================================
-- ENUM TYPES
-- ============================================================================

CREATE TYPE admin_role AS ENUM (
    'super_admin',
    'moderator',
    'support',
    'readonly'
);

CREATE TYPE audit_action AS ENUM (
    'login',
    'logout',
    'password_change',
    'email_change',
    'account_created',
    'account_deleted',
    'account_suspended',
    'account_reinstated',
    'session_revoked',
    'device_revoked',
    'admin_action',
    'moderation_action',
    'content_removed',
    'user_reported',
    'security_event'
);

CREATE TYPE moderation_action_type AS ENUM (
    'warn',
    'mute_user',
    'temporary_ban',
    'permanent_ban',
    'remove_content',
    'restrict_sending',
    'restrict_media',
    'restrict_adding_to_groups',
    'unban',
    'unmute',
    'remove_restriction'
);

-- ============================================================================
-- ADMIN_USERS TABLE (Admin accounts)
-- ============================================================================

CREATE TABLE admin_users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    role admin_role NOT NULL DEFAULT 'moderator',
    assigned_by UUID REFERENCES users(id) ON DELETE SET NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ, -- For temporary admin roles
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT admin_users_future_expiry CHECK (expires_at IS NULL OR expires_at > created_at)
);

COMMENT ON TABLE admin_users IS 'Admin accounts with elevated permissions';
COMMENT ON COLUMN admin_users.role IS 'Admin role determining available permissions';
COMMENT ON COLUMN admin_users.expires_at IS 'When admin access expires (NULL = permanent)';

CREATE INDEX idx_admin_users_user_id ON admin_users(user_id);
CREATE INDEX idx_admin_users_role ON admin_users(role);
CREATE INDEX idx_admin_users_active ON admin_users(is_active) WHERE is_active = TRUE;

CREATE TRIGGER update_admin_users_updated_at
    BEFORE UPDATE ON admin_users
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- ADMIN_PERMISSIONS TABLE (Granular admin permissions)
-- ============================================================================

CREATE TABLE admin_permissions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    role admin_role NOT NULL,
    permission TEXT NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT admin_permissions_unique UNIQUE (role, permission)
);

COMMENT ON TABLE admin_permissions IS 'Granular permissions for each admin role';
COMMENT ON COLUMN admin_permissions.permission IS 'Permission string (e.g., users.read, users.delete)';

CREATE INDEX idx_admin_permissions_role ON admin_permissions(role);

-- Seed default admin permissions
INSERT INTO admin_permissions (role, permission, description) VALUES
    ('super_admin', 'users.read', 'Read user data'),
    ('super_admin', 'users.write', 'Modify user data'),
    ('super_admin', 'users.delete', 'Delete user accounts'),
    ('super_admin', 'users.suspend', 'Suspend user accounts'),
    ('super_admin', 'moderation.read', 'Read moderation data'),
    ('super_admin', 'moderation.write', 'Perform moderation actions'),
    ('super_admin', 'admin.manage', 'Manage admin accounts'),
    ('super_admin', 'system.config', 'Modify system configuration'),
    ('super_admin', 'audit.read', 'Read audit logs'),
    ('moderator', 'users.read', 'Read user data'),
    ('moderator', 'users.write', 'Modify user data'),
    ('moderator', 'users.suspend', 'Suspend user accounts'),
    ('moderator', 'moderation.read', 'Read moderation data'),
    ('moderator', 'moderation.write', 'Perform moderation actions'),
    ('support', 'users.read', 'Read user data'),
    ('support', 'users.write', 'Modify user data'),
    ('support', 'moderation.read', 'Read moderation data'),
    ('readonly', 'users.read', 'Read user data'),
    ('readonly', 'moderation.read', 'Read moderation data'),
    ('readonly', 'audit.read', 'Read audit logs');

-- ============================================================================
-- ADMIN_ACTIONS TABLE (Audit trail for admin actions)
-- ============================================================================

CREATE TABLE admin_actions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    admin_user_id UUID NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    action TEXT NOT NULL,
    target_type TEXT, -- Type of entity affected
    target_id UUID, -- ID of entity affected
    previous_state JSONB, -- State before action
    new_state JSONB, -- State after action
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE admin_actions IS 'Audit trail of all admin actions';
COMMENT ON COLUMN admin_actions.action IS 'Action performed (e.g., user.suspend, content.remove)';
COMMENT ON COLUMN admin_actions.target_type IS 'Type of entity affected';

CREATE INDEX idx_admin_actions_admin_id ON admin_actions(admin_user_id);
CREATE INDEX idx_admin_actions_target ON admin_actions(target_type, target_id);
CREATE INDEX idx_admin_actions_created_at ON admin_actions(created_at DESC);
CREATE INDEX idx_admin_actions_action ON admin_actions(action);

-- ============================================================================
-- AUDIT_LOGS TABLE (Security-sensitive operation logs)
-- ============================================================================

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action audit_action NOT NULL,
    actor_type TEXT NOT NULL DEFAULT 'user', -- user, system, admin
    actor_id UUID, -- ID of actor (if not user_id)
    ip_hash TEXT, -- Hashed IP address
    user_agent TEXT,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT audit_logs_metadata_valid CHECK (jsonb_typeof(metadata) = 'object')
);

COMMENT ON TABLE audit_logs IS 'Security-sensitive operation audit trail';
COMMENT ON COLUMN audit_logs.action IS 'Type of security event';
COMMENT ON COLUMN audit_logs.ip_hash IS 'SHA-256 hash of client IP (not raw IP)';
COMMENT ON COLUMN audit_logs.metadata IS 'Additional context (never store secrets)';

CREATE INDEX idx_audit_logs_user_id ON audit_logs(user_id, created_at DESC);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at DESC);
CREATE INDEX idx_audit_logs_actor ON audit_logs(actor_type, actor_id);

-- ============================================================================
-- MODERATION_ACTIONS TABLE (Content/user moderation actions)
-- ============================================================================

CREATE TABLE moderation_actions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    action_type moderation_action_type NOT NULL,
    target_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    target_content_type TEXT, -- message, story, media, etc.
    target_content_id UUID,
    moderator_id UUID REFERENCES users(id) ON DELETE SET NULL,
    reason TEXT,
    metadata JSONB DEFAULT '{}',
    expires_at TIMESTAMPTZ,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT moderation_actions_reason_length CHECK (reason IS NULL OR char_length(reason) <= 2000),
    CONSTRAINT moderation_actions_future_expiry CHECK (expires_at IS NULL OR expires_at > created_at)
);

COMMENT ON TABLE moderation_actions IS 'Content and user moderation actions';
COMMENT ON COLUMN moderation_actions.action_type IS 'Type of moderation action';
COMMENT ON COLUMN moderation_actions.target_user_id IS 'User being moderated (NULL for content-only)';
COMMENT ON COLUMN moderation_actions.expires_at IS 'When action expires (NULL = permanent)';

CREATE INDEX idx_moderation_actions_target_user ON moderation_actions(target_user_id) WHERE is_active = TRUE;
CREATE INDEX idx_moderation_actions_type ON moderation_actions(action_type);
CREATE INDEX idx_moderation_actions_moderator ON moderation_actions(moderator_id);
CREATE INDEX idx_moderation_actions_expires ON moderation_actions(expires_at) WHERE is_active = TRUE AND expires_at IS NOT NULL;
CREATE INDEX idx_moderation_actions_created_at ON moderation_actions(created_at DESC);

-- ============================================================================
-- FEATURE_FLAGS TABLE (Controlled feature rollout)
-- ============================================================================

CREATE TABLE feature_flags (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    key TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    rollout_percentage INTEGER NOT NULL DEFAULT 0,
    target_user_ids UUID[], -- Specific users to enable for
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT feature_flags_rollout_range CHECK (rollout_percentage >= 0 AND rollout_percentage <= 100),
    CONSTRAINT feature_flags_key_not_empty CHECK (key <> '')
);

COMMENT ON TABLE feature_flags IS 'Feature flags for controlled rollout';
COMMENT ON COLUMN feature_flags.key IS 'Unique feature flag identifier';
COMMENT ON COLUMN feature_flags.enabled IS 'Whether feature is enabled';
COMMENT ON COLUMN feature_flags.rollout_percentage IS 'Percentage of users to enable for (0-100)';

CREATE INDEX idx_feature_flags_enabled ON feature_flags(enabled) WHERE enabled = TRUE;
CREATE INDEX idx_feature_flags_key ON feature_flags(key);

CREATE TRIGGER update_feature_flags_updated_at
    BEFORE UPDATE ON feature_flags
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- SYSTEM_CONFIG TABLE (Minimal system configuration)
-- ============================================================================

CREATE TABLE system_config (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    key TEXT UNIQUE NOT NULL,
    value TEXT NOT NULL,
    description TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT system_config_key_not_empty CHECK (key <> ''),
    CONSTRAINT system_config_value_not_empty CHECK (value <> '')
);

COMMENT ON TABLE system_config IS 'Minimal system configuration (NO SECRETS stored here)';
COMMENT ON COLUMN system_config.key IS 'Configuration key';
COMMENT ON COLUMN system_config.value IS 'Configuration value';

CREATE INDEX idx_system_config_key ON system_config(key);

CREATE TRIGGER update_system_config_updated_at
    BEFORE UPDATE ON system_config
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Seed default system config
INSERT INTO system_config (key, value, description) VALUES
    ('app.name', 'Creanger', 'Application display name'),
    ('app.version', '1.0.0', 'Current application version'),
    ('user.max_devices', '10', 'Maximum devices per user'),
    ('message.max_length', '4096', 'Maximum message length in characters'),
    ('media.max_size_bytes', '104857600', 'Maximum media file size (100MB)'),
    ('story.max_duration_seconds', '60', 'Maximum story duration in seconds'),
    ('story.expiration_hours', '24', 'Story expiration time in hours'),
    ('otp.expiration_minutes', '10', 'OTP code expiration time in minutes'),
    ('otp.max_attempts', '5', 'Maximum OTP verification attempts'),
    ('session.expiration_days', '30', 'Session expiration time in days'),
    ('refresh_token.rotation_hours', '24', 'Refresh token rotation interval'),
    ('search.max_results', '100', 'Maximum search results returned');

-- ============================================================================
-- RLS POLICIES (Admin tables - restricted access)
-- ============================================================================

ALTER TABLE admin_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE moderation_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE feature_flags ENABLE ROW LEVEL SECURITY;
ALTER TABLE system_config ENABLE ROW LEVEL SECURITY;

-- Admin Users: Only super_admins can see/manage; users can see if they are admin
CREATE POLICY admin_users_select_own_or_super ON admin_users
    FOR SELECT USING (
        user_id = current_user_id()
        OR current_user_id() IN (
            SELECT user_id FROM admin_users WHERE role = 'super_admin' AND is_active = TRUE
        )
    );
-- All other operations via service_role only

-- Admin Permissions: Readable by all admins
CREATE POLICY admin_permissions_select_admins ON admin_permissions
    FOR SELECT USING (
        current_user_id() IN (
            SELECT user_id FROM admin_users WHERE is_active = TRUE
        )
    );

-- Admin Actions: Readable by super_admins and the actor
CREATE POLICY admin_actions_select_visible ON admin_actions
    FOR SELECT USING (
        admin_user_id IN (SELECT id FROM admin_users WHERE user_id = current_user_id())
        OR current_user_id() IN (
            SELECT user_id FROM admin_users WHERE role = 'super_admin' AND is_active = TRUE
        )
    );

-- Audit Logs: Readable by super_admins and the user (for own logs)
CREATE POLICY audit_logs_select_visible ON audit_logs
    FOR SELECT USING (
        user_id = current_user_id()
        OR current_user_id() IN (
            SELECT user_id FROM admin_users WHERE role IN ('super_admin', 'readonly') AND is_active = TRUE
        )
    );

-- Moderation Actions: Readable by moderators; visible to affected user
CREATE POLICY moderation_actions_select_visible ON moderation_actions
    FOR SELECT USING (
        target_user_id = current_user_id()
        OR current_user_id() IN (
            SELECT user_id FROM admin_users WHERE is_active = TRUE
        )
    );

-- Feature Flags: Readable by all authenticated users (for client checks)
CREATE POLICY feature_flags_select_all ON feature_flags
    FOR SELECT USING (TRUE);

-- System Config: Readable by all authenticated users
CREATE POLICY system_config_select_all ON system_config
    FOR SELECT USING (TRUE);

-- ============================================================================
-- GRANTS (Admin tables - service_role for management)
-- ============================================================================

-- Authenticated users can read admin-related tables (with RLS filtering)
GRANT SELECT ON admin_users TO authenticated;
GRANT SELECT ON admin_permissions TO authenticated;
GRANT SELECT ON admin_actions TO authenticated;
GRANT SELECT ON audit_logs TO authenticated;
GRANT SELECT ON moderation_actions TO authenticated;
GRANT SELECT ON feature_flags TO authenticated;
GRANT SELECT ON system_config TO authenticated;

-- Service role has full access for management
GRANT ALL ON admin_users TO service_role;
GRANT ALL ON admin_permissions TO service_role;
GRANT ALL ON admin_actions TO service_role;
GRANT ALL ON audit_logs TO service_role;
GRANT ALL ON moderation_actions TO service_role;
GRANT ALL ON feature_flags TO service_role;
GRANT ALL ON system_config TO service_role;