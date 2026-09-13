-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 013: INDEXES AND TRIGGERS
-- ============================================================================
-- This migration adds comprehensive indexes and triggers for performance
-- and data integrity across all tables.
-- ============================================================================

-- ============================================================================
-- ADDITIONAL INDEXES FOR PERFORMANCE
-- ============================================================================

-- Users
CREATE INDEX idx_users_created_at ON users(created_at DESC);

-- Profiles (profiles has no deleted_at column - plain indexes)
CREATE INDEX idx_profiles_first_name ON profiles(first_name);
CREATE INDEX idx_profiles_last_name ON profiles(last_name);

-- User Identities
CREATE INDEX idx_user_identities_provider_user ON user_identities(provider, provider_user_id);

-- Privacy Settings
CREATE INDEX idx_privacy_settings_updated_at ON privacy_settings(updated_at);

-- Chats
CREATE INDEX idx_chats_type_updated ON chats(type, updated_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_chats_public ON chats(is_public) WHERE is_public = TRUE AND deleted_at IS NULL;

-- Messages
CREATE INDEX idx_messages_chat_sender ON messages(chat_id, sender_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_messages_type ON messages(message_type) WHERE deleted_at IS NULL;
CREATE INDEX idx_messages_metadata ON messages USING gin(metadata);

-- Chat Read State
-- NOTE: idx_chat_read_state_unread already exists from migration 004;
-- the redundant (user_id) variant originally named identically is removed.

-- Media
CREATE INDEX idx_media_owner_type ON media(owner_id, mime_type) WHERE deleted_at IS NULL;
CREATE INDEX idx_media_owner_created ON media(owner_id, created_at DESC) WHERE deleted_at IS NULL;

-- Notifications
CREATE INDEX idx_notifications_entity ON notifications(entity_type, entity_id) WHERE read_at IS NULL;

-- Stories
-- NOTE: partial index predicate cannot use NOW() (must be IMMUTABLE);
-- RLS policy re-checks expiry at query time.
CREATE INDEX idx_stories_active ON stories(user_id, created_at DESC) WHERE deleted_at IS NULL;



-- ============================================================================
-- TRIGGERS FOR DATA INTEGRITY
-- ============================================================================

-- Trigger to update chat.updated_at when new message is sent
CREATE OR REPLACE FUNCTION update_chat_on_message()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE chats SET updated_at = NEW.created_at WHERE id = NEW.chat_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_update_chat_on_message
    AFTER INSERT ON messages
    FOR EACH ROW
    EXECUTE FUNCTION update_chat_on_message();

-- Trigger to update chat.updated_at when member changes
CREATE OR REPLACE FUNCTION update_chat_on_member_change()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE chats SET updated_at = NOW() WHERE id = NEW.chat_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_update_chat_on_member_change
    AFTER INSERT OR UPDATE ON chat_members
    FOR EACH ROW
    EXECUTE FUNCTION update_chat_on_member_change();

-- Trigger to increment unread count for chat members on new message
CREATE OR REPLACE FUNCTION increment_unread_on_message()
RETURNS TRIGGER AS $$
BEGIN
    -- Increment unread count for all chat members except sender
    UPDATE chat_read_state
    SET unread_count = unread_count + 1,
        updated_at = NOW()
    WHERE chat_id = NEW.chat_id
      AND user_id <> NEW.sender_id;
    
    -- Set read state for sender (they read their own message)
    INSERT INTO chat_read_state (chat_id, user_id, last_read_message_id, last_read_at, unread_count)
    VALUES (NEW.chat_id, NEW.sender_id, NEW.id, NOW(), 0)
    ON CONFLICT (chat_id, user_id)
    DO UPDATE SET 
        last_read_message_id = NEW.id,
        last_read_at = NOW(),
        unread_count = 0,
        updated_at = NOW();
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_increment_unread_on_message
    AFTER INSERT ON messages
    FOR EACH ROW
    EXECUTE FUNCTION increment_unread_on_message();

-- Trigger to update story.view_count when view is added
CREATE OR REPLACE FUNCTION increment_story_view_count()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE stories SET view_count = view_count + 1 WHERE id = NEW.story_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_increment_story_view_count
    AFTER INSERT ON story_views
    FOR EACH ROW
    EXECUTE FUNCTION increment_story_view_count();

-- Trigger to update story.reaction_count when reaction is added/removed
CREATE OR REPLACE FUNCTION update_story_reaction_count()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE stories SET reaction_count = reaction_count + 1 WHERE id = NEW.story_id;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE stories SET reaction_count = GREATEST(0, reaction_count - 1) WHERE id = OLD.story_id;
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_update_story_reaction_count
    AFTER INSERT OR DELETE ON story_reactions
    FOR EACH ROW
    EXECUTE FUNCTION update_story_reaction_count();

-- Trigger to update polls.closed_at when closes_at passes
CREATE OR REPLACE FUNCTION auto_close_expired_polls()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE polls
    SET closed_at = closes_at
    WHERE closes_at <= NOW()
      AND closed_at IS NULL;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- This would be called via a scheduled job (pg_cron or external scheduler)
-- CREATE TRIGGER trigger_auto_close_polls
--     AFTER INSERT OR UPDATE ON polls
--     FOR EACH STATEMENT
--     EXECUTE FUNCTION auto_close_expired_polls();

-- ============================================================================
-- FUNCTIONS FOR COMMON OPERATIONS
-- ============================================================================

-- Function to get or create direct chat between two users
CREATE OR REPLACE FUNCTION get_or_create_direct_chat(p_user_a UUID, p_user_b UUID)
RETURNS UUID AS $$
DECLARE
    v_user_low UUID;
    v_user_high UUID;
    v_chat_id UUID;
BEGIN
    -- Ensure deterministic ordering
    IF p_user_a < p_user_b THEN
        v_user_low := p_user_a;
        v_user_high := p_user_b;
    ELSE
        v_user_low := p_user_b;
        v_user_high := p_user_a;
    END IF;
    
    -- Check if direct chat already exists
    SELECT dc.chat_id INTO v_chat_id
    FROM direct_chats dc
    WHERE dc.user_a_id = v_user_low AND dc.user_b_id = v_user_high;
    
    -- If not found, create it
    IF v_chat_id IS NULL THEN
        -- Create chat
        INSERT INTO chats (type, created_at) VALUES ('direct', NOW())
        RETURNING id INTO v_chat_id;
        
        -- Create direct_chat record
        INSERT INTO direct_chats (chat_id, user_a_id, user_b_id)
        VALUES (v_chat_id, v_user_low, v_user_high);
        
        -- Add members
        INSERT INTO chat_members (chat_id, user_id, role, joined_at)
        VALUES 
            (v_chat_id, p_user_a, 'member', NOW()),
            (v_chat_id, p_user_b, 'member', NOW());
        
        -- Initialize read states
        INSERT INTO chat_read_state (chat_id, user_id)
        VALUES 
            (v_chat_id, p_user_a),
            (v_chat_id, p_user_b);
    END IF;
    
    RETURN v_chat_id;
END;
$$ LANGUAGE plpgsql;

-- Function to mark messages as read for a user in a chat
CREATE OR REPLACE FUNCTION mark_chat_as_read(p_user_id UUID, p_chat_id UUID)
RETURNS VOID AS $$
DECLARE
    v_last_message_id UUID;
BEGIN
    -- Get the last message in the chat
    SELECT id INTO v_last_message_id
    FROM messages
    WHERE chat_id = p_chat_id AND deleted_at IS NULL
    ORDER BY created_at DESC
    LIMIT 1;
    
    -- Update read state
    INSERT INTO chat_read_state (chat_id, user_id, last_read_message_id, last_read_at, unread_count)
    VALUES (p_chat_id, p_user_id, v_last_message_id, NOW(), 0)
    ON CONFLICT (chat_id, user_id)
    DO UPDATE SET 
        last_read_message_id = v_last_message_id,
        last_read_at = NOW(),
        unread_count = 0,
        updated_at = NOW();
END;
$$ LANGUAGE plpgsql;

-- Function to search messages in a chat
CREATE OR REPLACE FUNCTION search_messages_in_chat(
    p_chat_id UUID,
    p_search_query TEXT,
    p_limit INTEGER DEFAULT 50
)
RETURNS TABLE (
    message_id UUID,
    content TEXT,
    sender_id UUID,
    created_at TIMESTAMPTZ
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        m.id AS message_id,
        m.content,
        m.sender_id,
        m.created_at
    FROM messages m
    WHERE m.chat_id = p_chat_id
      AND m.deleted_at IS NULL
      AND m.content ILIKE '%' || p_search_query || '%'
    ORDER BY m.created_at DESC
    LIMIT p_limit;
END;
$$ LANGUAGE plpgsql STABLE;

-- Function to get user's public profile
CREATE OR REPLACE FUNCTION get_public_profile(p_user_id UUID)
RETURNS TABLE (
    user_id UUID,
    username CITEXT,
    first_name TEXT,
    last_name TEXT,
    bio TEXT,
    avatar_url TEXT,
    presence_status presence_status,
    last_seen_at TIMESTAMPTZ
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        p.user_id,
        p.username,
        p.first_name,
        p.last_name,
        p.bio,
        m.public_url,
        COALESCE(pres.status, 'offline'::presence_status),
        pres.last_seen_at
    FROM profiles p
    LEFT JOIN media m ON m.id = p.avatar_media_id
    LEFT JOIN user_presence pres ON pres.user_id = p.user_id
    WHERE p.user_id = p_user_id;
END;
$$ LANGUAGE plpgsql STABLE;