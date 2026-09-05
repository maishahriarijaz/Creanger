-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 006: DRAFTS AND STICKERS
-- ============================================================================
-- This migration creates the drafts and sticker tables:
-- drafts, sticker_sets, stickers, user_sticker_sets
-- ============================================================================

-- ============================================================================
-- ENUM TYPES
-- ============================================================================

CREATE TYPE sticker_type AS ENUM (
    'static',
    'animated',
    'video'
);

CREATE TYPE sticker_set_type AS ENUM (
    'regular',
    'animated',
    'video',
    'custom_emoji'
);

-- ============================================================================
-- DRAFTS TABLE (Message drafts - server sync for multi-device)
-- ============================================================================

CREATE TABLE drafts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    topic_id UUID REFERENCES topics(id) ON DELETE SET NULL,
    content TEXT,
    reply_to_message_id UUID REFERENCES messages(id) ON DELETE SET NULL,
    metadata JSONB DEFAULT '{}', -- Flexible draft metadata
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT drafts_unique_chat UNIQUE (user_id, chat_id, topic_id),
    CONSTRAINT drafts_topic_requires_chat CHECK (
        topic_id IS NULL OR chat_id IS NOT NULL
    )
);

COMMENT ON TABLE drafts IS 'Message drafts - synced across devices';
COMMENT ON COLUMN drafts.content IS 'Draft text content';
COMMENT ON COLUMN drafts.reply_to_message_id IS 'Message being replied to';
COMMENT ON COLUMN drafts.topic_id IS 'Topic for forum drafts (NULL for main chat)';

CREATE INDEX idx_drafts_user_id ON drafts(user_id);
CREATE INDEX idx_drafts_updated_at ON drafts(updated_at);

CREATE TRIGGER update_drafts_updated_at
    BEFORE UPDATE ON drafts
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- STICKER_SETS TABLE (Sticker pack metadata)
-- ============================================================================

CREATE TABLE sticker_sets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL,
    title TEXT NOT NULL,
    short_name CITEXT UNIQUE,
    type sticker_set_type NOT NULL DEFAULT 'regular',
    author_id UUID REFERENCES users(id) ON DELETE SET NULL,
    thumbnail_media_id UUID REFERENCES media(id) ON DELETE SET NULL,
    is_animated BOOLEAN NOT NULL DEFAULT FALSE,
    is_video BOOLEAN NOT NULL DEFAULT FALSE,
    is_official BOOLEAN NOT NULL DEFAULT FALSE, -- Official Creanger sticker sets
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    install_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT sticker_sets_name_not_empty CHECK (name <> ''),
    CONSTRAINT sticker_sets_title_not_empty CHECK (title <> ''),
    CONSTRAINT sticker_sets_install_count_nonneg CHECK (install_count >= 0)
);

COMMENT ON TABLE sticker_sets IS 'Sticker pack metadata';
COMMENT ON COLUMN sticker_sets.name IS 'Internal name (unique identifier)';
COMMENT ON COLUMN sticker_sets.title IS 'Display title';
COMMENT ON COLUMN sticker_sets.short_name IS 'Short URL-friendly name';
COMMENT ON COLUMN sticker_sets.is_official IS 'Official Creanger sticker set';

CREATE INDEX idx_sticker_sets_author ON sticker_sets(author_id);
CREATE INDEX idx_sticker_sets_type ON sticker_sets(type);
CREATE INDEX idx_sticker_sets_install_count ON sticker_sets(install_count DESC) WHERE is_official = TRUE;
CREATE INDEX idx_sticker_sets_created_at ON sticker_sets(created_at DESC);

CREATE TRIGGER update_sticker_sets_updated_at
    BEFORE UPDATE ON sticker_sets
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- STICKERS TABLE (Individual stickers within sets)
-- ============================================================================

CREATE TABLE stickers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    set_id UUID NOT NULL REFERENCES sticker_sets(id) ON DELETE CASCADE,
    media_id UUID NOT NULL REFERENCES media(id) ON DELETE CASCADE,
    emoji TEXT NOT NULL, -- Emoji associated with this sticker
    keywords TEXT[], -- Search keywords for this sticker
    position INTEGER NOT NULL DEFAULT 0,
    type sticker_type NOT NULL DEFAULT 'static',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT stickers_emoji_not_empty CHECK (emoji <> ''),
    CONSTRAINT stickers_position_nonneg CHECK (position >= 0)
);

COMMENT ON TABLE stickers IS 'Individual stickers within a sticker set';
COMMENT ON COLUMN stickers.emoji IS 'Emoji associated with this sticker';
COMMENT ON COLUMN stickers.keywords IS 'Search keywords for discovery';
COMMENT ON COLUMN stickers.position IS 'Order within the set';

