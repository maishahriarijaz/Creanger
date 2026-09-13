-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 007: STORIES
-- ============================================================================
-- This migration creates the stories tables:
-- stories, story_views, story_reactions, story_custom_audience
--
-- NOTE: The contacts feature has been removed from Creanger product scope.
-- story_privacy no longer has a 'contacts' tier; the RLS policy below only
-- resolves visibility via 'everyone', 'custom' (story_custom_audience), or
-- the story owner. 'close_friends' remains a placeholder enum value with no
-- dedicated audience table/RLS branch (see audit report).
-- ============================================================================

-- ============================================================================
-- ENUM TYPES
-- ============================================================================

CREATE TYPE story_privacy AS ENUM (
    'everyone',
    'close_friends',
    'nobody',
    'custom'
);

-- ============================================================================
-- STORIES TABLE (User stories - ephemeral content)
-- ============================================================================

CREATE TABLE stories (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_id UUID NOT NULL REFERENCES media(id) ON DELETE CASCADE,
    caption TEXT,
    privacy story_privacy NOT NULL DEFAULT 'everyone',
    is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
    view_count INTEGER NOT NULL DEFAULT 0,
    reaction_count INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '24 hours',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    metadata JSONB DEFAULT '{}',
    CONSTRAINT stories_caption_length CHECK (caption IS NULL OR char_length(caption) <= 1000),
    CONSTRAINT stories_view_count_nonneg CHECK (view_count >= 0),
    CONSTRAINT stories_reaction_count_nonneg CHECK (reaction_count >= 0),
    CONSTRAINT stories_expires_future CHECK (expires_at > created_at)
);

COMMENT ON TABLE stories IS 'User stories - ephemeral content with expiration';
COMMENT ON COLUMN stories.media_id IS 'Media attached to the story';
COMMENT ON COLUMN stories.privacy IS 'Who can view this story';
COMMENT ON COLUMN stories.is_pinned IS 'Whether story is pinned (pinned stories dont expire)';
COMMENT ON COLUMN stories.expires_at IS 'When story becomes unavailable (auto-expire)';

