-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 001: CORE IDENTITY
-- ============================================================================
-- This migration creates the core identity system tables:
-- users, profiles, user_identities, email_verifications, password_credentials,
-- devices, sessions, refresh_tokens
-- ============================================================================

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "citext";

-- ============================================================================
-- ENUM TYPES
-- ============================================================================

CREATE TYPE user_status AS ENUM (
    'active',
    'inactive',
    'suspended',
    'banned',
    'pending_verification',
    'deleted'
);

CREATE TYPE identity_provider AS ENUM (
    'email',
    'google',
    'apple',
    'phone'
);

CREATE TYPE device_platform AS ENUM (
    'android',
    'ios',
    'web',
    'desktop',
    'unknown'
);

CREATE TYPE session_revoke_reason AS ENUM (
    'user_requested',
    'security_concern',
    'token_expired',
    'password_changed',
    'device_revoked',
    'admin_action'
);

CREATE TYPE verification_purpose AS ENUM (
    'register',
    'login',
    'change_email',
    'reset_password',
    'verify_identity'
);

-- ============================================================================
-- USERS TABLE (Canonical account record)
-- ============================================================================

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    status user_status NOT NULL DEFAULT 'pending_verification',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

COMMENT ON TABLE users IS 'Canonical account record - minimal core identity';
COMMENT ON COLUMN users.id IS 'Unique user identifier (UUID)';
COMMENT ON COLUMN users.status IS 'Account lifecycle status';
COMMENT ON COLUMN users.deleted_at IS 'Soft delete timestamp for recovery/audit';

-- Indexes
CREATE INDEX idx_users_status ON users(status) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_deleted_at ON users(deleted_at) WHERE deleted_at IS NOT NULL;

-- Trigger to auto-update updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- PROFILES TABLE (User-facing profile information)
-- ============================================================================

CREATE TABLE profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    username CITEXT UNIQUE,
    first_name TEXT NOT NULL,
    last_name TEXT,
    bio TEXT,
    avatar_media_id UUID, -- FK to media table (added later)
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT profiles_username_format CHECK (
        username IS NULL OR username ~ '^[a-zA-Z][a-zA-Z0-9_]{3,31}$'
    ),
    CONSTRAINT profiles_first_name_not_empty CHECK (first_name <> ''),
    CONSTRAINT profiles_bio_length CHECK (bio IS NULL OR char_length(bio) <= 1000)
);

COMMENT ON TABLE profiles IS 'User-facing profile information';
COMMENT ON COLUMN profiles.username IS 'Unique public username (alphanumeric + underscore, 4-32 chars)';
COMMENT ON COLUMN profiles.avatar_media_id IS 'Reference to media table for profile photo';

CREATE INDEX idx_profiles_username ON profiles(username) WHERE username IS NOT NULL;
CREATE INDEX idx_profiles_updated_at ON profiles(updated_at);

CREATE TRIGGER update_profiles_updated_at
    BEFORE UPDATE ON profiles
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- USER_IDENTITIES TABLE (Authentication identities linked to account)
-- ============================================================================

CREATE TABLE user_identities (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider identity_provider NOT NULL,
    provider_user_id TEXT NOT NULL,
    email CITEXT,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT user_identities_unique_provider UNIQUE (provider, provider_user_id),
    CONSTRAINT user_identities_email_format CHECK (
        email IS NULL OR email ~ '^[^@]+@[^@]+\.[^@]+$'
    )
);

COMMENT ON TABLE user_identities IS 'Authentication identities linked to a Creanger account';
COMMENT ON COLUMN user_identities.provider IS 'Identity provider (email, google, apple, phone)';
COMMENT ON COLUMN user_identities.provider_user_id IS 'Unique identifier from the provider';
COMMENT ON COLUMN user_identities.email IS 'Email associated with this identity (if applicable)';
COMMENT ON COLUMN user_identities.email_verified IS 'Whether the email has been verified';

CREATE INDEX idx_user_identities_user_id ON user_identities(user_id);
CREATE INDEX idx_user_identities_email ON user_identities(email) WHERE email IS NOT NULL;

CREATE TRIGGER update_user_identities_updated_at
    BEFORE UPDATE ON user_identities
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- EMAIL_VERIFICATIONS TABLE (OTP/email verification with secure hash)
-- ============================================================================

CREATE TABLE email_verifications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email CITEXT NOT NULL,
    purpose verification_purpose NOT NULL,
    code_hash TEXT NOT NULL, -- Argon2id hash of the OTP code
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT email_verifications_future_expiry CHECK (expires_at > created_at),
    CONSTRAINT email_verifications_attempts_nonneg CHECK (attempts >= 0),
    CONSTRAINT email_verifications_max_attempts_positive CHECK (max_attempts > 0)
);