CREATE INDEX idx_stickers_set_id ON stickers(set_id);
CREATE INDEX idx_stickers_emoji ON stickers(emoji);
CREATE INDEX idx_stickers_type ON stickers(type);

-- ============================================================================
-- USER_STICKER_SETS TABLE (User's installed sticker sets)
-- ============================================================================

CREATE TABLE user_sticker_sets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    set_id UUID NOT NULL REFERENCES sticker_sets(id) ON DELETE CASCADE,
    is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
    installed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT user_sticker_sets_unique UNIQUE (user_id, set_id)
);

COMMENT ON TABLE user_sticker_sets IS 'User installed sticker sets';
COMMENT ON COLUMN user_sticker_sets.is_favorite IS 'Whether set is favorited by user';

CREATE INDEX idx_user_sticker_sets_user_id ON user_sticker_sets(user_id);
CREATE INDEX idx_user_sticker_sets_set_id ON user_sticker_sets(set_id);
CREATE INDEX idx_user_sticker_sets_favorite ON user_sticker_sets(user_id) WHERE is_favorite = TRUE;

CREATE TRIGGER update_user_sticker_sets_updated_at
    BEFORE UPDATE ON user_sticker_sets
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- RLS POLICIES
-- ============================================================================

ALTER TABLE drafts ENABLE ROW LEVEL SECURITY;
ALTER TABLE sticker_sets ENABLE ROW LEVEL SECURITY;
ALTER TABLE stickers ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_sticker_sets ENABLE ROW LEVEL SECURITY;

-- Drafts: Users can read/manage their own drafts
CREATE POLICY drafts_select_own ON drafts
    FOR SELECT USING (user_id = current_user_id());
CREATE POLICY drafts_insert_own ON drafts
    FOR INSERT WITH CHECK (user_id = current_user_id());
CREATE POLICY drafts_update_own ON drafts
    FOR UPDATE USING (user_id = current_user_id());
CREATE POLICY drafts_delete_own ON drafts
    FOR DELETE USING (user_id = current_user_id());

-- Sticker Sets: Public sticker sets visible to all; author can manage
CREATE POLICY sticker_sets_select_all ON sticker_sets
    FOR SELECT USING (is_archived = FALSE OR author_id = current_user_id());
CREATE POLICY sticker_sets_insert_author ON sticker_sets
    FOR INSERT WITH CHECK (author_id = current_user_id());
CREATE POLICY sticker_sets_update_author ON sticker_sets
    FOR UPDATE USING (author_id = current_user_id());

-- Stickers: Visible if parent set is visible
CREATE POLICY stickers_select_all ON stickers
    FOR SELECT USING (
        set_id IN (
            SELECT id FROM sticker_sets WHERE is_archived = FALSE
        )
    );
CREATE POLICY stickers_insert_author ON stickers
    FOR INSERT WITH CHECK (
        set_id IN (
            SELECT id FROM sticker_sets WHERE author_id = current_user_id()
        )
    );
CREATE POLICY stickers_update_author ON stickers
    FOR UPDATE USING (
        set_id IN (
            SELECT id FROM sticker_sets WHERE author_id = current_user_id()
        )
    );

-- User Sticker Sets: Users can read/manage their own
CREATE POLICY user_sticker_sets_select_own ON user_sticker_sets
    FOR SELECT USING (user_id = current_user_id());
CREATE POLICY user_sticker_sets_insert_own ON user_sticker_sets
    FOR INSERT WITH CHECK (user_id = current_user_id());
CREATE POLICY user_sticker_sets_update_own ON user_sticker_sets
    FOR UPDATE USING (user_id = current_user_id());
CREATE POLICY user_sticker_sets_delete_own ON user_sticker_sets
    FOR DELETE USING (user_id = current_user_id());

-- ============================================================================
-- GRANTS
-- ============================================================================

GRANT SELECT, INSERT, UPDATE, DELETE ON drafts TO authenticated;
GRANT SELECT ON sticker_sets TO authenticated;
GRANT INSERT, UPDATE ON sticker_sets TO authenticated;
GRANT SELECT ON stickers TO authenticated;
GRANT INSERT, UPDATE ON stickers TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON user_sticker_sets TO authenticated;

GRANT ALL ON drafts TO service_role;
GRANT ALL ON sticker_sets TO service_role;
GRANT ALL ON stickers TO service_role;
GRANT ALL ON user_sticker_sets TO service_role;