CREATE INDEX idx_stories_user_id ON stories(user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_stories_expires_at ON stories(expires_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_stories_privacy ON stories(privacy) WHERE deleted_at IS NULL;
CREATE INDEX idx_stories_created_at ON stories(created_at DESC) WHERE deleted_at IS NULL;

-- ============================================================================
-- STORY_VIEWS TABLE (Track who viewed a story)
-- ============================================================================

CREATE TABLE story_views (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    story_id UUID NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    viewer_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    viewed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT story_views_unique UNIQUE (story_id, viewer_id)
);

COMMENT ON TABLE story_views IS 'Track who viewed each story';
COMMENT ON COLUMN story_views.viewer_id IS 'User who viewed the story';
COMMENT ON COLUMN story_views.viewed_at IS 'When the story was viewed';

CREATE INDEX idx_story_views_story_id ON story_views(story_id);
CREATE INDEX idx_story_views_viewer_id ON story_views(viewer_id);
CREATE INDEX idx_story_views_viewed_at ON story_views(viewed_at);

-- ============================================================================
-- STORY_REACTIONS TABLE (Reactions to stories)
-- ============================================================================

CREATE TABLE story_reactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    story_id UUID NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reaction TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT story_reactions_unique UNIQUE (story_id, user_id),
    CONSTRAINT story_reactions_reaction_not_empty CHECK (reaction <> '')
);

COMMENT ON TABLE story_reactions IS 'Reactions to stories';
COMMENT ON COLUMN story_reactions.reaction IS 'Reaction emoji';

CREATE INDEX idx_story_reactions_story_id ON story_reactions(story_id);
CREATE INDEX idx_story_reactions_user_id ON story_reactions(user_id);

CREATE TRIGGER update_story_reactions_updated_at
    BEFORE UPDATE ON story_reactions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- STORY_CUSTOM_AUDIENCE TABLE (Custom privacy audience for stories)
-- ============================================================================

CREATE TABLE story_custom_audience (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    story_id UUID NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT story_custom_audience_unique UNIQUE (story_id, user_id)
);

COMMENT ON TABLE story_custom_audience IS 'Custom audience for stories (explicit allow list)';

CREATE INDEX idx_story_custom_audience_story_id ON story_custom_audience(story_id);
CREATE INDEX idx_story_custom_audience_user_id ON story_custom_audience(user_id);

-- ============================================================================
-- RLS POLICIES
-- ============================================================================

ALTER TABLE stories ENABLE ROW LEVEL SECURITY;
ALTER TABLE story_views ENABLE ROW LEVEL SECURITY;
ALTER TABLE story_reactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE story_custom_audience ENABLE ROW LEVEL SECURITY;

-- Stories: Based on privacy settings
CREATE POLICY stories_select_visible ON stories
    FOR SELECT USING (
        user_id = current_user_id()
        OR (
            privacy = 'everyone'
            AND deleted_at IS NULL
            AND (expires_at > NOW() OR is_pinned = TRUE)
        )
        OR (
            privacy = 'custom'
            AND user_id IN (
                SELECT user_id FROM story_custom_audience WHERE story_id = stories.id
            )
            AND deleted_at IS NULL
            AND (expires_at > NOW() OR is_pinned = TRUE)
        )
    );
CREATE POLICY stories_insert_owner ON stories
    FOR INSERT WITH CHECK (user_id = current_user_id());
CREATE POLICY stories_update_owner ON stories
    FOR UPDATE USING (user_id = current_user_id());
CREATE POLICY stories_delete_owner ON stories
    FOR DELETE USING (user_id = current_user_id());

-- Story Views: Story owner can see views; viewer can see their own
CREATE POLICY story_views_select_owner_or_viewer ON story_views
    FOR SELECT USING (
        viewer_id = current_user_id()
        OR story_id IN (
            SELECT id FROM stories WHERE user_id = current_user_id()
        )
    );
CREATE POLICY story_views_insert_viewer ON story_views
    FOR INSERT WITH CHECK (viewer_id = current_user_id());

-- Story Reactions: Story owner can see reactions; reactor can see own
CREATE POLICY story_reactions_select_visible ON story_reactions
    FOR SELECT USING (
        user_id = current_user_id()
        OR story_id IN (
            SELECT id FROM stories WHERE user_id = current_user_id()
        )
    );
CREATE POLICY story_reactions_insert_own ON story_reactions
    FOR INSERT WITH CHECK (user_id = current_user_id());
CREATE POLICY story_reactions_update_own ON story_reactions
    FOR UPDATE USING (user_id = current_user_id());
CREATE POLICY story_reactions_delete_own ON story_reactions
    FOR DELETE USING (user_id = current_user_id());

-- Story Custom Audience: Story owner can manage
CREATE POLICY story_custom_audience_select_owner ON story_custom_audience
    FOR SELECT USING (
        story_id IN (
            SELECT id FROM stories WHERE user_id = current_user_id()
        )
    );
CREATE POLICY story_custom_audience_insert_owner ON story_custom_audience
    FOR INSERT WITH CHECK (
        story_id IN (
            SELECT id FROM stories WHERE user_id = current_user_id()
        )
    );
CREATE POLICY story_custom_audience_delete_owner ON story_custom_audience
    FOR DELETE USING (
        story_id IN (
            SELECT id FROM stories WHERE user_id = current_user_id()
        )
    );

-- ============================================================================
-- GRANTS
-- ============================================================================

GRANT SELECT ON stories TO authenticated;
GRANT INSERT, UPDATE, DELETE ON stories TO authenticated;
GRANT SELECT ON story_views TO authenticated;
GRANT INSERT ON story_views TO authenticated;
GRANT SELECT ON story_reactions TO authenticated;
GRANT INSERT, UPDATE, DELETE ON story_reactions TO authenticated;
GRANT SELECT ON story_custom_audience TO authenticated;
GRANT INSERT, DELETE ON story_custom_audience TO authenticated;

GRANT ALL ON stories TO service_role;
GRANT ALL ON story_views TO service_role;
GRANT ALL ON story_reactions TO service_role;
GRANT ALL ON story_custom_audience TO service_role;