COMMENT ON TABLE email_verifications IS 'OTP/email verification codes - stores only secure hash';
COMMENT ON COLUMN email_verifications.code_hash IS 'Argon2id hash of the OTP - NEVER store plaintext codes';
COMMENT ON COLUMN email_verifications.attempts IS 'Number of verification attempts made';
COMMENT ON COLUMN email_verifications.max_attempts IS 'Maximum allowed attempts before invalidation';

CREATE INDEX idx_email_verifications_user_email_purpose ON email_verifications(user_id, email, purpose) WHERE verified_at IS NULL;
CREATE INDEX idx_email_verifications_expires_at ON email_verifications(expires_at) WHERE verified_at IS NULL;
CREATE INDEX idx_email_verifications_code_hash ON email_verifications(code_hash);

-- Auto-cleanup function for expired unverified codes
CREATE OR REPLACE FUNCTION cleanup_expired_email_verifications()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM email_verifications
    WHERE verified_at IS NULL
      AND expires_at < NOW()
      AND created_at < NOW() - INTERVAL '24 hours';
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- PASSWORD_CREDENTIALS TABLE (Argon2id password hashes)
-- ============================================================================

CREATE TABLE password_credentials (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    password_hash TEXT NOT NULL, -- Argon2id hash
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT password_credentials_hash_not_empty CHECK (password_hash <> '')
);

COMMENT ON TABLE password_credentials IS 'Password credentials - stores only Argon2id hash';
COMMENT ON COLUMN password_credentials.password_hash IS 'Argon2id hash - NEVER store plaintext passwords';

CREATE TRIGGER update_password_credentials_updated_at
    BEFORE UPDATE ON password_credentials
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- DEVICES TABLE (User devices with deduplication)
-- ============================================================================

CREATE TABLE devices (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_identifier TEXT NOT NULL, -- Stable hardware/app identifier
    device_name TEXT,
    platform device_platform NOT NULL DEFAULT 'unknown',
    os_version TEXT,
    app_version TEXT,
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT devices_unique_user_identifier UNIQUE (user_id, device_identifier),
    CONSTRAINT devices_identifier_not_empty CHECK (device_identifier <> '')
);

COMMENT ON TABLE devices IS 'User devices - one record per physical device per user';
COMMENT ON COLUMN devices.device_identifier IS 'Stable identifier (e.g., Android ID, iOS IDFV, browser fingerprint)';
COMMENT ON COLUMN devices.revoked_at IS 'When device access was revoked (soft delete)';

CREATE INDEX idx_devices_user_id ON devices(user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_devices_revoked_at ON devices(revoked_at) WHERE revoked_at IS NOT NULL;
CREATE INDEX idx_devices_last_seen_at ON devices(last_seen_at);

CREATE TRIGGER update_devices_updated_at
    BEFORE UPDATE ON devices
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- SESSIONS TABLE (Active user sessions)
-- ============================================================================

CREATE TABLE sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoke_reason session_revoke_reason,
    ip_hash TEXT, -- Hashed IP for security auditing
    user_agent TEXT,
    CONSTRAINT sessions_future_expiry CHECK (expires_at > created_at)
);

COMMENT ON TABLE sessions IS 'Active user sessions with device association';
COMMENT ON COLUMN sessions.ip_hash IS 'SHA-256 hash of client IP for audit (not raw IP)';
COMMENT ON COLUMN sessions.revoke_reason IS 'Reason for session revocation';

