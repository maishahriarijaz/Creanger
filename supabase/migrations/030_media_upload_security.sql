-- ============================================================================
-- CREANGER SUPABASE SCHEMA - MIGRATION 030: MEDIA UPLOAD SECURITY (ImageBB)
-- ============================================================================
-- Media message SEND (Phase 4) stores image bytes in ImageBB, not in a
-- Supabase Storage bucket. This migration is the replacement for the earlier
-- "chat media bucket" migration (030_chat_media_bucket.sql), which is REMOVED:
-- a Supabase Storage bucket is NOT the active image provider, and the dead
-- public-read/authenticated-upload storage policies must not linger.
--
-- Intended architecture (the ONLY image upload path):
--
--   Android  --Bearer user JWT + raw bytes-->  /functions/v1/upload-image
--        (Supabase Edge Function: authenticate -> validate size/type ->
--         upload to ImageBB with server-side IMAGEBB_API_KEY -> validate the
--         ImageBB response) -> provider-neutral UploadedMedia
--   Android  --UploadedMedia metadata-->  send_media_message (migration 029)
--        --MediaAttachment.publicUrl-->  existing Telegram image renderer
--
-- The ImageBB API key lives ONLY in the Edge Function environment (secret),
-- never in the Android APK and never in a database table. Android depends on
-- no ImageBB JSON and calls no ImageBB endpoint.
--
-- Provider-neutrality: the media metadata model (005) already carries an
-- 'imagebb' storage_provider_type value; a future video/audio provider
-- (Cloudinary, ...) is added server-side behind the same UploadedMedia
-- contract. The client-facing shape stays unchanged.
--
-- This migration therefore only adds the server-side integrity guarantee the
-- new architecture relies on: an ImageBB media row must always carry a stable
-- public URL (ImageBB images are public), so a send can never persist a media
-- row whose provider-neutral result was incomplete.
-- ============================================================================

ALTER TABLE public.media
    DROP CONSTRAINT IF EXISTS media_imagebb_requires_public_url;

-- ImageBB uploads are public by design: the provider-neutral result always
-- carries public_url, and MediaAttachment.publicUrl is what the Telegram
-- renderer loads. A row that claims ImageBB without a public URL is a broken
-- provider-neutral result and must never be persisted.
ALTER TABLE public.media
    ADD CONSTRAINT media_imagebb_requires_public_url
    CHECK (storage_provider <> 'imagebb' OR (public_url IS NOT NULL AND btrim(public_url) <> ''));

COMMENT ON CONSTRAINT media_imagebb_requires_public_url ON public.media IS
    'ImageBB media rows must carry a non-empty public_url (the provider-neutral upload result is the only source of MediaAttachment.publicUrl).';
