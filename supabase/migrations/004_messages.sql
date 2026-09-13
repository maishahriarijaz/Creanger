-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 004: MESSAGES
-- ============================================================================
-- This migration creates the messaging tables:
-- messages, message_replies, message_edits, message_deletions,
-- message_reactions, chat_read_state, message_deliveries, message_forwards
-- ============================================================================

-- ============================================================================
-- ENUM TYPES
-- ============================================================================

CREATE TYPE message_type AS ENUM (
    'text',
    'image',
    'video',
    'document',
    'audio',
    'voice',
    'sticker',
    'poll',
    'system',
    'location'
);

CREATE TYPE message_status AS ENUM (
    'pending',
    'sent',
    'delivered',
    'read',
    'failed',
    'scheduled'
);

-- ============================================================================
-- MESSAGES TABLE (Core message entity)
-- ============================================================================

CREATE TABLE messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    topic_id UUID REFERENCES topics(id) ON DELETE SET NULL,
    message_type message_type NOT NULL DEFAULT 'text',
    content TEXT,
    reply_to_message_id UUID REFERENCES messages(id) ON DELETE SET NULL,
    status message_status NOT NULL DEFAULT 'pending',
    scheduled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    is_system_message BOOLEAN NOT NULL DEFAULT FALSE,
    metadata JSONB DEFAULT '{}',
    CONSTRAINT messages_content_not_empty CHECK (
        (message_type <> 'text' AND message_type <> 'document') OR content IS NOT NULL
    ),
    CONSTRAINT messages_scheduled_future CHECK (
        scheduled_at IS NULL OR scheduled_at > created_at
    ),
    CONSTRAINT messages_edited_after_created CHECK (
        edited_at IS NULL OR edited_at >= created_at
    )
);

COMMENT ON TABLE messages IS 'Core message entity - all message types stored here';
COMMENT ON COLUMN messages.content IS 'Text content or caption (text stored as UTF-8)';
COMMENT ON COLUMN messages.status IS 'Message delivery status';
COMMENT ON COLUMN messages.scheduled_at IS 'Scheduled send time (NULL = immediate)';
COMMENT ON COLUMN messages.deleted_at IS 'Soft delete timestamp';
COMMENT ON COLUMN messages.metadata IS 'Flexible JSONB for special message data';

