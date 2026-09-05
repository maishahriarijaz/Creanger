-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 003: CHAT MODEL
-- ============================================================================
-- This migration creates the chat and conversation tables:
-- chats, direct_chats, chat_members, chat_permissions, topics
-- ============================================================================

-- ============================================================================
-- ENUM TYPES
-- ============================================================================

CREATE TYPE chat_type AS ENUM (
    'direct',
    'group',
    'channel'
);

CREATE TYPE chat_member_role AS ENUM (
    'member',
    'admin',
    'owner'
);

CREATE TYPE topic_state AS ENUM (
    'open',
    'closed',
    'hidden'
);

-- ============================================================================
-- CHATS TABLE (Root conversation entity)
-- ============================================================================

CREATE TABLE chats (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    type chat_type NOT NULL,
    title TEXT, -- For groups/channels; NULL for direct chats
    username CITEXT UNIQUE, -- Public username for channels (like @channelname)
    description TEXT,
    avatar_media_id UUID, -- FK to media table
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL, -- Creator/owner
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    is_public BOOLEAN NOT NULL DEFAULT FALSE, -- Public vs private group/channel
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT chats_title_required_for_groups CHECK (
        type = 'direct' OR title IS NOT NULL
    ),
    CONSTRAINT chats_username_format CHECK (
        username IS NULL OR username ~ '^[a-zA-Z][a-zA-Z0-9_]{3,31}$'
    ),
    CONSTRAINT chats_description_length CHECK (description IS NULL OR char_length(description) <= 255)
);

COMMENT ON TABLE chats IS 'Root conversation entity - direct chats, groups, and channels';
COMMENT ON COLUMN chats.type IS 'Type of conversation: direct, group, or channel';
COMMENT ON COLUMN chats.username IS 'Public username (for channels, like @channelname)';
COMMENT ON COLUMN chats.owner_id IS 'Creator/owner of the chat (groups/channels only)';