CREATE INDEX idx_sessions_user_id ON sessions(user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_sessions_device_id ON sessions(device_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_sessions_expires_at ON sessions(expires_at) WHERE revoked_at IS NULL;
CREATE INDEX idx_sessions_last_used_at ON sessions(last_used_at);

CREATE TRIGGER update_sessions_updated_at
    BEFORE UPDATE ON sessions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- REFRESH_TOKENS TABLE (Hashed opaque refresh tokens with rotation)
-- ============================================================================

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    family_id UUID NOT NULL, -- Groups rotated tokens together
    token_hash TEXT NOT NULL, -- Argon2id hash of opaque token
    issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    reuse_detected_at TIMESTAMPTZ,
    CONSTRAINT refresh_tokens_future_expiry CHECK (expires_at > issued_at)
);

COMMENT ON TABLE refresh_tokens IS 'Hashed refresh tokens with rotation and reuse detection';
COMMENT ON COLUMN refresh_tokens.family_id IS 'Token family ID - all rotated tokens share this';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'Argon2id hash of opaque refresh token';
COMMENT ON COLUMN refresh_tokens.rotated_at IS 'When this token was rotated (replaced by new token)';
COMMENT ON COLUMN refresh_tokens.reuse_detected_at IS 'Timestamp if token reuse attack detected';

CREATE INDEX idx_refresh_tokens_session_id ON refresh_tokens(session_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at) WHERE revoked_at IS NULL;

-- Function to detect token reuse (called during token validation)
CREATE OR REPLACE FUNCTION detect_refresh_token_reuse(p_token_hash TEXT, p_family_id UUID)
RETURNS BOOLEAN AS $$
DECLARE
    v_count INTEGER;
BEGIN
    -- Check if this token hash was already used (rotated or revoked)
    SELECT COUNT(*) INTO v_count
    FROM refresh_tokens
    WHERE family_id = p_family_id
      AND token_hash = p_token_hash
      AND (rotated_at IS NOT NULL OR revoked_at IS NOT NULL);
    
    RETURN v_count > 0;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- RLS POLICIES FOR CORE IDENTITY TABLES
-- ============================================================================

-- Enable RLS on all tables
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_identities ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_verifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE password_credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;

-- Helper function to get current user ID from auth context
CREATE OR REPLACE FUNCTION current_user_id()
RETURNS UUID AS $$
BEGIN
    RETURN COALESCE(
        NULLIF(current_setting('request.jwt.claims', TRUE), '')::jsonb ->> 'sub',
        NULLIF(current_setting('request.jwt.claim.sub', TRUE), '')
    )::UUID;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

-- Users: Users can read their own record
CREATE POLICY users_select_own ON users
    FOR SELECT USING (id = current_user_id());

-- Profiles: Users can read all profiles (public info), update own
CREATE POLICY profiles_select_all ON profiles
    FOR SELECT USING (TRUE);
CREATE POLICY profiles_update_own ON profiles
    FOR UPDATE USING (user_id = current_user_id());
CREATE POLICY profiles_insert_own ON profiles
    FOR INSERT WITH CHECK (user_id = current_user_id());

-- User Identities: Users can read/manage their own identities
CREATE POLICY user_identities_select_own ON user_identities
    FOR SELECT USING (user_id = current_user_id());
CREATE POLICY user_identities_insert_own ON user_identities
    FOR INSERT WITH CHECK (user_id = current_user_id());
CREATE POLICY user_identities_update_own ON user_identities
    FOR UPDATE USING (user_id = current_user_id());
CREATE POLICY user_identities_delete_own ON user_identities
    FOR DELETE USING (user_id = current_user_id());

-- Email Verifications: Users can read/manage their own verification codes
CREATE POLICY email_verifications_select_own ON email_verifications
    FOR SELECT USING (user_id = current_user_id());
CREATE POLICY email_verifications_insert_own ON email_verifications
    FOR INSERT WITH CHECK (user_id = current_user_id());
CREATE POLICY email_verifications_update_own ON email_verifications
    FOR UPDATE USING (user_id = current_user_id());

-- Password Credentials: NO direct access - only via auth service
-- (Intentionally no policies - accessed only by service role)

-- Devices: Users can read/manage their own devices
CREATE POLICY devices_select_own ON devices
    FOR SELECT USING (user_id = current_user_id());
CREATE POLICY devices_insert_own ON devices
    FOR INSERT WITH CHECK (user_id = current_user_id());
CREATE POLICY devices_update_own ON devices
    FOR UPDATE USING (user_id = current_user_id());
CREATE POLICY devices_delete_own ON devices
    FOR DELETE USING (user_id = current_user_id());

-- Sessions: Users can read/manage their own sessions
CREATE POLICY sessions_select_own ON sessions
    FOR SELECT USING (user_id = current_user_id());
CREATE POLICY sessions_insert_own ON sessions
    FOR INSERT WITH CHECK (user_id = current_user_id());
CREATE POLICY sessions_update_own ON sessions
    FOR UPDATE USING (user_id = current_user_id());
CREATE POLICY sessions_delete_own ON sessions
    FOR DELETE USING (user_id = current_user_id());

-- Refresh Tokens: NO direct client access - only via auth service
-- (Intentionally no policies - accessed only by service role)

-- ============================================================================
-- GRANTS
-- ============================================================================

GRANT SELECT, INSERT, UPDATE, DELETE ON users TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON profiles TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON user_identities TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON email_verifications TO authenticated;
-- password_credentials: NO grants to authenticated (service role only)
GRANT SELECT, INSERT, UPDATE, DELETE ON devices TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON sessions TO authenticated;
-- refresh_tokens: NO grants to authenticated (service role only)

-- Service role has full access
GRANT ALL ON users TO service_role;
GRANT ALL ON profiles TO service_role;
GRANT ALL ON user_identities TO service_role;
GRANT ALL ON email_verifications TO service_role;
GRANT ALL ON password_credentials TO service_role;
GRANT ALL ON devices TO service_role;
GRANT ALL ON sessions TO service_role;
GRANT ALL ON refresh_tokens TO service_role;