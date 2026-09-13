-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 005: MEDIA AND POLLS
-- ============================================================================
-- This migration creates the media and poll tables:
-- media, message_attachments, polls, poll_options, poll_votes
-- ============================================================================

-- ============================================================================
-- ENUM TYPES
-- ============================================================================

CREATE TYPE storage_provider_type AS ENUM (
    'cloudinary',
    'imagebb',
    's3',
    'local',
    'other'
);

-- ============================================================================
-- MEDIA TABLE (Media metadata - actual files in external storage)
-- ============================================================================

CREATE TABLE media (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    storage_provider storage_provider_type NOT NULL,
    storage_key TEXT NOT NULL, -- Provider-specific key/path
    public_url TEXT, -- Public URL for direct access (if supported)
    delivery_url TEXT, -- Secure delivery URL (signed/authenticated)
    mime_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    checksum TEXT, -- SHA-256 hash for integrity verification
    width INTEGER, -- Width in pixels (images/videos)
    height INTEGER, -- Height in pixels (images/videos)
    duration INTEGER, -- Duration in milliseconds (audio/video)
    thumbnail_media_id UUID REFERENCES media(id) ON DELETE SET NULL, -- Self-referential FK for thumbnail
    metadata JSONB DEFAULT '{}', -- Flexible metadata (codec, bitrate, etc.)
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT media_size_nonneg CHECK (size_bytes >= 0),
    CONSTRAINT media_dimensions_nonneg CHECK (
        (width IS NULL OR width >= 0) AND (height IS NULL OR height >= 0)
    ),
    CONSTRAINT media_duration_nonneg CHECK (duration IS NULL OR duration >= 0),
    CONSTRAINT media_mime_not_empty CHECK (mime_type <> ''),
    CONSTRAINT media_storage_key_not_empty CHECK (storage_key <> '')
);

COMMENT ON TABLE media IS 'Media metadata only - actual binary files remain in external storage';
COMMENT ON COLUMN media.storage_provider IS 'Where the file is stored (cloudinary, imagebb, s3, etc.)';
COMMENT ON COLUMN media.storage_key IS 'Provider-specific key/path to retrieve the file';
COMMENT ON COLUMN media.public_url IS 'Public URL for direct access (if provider supports it)';
COMMENT ON COLUMN media.delivery_url IS 'Secure delivery URL for authenticated access';
COMMENT ON COLUMN media.checksum IS 'SHA-256 hash for integrity verification';
COMMENT ON COLUMN media.thumbnail_media_id IS 'Self-referential FK to this table for thumbnail image';

CREATE INDEX idx_media_owner_id ON media(owner_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_media_checksum ON media(checksum) WHERE checksum IS NOT NULL;
CREATE INDEX idx_media_storage_provider ON media(storage_provider) WHERE deleted_at IS NULL;
CREATE INDEX idx_media_mime_type ON media(mime_type) WHERE deleted_at IS NULL;
CREATE INDEX idx_media_created_at ON media(created_at DESC) WHERE deleted_at IS NULL;

-- Now add the FK from profiles to media
ALTER TABLE profiles ADD CONSTRAINT fk_profiles_avatar_media 
    FOREIGN KEY (avatar_media_id) REFERENCES media(id) ON DELETE SET NULL;

-- Now add the FK from chats to media
ALTER TABLE chats ADD CONSTRAINT fk_chats_avatar_media 
    FOREIGN KEY (avatar_media_id) REFERENCES media(id) ON DELETE SET NULL;

-- ============================================================================
-- MESSAGE_ATTACHMENTS TABLE (Link between messages and media)
-- ============================================================================

CREATE TABLE message_attachments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    media_id UUID NOT NULL REFERENCES media(id) ON DELETE CASCADE,
    position INTEGER NOT NULL DEFAULT 0, -- Position in multi-attachment message
    caption TEXT, -- Caption specific to this attachment
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT message_attachments_unique UNIQUE (message_id, media_id),
    CONSTRAINT message_attachments_position_nonneg CHECK (position >= 0)
);

COMMENT ON TABLE message_attachments IS 'Links messages to their media attachments';
COMMENT ON COLUMN message_attachments.position IS 'Order of attachment in multi-attachment message (0-indexed)';
COMMENT ON COLUMN message_attachments.caption IS 'Caption specific to this attachment';