CREATE INDEX idx_chats_type ON chats(type) WHERE deleted_at IS NULL;
CREATE INDEX idx_chats_username ON chats(username) WHERE username IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_chats_owner_id ON chats(owner_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_chats_updated_at ON chats(updated_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_chats_deleted_at ON chats(deleted_at) WHERE deleted_at IS NOT NULL;

CREATE TRIGGER update_chats_updated_at
    BEFORE UPDATE ON chats
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- DIRECT_CHATS TABLE (Canonical one-to-one conversation)
-- ============================================================================

CREATE TABLE direct_chats (
    chat_id UUID PRIMARY KEY REFERENCES chats(id) ON DELETE CASCADE,
    user_a_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_b_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT direct_chats_no_self_chat CHECK (user_a_id <> user_b_id),
    CONSTRAINT direct_chats_deterministic_order CHECK (user_a_id < user_b_id)
);

COMMENT ON TABLE direct_chats IS 'Ensures one canonical direct chat between two users';
COMMENT ON COLUMN direct_chats.user_a_id IS 'Lower user ID (deterministic ordering)';
COMMENT ON COLUMN direct_chats.user_b_id IS 'Higher user ID (deterministic ordering)';

CREATE INDEX idx_direct_chats_user_a ON direct_chats(user_a_id);
CREATE INDEX idx_direct_chats_user_b ON direct_chats(user_b_id);

-- ============================================================================
-- CHAT_MEMBERS TABLE (Chat membership with roles)
-- ============================================================================

CREATE TABLE chat_members (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role chat_member_role NOT NULL DEFAULT 'member',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at TIMESTAMPTZ,
    muted_until TIMESTAMPTZ,
    pinned_position INTEGER,
    last_read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chat_members_unique_chat_user UNIQUE (chat_id, user_id),
    CONSTRAINT chat_members_muted_future CHECK (muted_until IS NULL OR muted_until > created_at)
);

COMMENT ON TABLE chat_members IS 'Chat membership records with role and state';
COMMENT ON COLUMN chat_members.role IS 'User role in chat: member, admin, owner';
COMMENT ON COLUMN chat_members.left_at IS 'When user left the chat (NULL = active)';
COMMENT ON COLUMN chat_members.muted_until IS 'Temporary mute expiration (NULL = not muted)';
COMMENT ON COLUMN chat_members.pinned_position IS 'Pin position in member list (NULL = not pinned)';

CREATE INDEX idx_chat_members_chat_id ON chat_members(chat_id) WHERE left_at IS NULL;
CREATE INDEX idx_chat_members_user_id ON chat_members(user_id) WHERE left_at IS NULL;
CREATE INDEX idx_chat_members_role ON chat_members(chat_id, role) WHERE left_at IS NULL;
CREATE INDEX idx_chat_members_muted ON chat_members(chat_id, muted_until) WHERE left_at IS NULL AND muted_until IS NOT NULL;
CREATE INDEX idx_chat_members_joined_at ON chat_members(chat_id, joined_at);

CREATE TRIGGER update_chat_members_updated_at
    BEFORE UPDATE ON chat_members
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- CHAT_PERMISSIONS TABLE (Per-chat permission configuration)
-- ============================================================================

CREATE TABLE chat_permissions (
    chat_id UUID PRIMARY KEY REFERENCES chats(id) ON DELETE CASCADE,
    -- Messaging permissions
    can_send_messages BOOLEAN NOT NULL DEFAULT TRUE,
    can_send_media BOOLEAN NOT NULL DEFAULT TRUE,
    can_send_polls BOOLEAN NOT NULL DEFAULT TRUE,
    can_send_stickers BOOLEAN NOT NULL DEFAULT TRUE,
    can_send_animated_emojis BOOLEAN NOT NULL DEFAULT TRUE,
    can_send_voice_messages BOOLEAN NOT NULL DEFAULT TRUE,
    -- Interaction permissions
    can_add_members BOOLEAN NOT NULL DEFAULT TRUE,
    can_pin_messages BOOLEAN NOT NULL DEFAULT TRUE,
    can_edit_chat_info BOOLEAN NOT NULL DEFAULT TRUE,
    can_delete_messages BOOLEAN NOT NULL DEFAULT TRUE,
    can_manage_topics BOOLEAN NOT NULL DEFAULT TRUE,
    -- Advanced
    can_invite_users_by_link BOOLEAN NOT NULL DEFAULT TRUE,
    can_post_anonymous_messages BOOLEAN NOT NULL DEFAULT FALSE,
    -- Metadata
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE chat_permissions IS 'Per-chat permission configuration';
COMMENT ON COLUMN chat_permissions.can_send_messages IS 'Whether members can send messages';
COMMENT ON COLUMN chat_permissions.can_send_media IS 'Whether members can send media (images, videos, files)';
COMMENT ON COLUMN chat_permissions.can_add_members IS 'Whether members can add new members';

CREATE TRIGGER update_chat_permissions_updated_at
    BEFORE UPDATE ON chat_permissions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- TOPICS TABLE (Forum topics within a chat)
-- ============================================================================

CREATE TABLE topics (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    creator_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    icon_emoji TEXT,
    icon_custom_emoji_id UUID,
    state topic_state NOT NULL DEFAULT 'open',
    is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    total_messages_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT topics_name_not_empty CHECK (name <> ''),
    CONSTRAINT topics_name_length CHECK (char_length(name) <= 128),
    CONSTRAINT topics_positive_message_count CHECK (total_messages_count >= 0)
);

COMMENT ON TABLE topics IS 'Forum topics within a group/channel (optional feature)';
COMMENT ON COLUMN topics.state IS 'Topic state: open, closed, hidden';
COMMENT ON COLUMN topics.is_hidden IS 'Whether topic is hidden from default view';
COMMENT ON COLUMN topics.total_messages_count IS 'Cached message count for this topic';

CREATE INDEX idx_topics_chat_id ON topics(chat_id) WHERE state <> 'hidden';
CREATE INDEX idx_topics_creator_id ON topics(creator_id);
CREATE INDEX idx_topics_updated_at ON topics(updated_at);
CREATE INDEX idx_topics_state ON topics(chat_id, state);

CREATE TRIGGER update_topics_updated_at
    BEFORE UPDATE ON topics
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- RLS POLICIES
-- ============================================================================

ALTER TABLE chats ENABLE ROW LEVEL SECURITY;
ALTER TABLE direct_chats ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE topics ENABLE ROW LEVEL SECURITY;

-- Chats: Members can read their chats; owners/admins can update
CREATE POLICY chats_select_members ON chats
    FOR SELECT USING (
        id IN (
            SELECT chat_id FROM chat_members 
            WHERE user_id = current_user_id() AND left_at IS NULL
        ) OR is_public = TRUE
    );
CREATE POLICY chats_insert_owner ON chats
    FOR INSERT WITH CHECK (owner_id = current_user_id());
CREATE POLICY chats_update_owner ON chats
    FOR UPDATE USING (owner_id = current_user_id());
-- Admins can update via service role
CREATE POLICY chats_delete_owner ON chats
    FOR DELETE USING (owner_id = current_user_id());

-- Direct Chats: Both participants can read
CREATE POLICY direct_chats_select_participants ON direct_chats
    FOR SELECT USING (
        user_a_id = current_user_id() OR user_b_id = current_user_id()
    );
CREATE POLICY direct_chats_insert_participant ON direct_chats
    FOR INSERT WITH CHECK (
        user_a_id = current_user_id() OR user_b_id = current_user_id()
    );

-- Chat Members: Members can read all members; admins/owner can manage
CREATE POLICY chat_members_select_members ON chat_members
    FOR SELECT USING (
        chat_id IN (
            SELECT chat_id FROM chat_members 
            WHERE user_id = current_user_id() AND left_at IS NULL
        )
    );
CREATE POLICY chat_members_insert_self ON chat_members
    FOR INSERT WITH CHECK (user_id = current_user_id());
CREATE POLICY chat_members_update_own ON chat_members
    FOR UPDATE USING (user_id = current_user_id());
-- Admins/owner can update other members via service role
CREATE POLICY chat_members_delete_self ON chat_members
    FOR DELETE USING (user_id = current_user_id());

-- Chat Permissions: Members can read; owner/admins can update
CREATE POLICY chat_permissions_select_members ON chat_permissions
    FOR SELECT USING (
        chat_id IN (
            SELECT chat_id FROM chat_members 
            WHERE user_id = current_user_id() AND left_at IS NULL
        )
    );
CREATE POLICY chat_permissions_update_owner ON chat_permissions
    FOR UPDATE USING (
        chat_id IN (
            SELECT id FROM chats WHERE owner_id = current_user_id()
        )
    );

-- Topics: Members can read; creator/owner can update
CREATE POLICY topics_select_members ON topics
    FOR SELECT USING (
        chat_id IN (
            SELECT chat_id FROM chat_members 
            WHERE user_id = current_user_id() AND left_at IS NULL
        )
    );
CREATE POLICY topics_insert_creator ON topics
    FOR INSERT WITH CHECK (creator_id = current_user_id());
CREATE POLICY topics_update_creator ON topics
    FOR UPDATE USING (creator_id = current_user_id());
CREATE POLICY topics_delete_creator ON topics
    FOR DELETE USING (creator_id = current_user_id());

-- ============================================================================
-- GRANTS
-- ============================================================================

GRANT SELECT, INSERT ON chats TO authenticated;
GRANT UPDATE (title, description, avatar_media_id, is_verified, is_public, is_archived) ON chats TO authenticated;
GRANT SELECT, INSERT ON direct_chats TO authenticated;
GRANT SELECT, INSERT ON chat_members TO authenticated;
GRANT UPDATE (role, muted_until, pinned_position, last_read_at) ON chat_members TO authenticated;
GRANT SELECT ON chat_permissions TO authenticated;
GRANT SELECT ON topics TO authenticated;
GRANT INSERT, UPDATE, DELETE ON topics TO authenticated;

GRANT ALL ON chats TO service_role;
GRANT ALL ON direct_chats TO service_role;
GRANT ALL ON chat_members TO service_role;
GRANT ALL ON chat_permissions TO service_role;
GRANT ALL ON topics TO service_role;