CREATE INDEX idx_messages_chat_id_created ON messages(chat_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_messages_sender_id ON messages(sender_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_messages_reply_to ON messages(reply_to_message_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_messages_status ON messages(status) WHERE status IN ('pending', 'scheduled');
CREATE INDEX idx_messages_scheduled ON messages(scheduled_at) WHERE status = 'scheduled' AND scheduled_at IS NOT NULL;
CREATE INDEX idx_messages_topic_id ON messages(topic_id, created_at DESC) WHERE deleted_at IS NULL AND topic_id IS NOT NULL;
CREATE INDEX idx_messages_content_search ON messages USING gin(to_tsvector('english', content)) WHERE deleted_at IS NULL AND content IS NOT NULL;

-- Trigger for new message notifications
CREATE OR REPLACE FUNCTION notify_new_message()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('new_message', json_build_object(
        'id', NEW.id,
        'chat_id', NEW.chat_id,
        'sender_id', NEW.sender_id,
        'created_at', NEW.created_at
    )::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_new_message
    AFTER INSERT ON messages
    FOR EACH ROW
    EXECUTE FUNCTION notify_new_message();

-- Trigger for message update notifications
CREATE OR REPLACE FUNCTION notify_message_update()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('message_update', json_build_object(
        'id', NEW.id,
        'chat_id', NEW.chat_id,
        'edited_at', NEW.edited_at,
        'deleted_at', NEW.deleted_at
    )::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_message_update
    AFTER UPDATE ON messages
    FOR EACH ROW
    EXECUTE FUNCTION notify_message_update();

-- ============================================================================
-- MESSAGE_REPLIES TABLE (Advanced quote functionality)
-- ============================================================================

CREATE TABLE message_replies (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    reply_to_message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    quoted_text TEXT,
    quote_offset INTEGER DEFAULT 0,
    quote_length INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT message_replies_different_messages CHECK (message_id <> reply_to_message_id)
);

COMMENT ON TABLE message_replies IS 'Advanced reply/quote relationships';

CREATE INDEX idx_message_replies_message_id ON message_replies(message_id);
CREATE INDEX idx_message_replies_reply_to ON message_replies(reply_to_message_id);

-- ============================================================================
-- MESSAGE_EDITS TABLE (Edit history)
-- ============================================================================

CREATE TABLE message_edits (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    previous_content TEXT NOT NULL,
    edit_sequence INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT message_edits_unique_sequence UNIQUE (message_id, edit_sequence)
);

COMMENT ON TABLE message_edits IS 'Message edit history (immutable log)';

CREATE INDEX idx_message_edits_message_id ON message_edits(message_id, edit_sequence DESC);

-- ============================================================================
-- MESSAGE_DELETIONS TABLE (Per-user deletion tracking)
-- ============================================================================

CREATE TABLE message_deletions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_for_everyone BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT message_deletions_unique UNIQUE (message_id, user_id)
);

COMMENT ON TABLE message_deletions IS 'Per-user message deletion tracking';
COMMENT ON COLUMN message_deletions.is_for_everyone IS 'TRUE = sender deleted for all participants';

CREATE INDEX idx_message_deletions_user_id ON message_deletions(user_id);
CREATE INDEX idx_message_deletions_message_id ON message_deletions(message_id);

-- Function to check if message is deleted for user
CREATE OR REPLACE FUNCTION message_is_deleted_for_user(p_message_id UUID, p_user_id UUID)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1 FROM message_deletions
        WHERE message_id = p_message_id
          AND (user_id = p_user_id OR is_for_everyone = TRUE)
    ) OR EXISTS (
        SELECT 1 FROM messages
        WHERE id = p_message_id AND deleted_at IS NOT NULL
    );
END;
$$ LANGUAGE plpgsql STABLE;

-- ============================================================================
-- MESSAGE_REACTIONS TABLE (Reactions on messages)
-- ============================================================================

CREATE TABLE message_reactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reaction TEXT NOT NULL,
    is_custom_emoji BOOLEAN NOT NULL DEFAULT FALSE,
    custom_emoji_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT message_reactions_unique UNIQUE (message_id, user_id, reaction),
    CONSTRAINT message_reactions_reaction_not_empty CHECK (reaction <> '')
);

COMMENT ON TABLE message_reactions IS 'Reactions on messages';
COMMENT ON COLUMN message_reactions.reaction IS 'Reaction emoji or custom emoji ID';
COMMENT ON COLUMN message_reactions.is_custom_emoji IS 'TRUE if reaction is a custom emoji';

CREATE INDEX idx_message_reactions_message_id ON message_reactions(message_id);
CREATE INDEX idx_message_reactions_user_id ON message_reactions(user_id);

CREATE TRIGGER update_message_reactions_updated_at
    BEFORE UPDATE ON message_reactions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Trigger for reaction notifications
CREATE OR REPLACE FUNCTION notify_reaction_change()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('reaction_change', json_build_object(
        'message_id', COALESCE(NEW.message_id, OLD.message_id),
        'user_id', COALESCE(NEW.user_id, OLD.user_id),
        'reaction', COALESCE(NEW.reaction, OLD.reaction),
        'event', TG_OP
    )::text);
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_reaction_change
    AFTER INSERT OR UPDATE OR DELETE ON message_reactions
    FOR EACH ROW
    EXECUTE FUNCTION notify_reaction_change();

-- ============================================================================
-- CHAT_READ_STATE TABLE (Chat-level read cursors)
-- ============================================================================

CREATE TABLE chat_read_state (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    last_read_message_id UUID REFERENCES messages(id) ON DELETE SET NULL,
    last_read_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    unread_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chat_read_state_unique UNIQUE (chat_id, user_id),
    CONSTRAINT chat_read_state_unread_nonneg CHECK (unread_count >= 0)
);

COMMENT ON TABLE chat_read_state IS 'Chat-level read cursors for unread tracking';
COMMENT ON COLUMN chat_read_state.last_read_message_id IS 'Last message read by user';
COMMENT ON COLUMN chat_read_state.unread_count IS 'Cached unread count for quick access';

CREATE INDEX idx_chat_read_state_user_id ON chat_read_state(user_id);
CREATE INDEX idx_chat_read_state_chat_id ON chat_read_state(chat_id);
CREATE INDEX idx_chat_read_state_unread ON chat_read_state(user_id, unread_count) WHERE unread_count > 0;

CREATE TRIGGER update_chat_read_state_updated_at
    BEFORE UPDATE ON chat_read_state
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- MESSAGE_DELIVERIES TABLE (Per-user delivery tracking - multi-device)
-- ============================================================================

CREATE TABLE message_deliveries (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    delivered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
    CONSTRAINT message_deliveries_unique UNIQUE (message_id, user_id, device_id)
);

COMMENT ON TABLE message_deliveries IS 'Multi-device delivery tracking (NULL device_id = all devices)';

CREATE INDEX idx_message_deliveries_message_id ON message_deliveries(message_id);
CREATE INDEX idx_message_deliveries_user_id ON message_deliveries(user_id, delivered_at DESC);
CREATE INDEX idx_message_deliveries_device_id ON message_deliveries(device_id);

-- ============================================================================
-- MESSAGE_FORWARDS TABLE (Forward tracking)
-- ============================================================================

CREATE TABLE message_forwards (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    original_message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    forwarded_by UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    forwarded_to_chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    forwarded_message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT message_forwards_unique UNIQUE (forwarded_message_id)
);

COMMENT ON TABLE message_forwards IS 'Message forward tracking for attribution';

CREATE INDEX idx_message_forwards_original ON message_forwards(original_message_id);
CREATE INDEX idx_message_forwards_forwarded_by ON message_forwards(forwarded_by);
CREATE INDEX idx_message_forwards_chat ON message_forwards(forwarded_to_chat_id);