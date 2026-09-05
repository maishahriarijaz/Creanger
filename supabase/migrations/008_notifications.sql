-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 008: NOTIFICATIONS
-- ============================================================================
-- This migration creates the notification tables:
-- push_tokens, notifications, user_presence
-- ============================================================================

-- ============================================================================
-- ENUM TYPES
-- ============================================================================

CREATE TYPE push_provider_type AS ENUM (
    'fcm',
    'apns',
    'web_push'
);

CREATE TYPE notification_type AS ENUM (
    'new_message',
    'message_reply',
    'message_reaction',
    'mention',
    'group_invite',
    'story',
    'system',
    'security'
);

CREATE TYPE presence_status AS ENUM (
    'online',
    'away',
    'offline',
    'invisible'
);

-- ============================================================================
-- PUSH_TOKENS TABLE (Device push notification tokens)
-- ============================================================================

CREATE TABLE push_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
    provider push_provider_type NOT NULL DEFAULT 'fcm',
    token TEXT NOT NULL,
    platform device_platform NOT NULL DEFAULT 'unknown',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT push_tokens_unique UNIQUE (user_id, device_id, provider),
    CONSTRAINT push_tokens_token_not_empty CHECK (token <> '')
);

COMMENT ON TABLE push_tokens IS 'Device push notification tokens for FCM/APNS/Web Push';
COMMENT ON COLUMN push_tokens.token IS 'Push notification token from provider (FCM, APNS, etc.)';
COMMENT ON COLUMN push_tokens.provider IS 'Push service provider';

CREATE INDEX idx_push_tokens_user_id ON push_tokens(user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_push_tokens_device_id ON push_tokens(device_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_push_tokens_token ON push_tokens(token) WHERE revoked_at IS NULL;

-- ============================================================================
-- NOTIFICATIONS TABLE (User notifications)
-- ============================================================================

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type notification_type NOT NULL,
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL, -- User who triggered notification
    entity_type TEXT, -- Type of entity (message, chat, story, etc.)
    entity_id UUID, -- ID of the entity
    payload JSONB DEFAULT '{}', -- Flexible notification metadata
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT notifications_payload_valid CHECK (jsonb_typeof(payload) = 'object')
);

COMMENT ON TABLE notifications IS 'User notifications for various events';
COMMENT ON COLUMN notifications.actor_id IS 'User who triggered the notification';
COMMENT ON COLUMN notifications.entity_type IS 'Type of entity (message, chat, story, user, etc.)';
COMMENT ON COLUMN notifications.entity_id IS 'ID of the related entity';
COMMENT ON COLUMN notifications.payload IS 'Flexible metadata for notification rendering';
COMMENT ON COLUMN notifications.read_at IS 'When the notification was marked as read';

CREATE INDEX idx_notifications_user_id_created ON notifications(user_id, created_at DESC) WHERE read_at IS NULL;
CREATE INDEX idx_notifications_user_id_read ON notifications(user_id, read_at) WHERE read_at IS NULL;
CREATE INDEX idx_notifications_created_at ON notifications(created_at DESC);
CREATE INDEX idx_notifications_type ON notifications(type);

-- Trigger for notification notifications
CREATE OR REPLACE FUNCTION notify_new_notification()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('new_notification', json_build_object(
        'id', NEW.id,
        'user_id', NEW.user_id,
        'type', NEW.type,
        'created_at', NEW.created_at
    )::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_new_notification
    AFTER INSERT ON notifications
    FOR EACH ROW
    EXECUTE FUNCTION notify_new_notification();

-- ============================================================================
-- USER_PRESENCE TABLE (Lightweight presence tracking)
-- ============================================================================

CREATE TABLE user_presence (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    status presence_status NOT NULL DEFAULT 'offline',
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE user_presence IS 'Lightweight presence tracking (use Realtime for typing)';
COMMENT ON COLUMN user_presence.status IS 'Current presence status';
COMMENT ON COLUMN user_presence.last_seen_at IS 'Last time user was active';

CREATE INDEX idx_user_presence_status ON user_presence(status) WHERE status = 'online';
CREATE INDEX idx_user_presence_last_seen ON user_presence(last_seen_at);

CREATE TRIGGER update_user_presence_updated_at
    BEFORE UPDATE ON user_presence
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- RLS POLICIES
-- ============================================================================

ALTER TABLE push_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_presence ENABLE ROW LEVEL SECURITY;

-- Push Tokens: Users can read/manage their own tokens
CREATE POLICY push_tokens_select_own ON push_tokens
    FOR SELECT USING (user_id = current_user_id());
CREATE POLICY push_tokens_insert_own ON push_tokens
    FOR INSERT WITH CHECK (user_id = current_user_id());
CREATE POLICY push_tokens_update_own ON push_tokens
    FOR UPDATE USING (user_id = current_user_id());
CREATE POLICY push_tokens_delete_own ON push_tokens
    FOR DELETE USING (user_id = current_user_id());

-- Notifications: Users can only read their own notifications
CREATE POLICY notifications_select_own ON notifications
    FOR SELECT USING (user_id = current_user_id());
CREATE POLICY notifications_update_own ON notifications
    FOR UPDATE USING (user_id = current_user_id());
CREATE POLICY notifications_delete_own ON notifications
    FOR DELETE USING (user_id = current_user_id());
-- Insert only via service_role (server-side notification creation)

-- User Presence: Users can read all (for status display); update own
CREATE POLICY user_presence_select_all ON user_presence
    FOR SELECT USING (TRUE);
CREATE POLICY user_presence_update_own ON user_presence
    FOR UPDATE USING (user_id = current_user_id());

-- ============================================================================
-- GRANTS
-- ============================================================================

GRANT SELECT ON push_tokens TO authenticated;
GRANT INSERT, UPDATE, DELETE ON push_tokens TO authenticated;
GRANT SELECT ON notifications TO authenticated;
GRANT UPDATE ON notifications TO authenticated;
GRANT SELECT ON user_presence TO authenticated;

GRANT ALL ON push_tokens TO service_role;
GRANT ALL ON notifications TO service_role;
GRANT ALL ON user_presence TO service_role;