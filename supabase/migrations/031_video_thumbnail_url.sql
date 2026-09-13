-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 031: VIDEO THUMBNAIL URL
-- ============================================================================
-- Adds thumbnail_url to the media table and extends send_media_message
-- to accept thumbnail_url for video attachments.
--
-- This supports the Cloudinary video provider architecture where the
-- Edge Function uploads the video to Cloudinary, generates a thumbnail
-- (poster), and returns both the video URL and thumbnail URL in the
-- provider-neutral UploadedMedia result.
-- ============================================================================

-- Add thumbnail_url column to media table
ALTER TABLE public.media
    ADD COLUMN IF NOT EXISTS thumbnail_url TEXT;

COMMENT ON COLUMN media.thumbnail_url IS
    'Thumbnail/poster URL for video (provider-neutral). Set by the upload Edge Function for video uploads via Cloudinary.';

-- Update send_media_message RPC to accept thumbnail_url in the attachment payload
CREATE OR REPLACE FUNCTION public.send_media_message(
    p_chat_id UUID,
    p_client_message_id TEXT,
    p_message_type public.message_type,
    p_caption TEXT DEFAULT NULL,
    p_reply_to_message_id UUID DEFAULT NULL,
    p_attachments JSONB DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := public.current_user_id();
    v_id UUID;
    v_media_id UUID;
    v_att JSONB;
    v_content TEXT;
    v_position INTEGER := 0;
    v_count INTEGER;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'not authenticated';
    END IF;

    IF p_chat_id IS NULL OR p_client_message_id IS NULL THEN
        RAISE EXCEPTION 'chat_id and client_message_id are required for idempotent send';
    END IF;

    -- Gate: only an active chat member may send into the chat.
    IF NOT public.is_chat_member(p_chat_id) THEN
        RAISE EXCEPTION 'not a member of this chat';
    END IF;

    -- Gate: this RPC only handles the media message types. Everything else
    -- (send_text_message for text, future phases for sticker/poll/system/
    -- location) is out of scope and is rejected outright.
    IF p_message_type NOT IN ('image', 'video', 'document', 'audio', 'voice') THEN
        RAISE EXCEPTION 'unsupported message_type for media send';
    END IF;

    -- The messages_content_not_empty check requires content NOT NULL for
    -- text/document rows; a document with no caption therefore gets ''.
    v_content := p_caption;
    IF p_message_type = 'document' AND v_content IS NULL THEN
        v_content := '';
    END IF;

    IF p_attachments IS NULL OR jsonb_typeof(p_attachments) <> 'array'
            OR jsonb_array_length(p_attachments) = 0 THEN
        RAISE EXCEPTION 'media message requires at least one attachment';
    END IF;

    v_count := jsonb_array_length(p_attachments);
    IF v_count > 10 THEN
        RAISE EXCEPTION 'at most 10 attachments per media message';
    END IF;

    -- Idempotent send: first write wins. A retry with the same client_message_id
    -- returns the ORIGINAL message (content/attachments are never rewritten).
    INSERT INTO public.messages (
        chat_id, sender_id, message_type, content, reply_to_message_id,
        client_message_id, status
    )
    VALUES (
        p_chat_id, v_user_id, p_message_type, v_content, p_reply_to_message_id,
        p_client_message_id, 'sent'
    )
    ON CONFLICT (chat_id, sender_id, client_message_id)
        WHERE client_message_id IS NOT NULL
    DO NOTHING
    RETURNING id INTO v_id;

    IF v_id IS NULL THEN
        SELECT id INTO v_id
        FROM public.messages
        WHERE chat_id = p_chat_id
          AND sender_id = v_user_id
          AND client_message_id = p_client_message_id;
        RETURN v_id;
    END IF;

    -- Atomic attachment persist: media + message_attachments rows. The
    -- storage_provider cast validates the provider enum; missing
    -- mime_type/storage_key are rejected explicitly (the storage_key is the
    -- only way to ever retrieve the file from the provider).
    FOR v_att IN SELECT * FROM jsonb_array_elements(p_attachments) LOOP
        IF v_att->>'storage_key' IS NULL OR btrim(v_att->>'storage_key') = '' THEN
            RAISE EXCEPTION 'attachment storage_key is required';
        END IF;
        IF v_att->>'mime_type' IS NULL OR btrim(v_att->>'mime_type') = '' THEN
            RAISE EXCEPTION 'attachment mime_type is required';
        END IF;

        INSERT INTO public.media (
            owner_id, storage_provider, storage_key, public_url, delivery_url,
            mime_type, size_bytes, checksum, width, height, duration, thumbnail_url
        )
        VALUES (
            v_user_id,
            (v_att->>'storage_provider')::public.storage_provider_type,
            v_att->>'storage_key',
            NULLIF(v_att->>'public_url', ''),
            NULLIF(v_att->>'delivery_url', ''),
            v_att->>'mime_type',
            COALESCE((v_att->>'size_bytes')::BIGINT, 0),
            NULLIF(v_att->>'checksum', ''),
            (v_att->>'width')::INTEGER,
            (v_att->>'height')::INTEGER,
            (v_att->>'duration')::INTEGER,
            NULLIF(v_att->>'thumbnail_url', '')
        )
        RETURNING id INTO v_media_id;

        IF v_att->>'position' IS NOT NULL THEN
            v_position := (v_att->>'position')::INTEGER;
        END IF;

        INSERT INTO public.message_attachments (message_id, media_id, position, caption)
        VALUES (v_id, v_media_id, v_position, NULLIF(v_att->>'caption', ''));

        v_position := v_position + 1;
    END LOOP;

    RETURN v_id;
END;
$$;

REVOKE ALL ON FUNCTION public.send_media_message(UUID, TEXT, public.message_type, TEXT, UUID, JSONB) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.send_media_message(UUID, TEXT, public.message_type, TEXT, UUID, JSONB) TO authenticated;