CREATE INDEX idx_message_attachments_message_id ON message_attachments(message_id);
CREATE INDEX idx_message_attachments_media_id ON message_attachments(media_id);

-- ============================================================================
-- POLLS TABLE (Poll metadata)
-- ============================================================================

CREATE TABLE polls (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    question TEXT NOT NULL,
    description TEXT,
    allows_multiple BOOLEAN NOT NULL DEFAULT FALSE,
    is_anonymous BOOLEAN NOT NULL DEFAULT TRUE,
    is_quiz BOOLEAN NOT NULL DEFAULT FALSE,
    correct_option_id UUID, -- FK to poll_options (set after options created)
    closes_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT polls_question_not_empty CHECK (question <> ''),
    CONSTRAINT polls_question_length CHECK (char_length(question) <= 300),
    CONSTRAINT polls_description_length CHECK (description IS NULL OR char_length(description) <= 255),
    CONSTRAINT polls_closes_future CHECK (closes_at IS NULL OR closes_at > created_at),
    CONSTRAINT polls_closed_after_created CHECK (closed_at IS NULL OR closed_at >= created_at)
);

COMMENT ON TABLE polls IS 'Poll metadata - attached to messages';
COMMENT ON COLUMN polls.message_id IS 'Message this poll is attached to';
COMMENT ON COLUMN polls.allows_multiple IS 'Whether users can select multiple options';
COMMENT ON COLUMN polls.is_anonymous IS 'Whether votes are anonymous';
COMMENT ON COLUMN polls.is_quiz IS 'Whether this is a quiz (has correct answer)';
COMMENT ON COLUMN polls.correct_option_id IS 'Correct option for quiz mode';
COMMENT ON COLUMN polls.closes_at IS 'When voting closes (NULL = never)';

CREATE INDEX idx_polls_message_id ON polls(message_id);
CREATE INDEX idx_polls_closes_at ON polls(closes_at) WHERE closes_at IS NOT NULL AND closed_at IS NULL;

CREATE TRIGGER update_polls_updated_at
    BEFORE UPDATE ON polls
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- POLL_OPTIONS TABLE (Individual poll choices)
-- ============================================================================

CREATE TABLE poll_options (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    poll_id UUID NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    text TEXT NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT poll_options_text_not_empty CHECK (text <> ''),
    CONSTRAINT poll_options_text_length CHECK (char_length(text) <= 100),
    CONSTRAINT poll_options_unique_position UNIQUE (poll_id, position)
);

COMMENT ON TABLE poll_options IS 'Individual poll choice options';
COMMENT ON COLUMN poll_options.text IS 'Option text displayed to users';
COMMENT ON COLUMN poll_options.position IS 'Order of option in poll (0-indexed)';

CREATE INDEX idx_poll_options_poll_id ON poll_options(poll_id);

-- Now add the FK from polls to poll_options
ALTER TABLE polls ADD CONSTRAINT fk_polls_correct_option 
    FOREIGN KEY (correct_option_id) REFERENCES poll_options(id) ON DELETE SET NULL;

-- ============================================================================
-- POLL_VOTES TABLE (User votes on poll options)
-- ============================================================================

CREATE TABLE poll_votes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    poll_id UUID NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    option_id UUID NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT poll_votes_unique_vote UNIQUE (poll_id, user_id, option_id)
);

COMMENT ON TABLE poll_votes IS 'User votes on poll options';
COMMENT ON COLUMN poll_votes.option_id IS 'Option the user voted for';

CREATE INDEX idx_poll_votes_poll_id ON poll_votes(poll_id);
CREATE INDEX idx_poll_votes_option_id ON poll_votes(option_id);
CREATE INDEX idx_poll_votes_user_id ON poll_votes(user_id);

-- Function to get vote count for a poll option
CREATE OR REPLACE FUNCTION get_poll_option_vote_count(p_option_id UUID)
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM poll_votes WHERE option_id = p_option_id;
    RETURN v_count;
END;
$$ LANGUAGE plpgsql STABLE;

-- ============================================================================
-- RLS POLICIES
-- ============================================================================

ALTER TABLE media ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE polls ENABLE ROW LEVEL SECURITY;
ALTER TABLE poll_options ENABLE ROW LEVEL SECURITY;
ALTER TABLE poll_votes ENABLE ROW LEVEL SECURITY;

