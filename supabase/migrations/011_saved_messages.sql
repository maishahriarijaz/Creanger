-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 011: SAVED MESSAGES
-- ============================================================================
-- This migration creates the saved messages table:
-- saved_messages
-- ============================================================================

-- ============================================================================
-- SAVED_MESSAGES TABLE (Users saved/bookmarked messages)
-- ============================================================================

CREATE TABLE saved_messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_message_id UUID REFERENCES messages(id) ON DELETE SET NULL, -- Original message (if from chat)
    source_chat_id UUID REFERENCES chats(id) ON DELETE SET NULL, -- Original chat
    content TEXT NOT NULL, -- Saved content text
    media_id UUID REFERENCES media(id) ON DELETE SET NULL, -- Optional media
    caption TEXT,
    saved_from_user_id UUID REFERENCES users(id) ON DELETE SET NULL, -- Original sender
    is_media_only BOOLEAN NOT NULL DEFAULT FALSE, -- Flag for media-only saves
    note TEXT, -- User annotation/note
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT saved_messages_content_not_empty CHECK (content <> ''),
    CONSTRAINT saved_messages_note_length CHECK (note IS NULL OR char_length(note) <= 500)
);

COMMENT ON TABLE saved_messages IS 'User saved/bookmarked messages (Creanger-native)';
COMMENT ON COLUMN saved_messages.source_message_id IS 'Original message if from chat (NULL for manual saves)';
COMMENT ON COLUMN saved_messages.content IS 'Saved text content (denormalized for quick display)';
COMMENT ON COLUMN saved_messages.is_media_only IS 'TRUE if this is a media-only save (no text)';
COMMENT ON COLUMN saved_messages.note IS 'User annotation or note about this save';

CREATE INDEX idx_saved_messages_user_id ON saved_messages(user_id, created_at DESC);
CREATE INDEX idx_saved_messages_source_message ON saved_messages(source_message_id);
CREATE INDEX idx_saved_messages_created_at ON saved_messages(created_at DESC);

CREATE TRIGGER update_saved_messages_updated_at
    BEFORE UPDATE ON saved_messages
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- RLS POLICIES
-- ============================================================================

ALTER TABLE saved_messages ENABLE ROW LEVEL SECURITY;

-- Saved Messages: Users can only read/manage their own saved messages
CREATE POLICY saved_messages_select_own ON saved_messages
    FOR SELECT USING (user_id = current_user_id());
CREATE POLICY saved_messages_insert_own ON saved_messages
    FOR INSERT WITH CHECK (user_id = current_user_id());
CREATE POLICY saved_messages_update_own ON saved_messages
    FOR UPDATE USING (user_id = current_user_id());
CREATE POLICY saved_messages_delete_own ON saved_messages
    FOR DELETE USING (user_id = current_user_id());

-- ============================================================================
-- GRANTS
-- ============================================================================

GRANT SELECT, INSERT, UPDATE, DELETE ON saved_messages TO authenticated;
GRANT ALL ON saved_messages TO service_role;