-- Media: Owners can read/manage their own media
CREATE POLICY media_select_owner ON media
    FOR SELECT USING (
        owner_id = current_user_id()
        OR id IN (SELECT media_id FROM message_attachments WHERE message_id IN (
            SELECT id FROM messages WHERE chat_id IN (
                SELECT chat_id FROM chat_members WHERE user_id = current_user_id() AND left_at IS NULL
            )
        ))
    );
CREATE POLICY media_insert_owner ON media
    FOR INSERT WITH CHECK (owner_id = current_user_id());
CREATE POLICY media_update_owner ON media
    FOR UPDATE USING (owner_id = current_user_id());
CREATE POLICY media_delete_owner ON media
    FOR DELETE USING (owner_id = current_user_id());

-- Message Attachments: Chat members can read
CREATE POLICY message_attachments_select_members ON message_attachments
    FOR SELECT USING (
        message_id IN (
            SELECT id FROM messages WHERE chat_id IN (
                SELECT chat_id FROM chat_members WHERE user_id = current_user_id() AND left_at IS NULL
            )
        )
    );
CREATE POLICY message_attachments_insert_sender ON message_attachments
    FOR INSERT WITH CHECK (
        message_id IN (
            SELECT id FROM messages WHERE sender_id = current_user_id()
        )
    );
CREATE POLICY message_attachments_delete_sender ON message_attachments
    FOR DELETE USING (
        message_id IN (
            SELECT id FROM messages WHERE sender_id = current_user_id()
        )
    );

-- Polls: Chat members can read
CREATE POLICY polls_select_members ON polls
    FOR SELECT USING (
        message_id IN (
            SELECT id FROM messages WHERE chat_id IN (
                SELECT chat_id FROM chat_members WHERE user_id = current_user_id() AND left_at IS NULL
            )
        )
    );
CREATE POLICY polls_insert_creator ON polls
    FOR INSERT WITH CHECK (
        message_id IN (
            SELECT id FROM messages WHERE sender_id = current_user_id()
        )
    );
CREATE POLICY polls_update_creator ON polls
    FOR UPDATE USING (
        message_id IN (
            SELECT id FROM messages WHERE sender_id = current_user_id()
        )
    );

-- Poll Options: Chat members can read; creator can manage
CREATE POLICY poll_options_select_members ON poll_options
    FOR SELECT USING (
        poll_id IN (
            SELECT id FROM polls WHERE message_id IN (
                SELECT id FROM messages WHERE chat_id IN (
                    SELECT chat_id FROM chat_members WHERE user_id = current_user_id() AND left_at IS NULL
                )
            )
        )
    );
CREATE POLICY poll_options_insert_creator ON poll_options
    FOR INSERT WITH CHECK (
        poll_id IN (
            SELECT id FROM polls WHERE message_id IN (
                SELECT id FROM messages WHERE sender_id = current_user_id()
            )
        )
    );
CREATE POLICY poll_options_delete_creator ON poll_options
    FOR DELETE USING (
        poll_id IN (
            SELECT id FROM polls WHERE message_id IN (
                SELECT id FROM messages WHERE sender_id = current_user_id()
            )
        )
    );

-- Poll Votes: Users can see votes (if anonymous) or own vote
CREATE POLICY poll_votes_select_all ON poll_votes
    FOR SELECT USING (TRUE);
CREATE POLICY poll_votes_insert_own ON poll_votes
    FOR INSERT WITH CHECK (user_id = current_user_id());
CREATE POLICY poll_votes_delete_own ON poll_votes
    FOR DELETE USING (user_id = current_user_id());

-- ============================================================================
-- GRANTS
-- ============================================================================

GRANT SELECT ON media TO authenticated;
GRANT INSERT, UPDATE, DELETE ON media TO authenticated;
GRANT SELECT ON message_attachments TO authenticated;
GRANT INSERT, DELETE ON message_attachments TO authenticated;
GRANT SELECT ON polls TO authenticated;
GRANT INSERT, UPDATE ON polls TO authenticated;
GRANT SELECT ON poll_options TO authenticated;
GRANT INSERT, DELETE ON poll_options TO authenticated;
GRANT SELECT, INSERT, DELETE ON poll_votes TO authenticated;

GRANT ALL ON media TO service_role;
GRANT ALL ON message_attachments TO service_role;
GRANT ALL ON polls TO service_role;
GRANT ALL ON poll_options TO service_role;
GRANT ALL ON poll_votes TO service_role;