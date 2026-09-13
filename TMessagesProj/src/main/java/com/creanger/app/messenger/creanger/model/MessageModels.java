package com.creanger.app.messenger.creanger.model;

import com.creanger.app.tgnet.tl.TL_iv;
import com.creanger.app.tgnet.TLRPC;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.json.JSONException;

/**
 * Message foundation models mirrored from the Creanger Supabase schema
 * ({@code supabase/migrations/004_messages.sql} + 017 + 022). The PostgREST
 * REST data plane and its RPCs ({@code get_messages_since},
 * {@code send_text_message}) are the wire contract:
 *
 *  - deterministic per-chat ordering via the monotonic {@code chat_seq} column
 *    (assigned by {@code assign_chat_seq()}, unique per (chat_id, chat_seq))
 *  - idempotent send via {@code client_message_id} (one per chat+sender; a
 *    retry with the same id returns the original message — first write wins,
 *    per migration 022)
 *  - RLS scoping: only chat members see a chat's messages; a non-member simply
 *    receives an empty list (never rows)
 *
 * Identifiers are Creanger UUID strings, deliberately kept separate from
 * Telegram Long IDs (no TLRPC types leak in here).
 */
public final class MessageModels {

    private MessageModels() {
    }

    public static final class MessageType {
        // Mirrors the schema's message_type enum. Text is the base type; the
        // media set (image/video/document/audio/voice) is carried end to end by
        // the media data plane (migration 029 send_media_message RPC). The
        // remaining enum values (sticker/poll/system/location) exist server-side
        // but are not modeled/rendered by the client yet.
        public static final String TEXT = "text";
        public static final String IMAGE = "image";
        public static final String VIDEO = "video";
        public static final String DOCUMENT = "document";
        public static final String AUDIO = "audio";
        public static final String VOICE = "voice";

        /** True for the media types the data plane can send ({@code send_media_message}). */
        public static boolean isMediaType(String type) {
            return IMAGE.equals(type) || VIDEO.equals(type) || DOCUMENT.equals(type)
                    || AUDIO.equals(type) || VOICE.equals(type);
        }

        private MessageType() {
        }
    }

    public static final class MessageStatus {
        public static final String PENDING = "pending";
        public static final String SENT = "sent";
        public static final String DELIVERED = "delivered";
        public static final String READ = "read";
        public static final String FAILED = "failed";
        public static final String SCHEDULED = "scheduled";

        private MessageStatus() {
        }

        /**
         * Monotonic delivery rank: a status may only advance
         * {@code pending(0) < sent(1) < delivered(2) < read(3)}; never regress.
         * {@code failed}/{@code scheduled} are terminal clientside-only states
         * that rank below {@code sent} so they can still be advanced.
         */
        public static int rank(String status) {
            switch (status == null ? "" : status) {
                case DELIVERED:
                    return 2;
                case READ:
                    return 3;
                case SENT:
                    return 1;
                case PENDING:
                case FAILED:
                case SCHEDULED:
                default:
                    return 0;
            }
        }
    }

    /**
     * A recipient-applied delivery/read state change for one message (migration
     * 026). Returned by {@code get_message_statuses_since} after a Realtime
     * reconnect: status changes do not bump {@code chat_seq}, so they are
     * recovered by their {@code updated_at} watermark instead.
     */
    public static final class MessageStatusUpdate {
        public final String messageId;
        public final String status; // MessageStatus.DELIVERED / READ
        @Nullable
        public final String updatedAt;

        public MessageStatusUpdate(String messageId, String status, @Nullable String updatedAt) {
            this.messageId = messageId;
            this.status = status;
            this.updatedAt = updatedAt;
        }
    }

    /**
     * One reaction row ({@code message_reactions}, migration 004). Rows carry
     * {@code message_id} and {@code user_id} but NO {@code chat_id}: realtime
     * frames deliver them as-is and the repository verifies each {@code
     * message_id} belongs to the current chat's loaded window. The same
     * (message, user, reaction) is unique, so per-user distinct reactions are
     * the model's granularity (a user may hold SEVERAL reactions per message).
     */
    public static final class MessageReaction {
        public final String messageId;
        public final String userId;
        public final String reaction;
        public final boolean isCustomEmoji;
        @Nullable
        public final String customEmojiId;
        @Nullable
        public final String createdAt;
        @Nullable
        public final String updatedAt;

        public MessageReaction(String messageId, String userId, String reaction,
                               boolean isCustomEmoji, @Nullable String customEmojiId,
                               @Nullable String createdAt, @Nullable String updatedAt) {
            this.messageId = messageId;
            this.userId = userId;
            this.reaction = reaction;
            this.isCustomEmoji = isCustomEmoji;
            this.customEmojiId = customEmojiId;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    /**
     * A per-reaction aggregation for the chat UI: one row per distinct reaction
     * of a message, with {@code count} being the number of DISTINCT users who
     * reacted and {@code chosen} marking the current user's own reaction.
     * This is what drives Telegram's reaction chips (the adapter maps it onto
     * {@code TLRPC.ReactionCount}).
     */
    public static final class ReactionSummary {
        public final String reaction;
        public final boolean isCustomEmoji;
        @Nullable
        public final String customEmojiId;
        public final int count;
        public final boolean chosen;

        public ReactionSummary(String reaction, boolean isCustomEmoji,
                               @Nullable String customEmojiId, int count, boolean chosen) {
            this.reaction = reaction;
            this.isCustomEmoji = isCustomEmoji;
            this.customEmojiId = customEmojiId;
            this.count = count;
            this.chosen = chosen;
        }
    }

    /**
     * The result of uploading a media file's bytes through the Creanger
     * Media API ({@code /v1/media/upload-image}, which proxies to the
     * external provider server-side) — the exact metadata {@link MediaAttachment}
     * rows carry, produced by the upload step of a media send. The binary bytes
     * never touch PostgREST; only this description is later persisted via
     * {@code send_media_message} (migration 029). Provider-neutral: ImageBB
     * today, any future provider behind the same contract.
     */
    public static final class UploadedMedia {
        /** {@code storage_provider_type} value recorded for the uploaded object. */
        public final String storageProvider;
        /** Provider-specific key/path that retrieves the file. */
        public final String storageKey;
        /** Public URL for direct access (what {@code CreangerMessageMapping.imageSourceUrl} renders). */
        @Nullable
        public final String publicUrl;
        /** MIME type of the uploaded bytes. */
        public final String mimeType;
        /** Byte size of the uploaded file. */
        public final long sizeBytes;
        /** Secure/signed delivery URL (null for public providers such as ImageBB). */
        @Nullable
        public final String deliveryUrl;
        /** Thumbnail/poster URL for video preview (provider-neutral). */
        @Nullable
        public final String thumbnailUrl;
        /** Short preview/animated URL for video (provider-neutral). */
        @Nullable
        public final String previewUrl;
        /** Video frame width in px (provider-neutral; absent when the provider did not report it). */
        @Nullable
        public final Integer width;
        /** Video frame height in px (provider-neutral; absent when the provider did not report it). */
        @Nullable
        public final Integer height;
        /** Video duration in milliseconds (provider-neutral; absent when the provider did not report it). */
        @Nullable
        public final Integer durationMs;

        public UploadedMedia(String storageProvider, String storageKey, @Nullable String publicUrl,
                             String mimeType, long sizeBytes, @Nullable String deliveryUrl,
                             @Nullable String thumbnailUrl, @Nullable String previewUrl) {
            this(storageProvider, storageKey, publicUrl, mimeType, sizeBytes, deliveryUrl,
                    thumbnailUrl, previewUrl, null, null, null);
        }

        public UploadedMedia(String storageProvider, String storageKey, @Nullable String publicUrl,
                             String mimeType, long sizeBytes, @Nullable String deliveryUrl,
                             @Nullable String thumbnailUrl, @Nullable String previewUrl,
                             @Nullable Integer width, @Nullable Integer height,
                             @Nullable Integer durationMs) {
            this.storageProvider = storageProvider;
            this.storageKey = storageKey;
            this.publicUrl = publicUrl;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
            this.deliveryUrl = deliveryUrl;
            this.thumbnailUrl = thumbnailUrl;
            this.previewUrl = previewUrl;
            this.width = width;
            this.height = height;
            this.durationMs = durationMs;
        }
    }

    /**
     * One media file attached to a message ({@code message_attachments} joined
     * with {@code media}, migration 005). The object only ever carries METADATA
     * — the binary bytes live in external storage (ImageBB/Cloudinary via the
     * Creanger/Admin backend) and are never touched by the Android data plane.
     *
     * The wire shape is the PostgREST embedded resource
     * {@code message_attachments(position,caption,media(...))}: the media
     * columns are flattened onto here so the whole attachment travels with the
     * message row, and {@code mediaId}+{@code messageId} keep the original
     * relationship for server-side lookups.
     */
    public static final class MediaAttachment {
        /** {@code message_attachments} row id, or null when constructed client-side. */
        @Nullable
        public final String id;
        /** {@code message_attachments.message_id} (null on optimistic copies). */
        @Nullable
        public final String messageId;
        /** {@code media.id}. */
        @Nullable
        public final String mediaId;
        /** Order within a multi-attachment message (0-indexed). */
        public final int position;
        /** Per-attachment caption (message-level caption lives on the row's content). */
        @Nullable
        public final String caption;
        /** {@code storage_provider_type}: cloudinary / imagebb / s3 / local / other. */
        public final String storageProvider;
        /** Provider-specific key/path that retrieves the file. */
        public final String storageKey;
        /** Public URL for direct access (when the provider supports it). */
        @Nullable
        public final String publicUrl;
        /** Secure/signed delivery URL (when supported). */
        @Nullable
        public final String deliveryUrl;
        /** MIME type, e.g. image/png, video/mp4, audio/mpeg. */
        public final String mimeType;
        /** Byte size of the original file. */
        public final long sizeBytes;
        /** SHA-256 of the file for integrity verification. */
        @Nullable
        public final String checksum;
        /** Pixel width (images/videos). */
        @Nullable
        public final Integer width;
        /** Pixel height (images/videos). */
        @Nullable
        public final Integer height;
        /** Duration in milliseconds (audio/video). */
        @Nullable
        public final Integer duration;
        /** {@code media.thumbnail_media_id} — the thumbnail of a video/document. */
        @Nullable
        public final String thumbnailMediaId;
        /** Local file path/URI for immediate pending preview (never sent to server). */
        @Nullable
        public final String localPath;
        /** Thumbnail/poster URL for video (provider-neutral). */
        @Nullable
        public final String thumbnailUrl;
        /** Short preview/animated URL for video (provider-neutral). */
        @Nullable
        public final String previewUrl;
        /** {@code media.deleted_at} — null while the media row is live. */
        @Nullable
        public final String deletedAt;
        /** {@code media.created_at}. */
        @Nullable
        public final String createdAt;

public MediaAttachment(@Nullable String id, @Nullable String messageId, @Nullable String mediaId,
                                int position, @Nullable String caption, String storageProvider, String storageKey,
                                @Nullable String publicUrl, @Nullable String deliveryUrl, String mimeType,
                                long sizeBytes, @Nullable String checksum, @Nullable Integer width,
                                @Nullable Integer height, @Nullable Integer duration,
                                @Nullable String thumbnailMediaId, @Nullable String localPath,
                                @Nullable String thumbnailUrl, @Nullable String previewUrl,
                                @Nullable String deletedAt, @Nullable String createdAt) {
            this.id = id;
            this.messageId = messageId;
            this.mediaId = mediaId;
            this.position = position;
            this.caption = caption;
            this.storageProvider = storageProvider;
            this.storageKey = storageKey;
            this.publicUrl = publicUrl;
            this.deliveryUrl = deliveryUrl;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
            this.checksum = checksum;
            this.width = width;
            this.height = height;
            this.duration = duration;
            this.thumbnailMediaId = thumbnailMediaId;
            this.localPath = localPath;
            this.thumbnailUrl = thumbnailUrl;
            this.previewUrl = previewUrl;
            this.deletedAt = deletedAt;
            this.createdAt = createdAt;
        }

        /**
         * Client-side PENDING attachment before upload: carries only the local
         * file so the UI can render an immediate preview. Never sent as-is to
         * the server — the send flow swaps it for a real uploaded attachment.
         */
        public static MediaAttachment createPending(String fileName, String mimeType, String localPath) {
            return new MediaAttachment(null, null, null, 0, null, "pending", fileName,
                    null, null, mimeType, 0, null, null, null, null,
                    null, localPath, null, null, null, null);
        }

        /** Video variant with a local poster/thumbnail path. */
        public static MediaAttachment createPendingVideo(String fileName, String mimeType, String posterPath) {
            return new MediaAttachment(null, null, null, 0, null, "pending", fileName,
                    null, null, mimeType, 0, null, null, null, null,
                    null, null, posterPath, null, null, null);
        }
    }

    /**
     * A single message ({@code messages}). {@code chatSeq} is the deterministic
     * per-chat order key; {@code clientMessageId} is the idempotency key.
     * {@code replyToMessageId} is the authoritative Creanger UUID of the
     * message this one replies to ({@code messages.reply_to_message_id}, a FK
     * to the same table — never a synthetic Telegram id), or null for a plain
     * message. {@code attachments} carries the media metadata of a media
     * message (empty for text); {@code messageType} selects which media type a
     * message is.
     *
     * Rich text formatting is stored in {@code richText} as a JSON object
     * compatible with Telegram's {@code TL_iv.RichText} format. When null,
     * the message is plain text.
     */
    public static final class CreangerMessage {
        public final String id;
        public final String chatId;
        public final String senderId;
        public final String messageType; // MessageType.*
        @Nullable
        public final String content;
        public final String status; // MessageStatus.*
        @Nullable
        public final String clientMessageId;
        @Nullable
        public final Long chatSeq;
        @Nullable
        public final String createdAt;
        @Nullable
        public final String editedAt;
        @Nullable
        public final String deletedAt;
        @Nullable
        public final String updatedAt;
        /**
         * Creanger UUID of the replied-to message, or null when this is not a
         * reply. The backend validates it belongs to the same chat
         * (migration 017 trigger) and soft-deleted targets are tolerated.
         */
        @Nullable
        public final String replyToMessageId;
        /**
         * True while this is an optimistic client-side copy not yet confirmed by
         * the server (pending send). Server rows are always isLocal=false.
         */
        public final boolean isLocal;
        /**
         * Media metadata attached to a media message (empty for text). Optimistic
         * copies carry the attachments the client is about to send; server rows
         * carry whatever the REST/RPC projection delivered (see
         * {@code getAttachments} for the snapshot-fill source).
         */
        public final List<MediaAttachment> attachments;
        /**
         * Rich text formatting entities in Telegram's {@code TL_iv.RichText} format.
         * Stored as a JSON object. When null, the message is plain text.
         */
        @Nullable
        public JSONObject richText;

        public CreangerMessage(String id, String chatId, String senderId, String messageType,
                               @Nullable String content, String status,
                               @Nullable String clientMessageId, @Nullable Long chatSeq,
                               @Nullable String createdAt, @Nullable String editedAt,
                               @Nullable String deletedAt, @Nullable String updatedAt,
                               boolean isLocal) {
            this(id, chatId, senderId, messageType, content, status,
                    clientMessageId, chatSeq, createdAt, editedAt, deletedAt, updatedAt,
                    null, isLocal, null);
        }

        public CreangerMessage(String id, String chatId, String senderId, String messageType,
                               @Nullable String content, String status,
                               @Nullable String clientMessageId, @Nullable Long chatSeq,
                               @Nullable String createdAt, @Nullable String editedAt,
                               @Nullable String deletedAt, @Nullable String updatedAt,
                               @Nullable String replyToMessageId,
                               boolean isLocal) {
            this(id, chatId, senderId, messageType, content, status,
                    clientMessageId, chatSeq, createdAt, editedAt, deletedAt, updatedAt,
                    replyToMessageId, isLocal, null);
        }

        public CreangerMessage(String id, String chatId, String senderId, String messageType,
                               @Nullable String content, String status,
                               @Nullable String clientMessageId, @Nullable Long chatSeq,
                               @Nullable String createdAt, @Nullable String editedAt,
                               @Nullable String deletedAt, @Nullable String updatedAt,
                               @Nullable String replyToMessageId, boolean isLocal,
                               @Nullable List<MediaAttachment> attachments) {
            this(id, chatId, senderId, messageType, content, status,
                    clientMessageId, chatSeq, createdAt, editedAt, deletedAt, updatedAt,
                    replyToMessageId, isLocal, attachments, null);
        }

        public CreangerMessage(String id, String chatId, String senderId, String messageType,
                               @Nullable String content, String status,
                               @Nullable String clientMessageId, @Nullable Long chatSeq,
                               @Nullable String createdAt, @Nullable String editedAt,
                               @Nullable String deletedAt, @Nullable String updatedAt,
                               @Nullable String replyToMessageId, boolean isLocal,
                               @Nullable List<MediaAttachment> attachments,
                               @Nullable JSONObject richText) {
            this.id = id;
            this.chatId = chatId;
            this.senderId = senderId;
            this.messageType = messageType;
            this.content = content;
            this.status = status;
            this.clientMessageId = clientMessageId;
            this.chatSeq = chatSeq;
            this.createdAt = createdAt;
            this.editedAt = editedAt;
            this.deletedAt = deletedAt;
            this.updatedAt = updatedAt;
            this.replyToMessageId = replyToMessageId;
            this.isLocal = isLocal;
            this.attachments = attachments != null ? attachments : new ArrayList<>();
            this.richText = richText;
        }

        /**
         * Get the rich text as a JSONObject, or null if not set.
         */
        @Nullable
        public JSONObject getRichText() {
            return richText;
        }

        /**
         * Set the rich text formatting.
         * @param richText JSON object in TL_iv.RichText format
         */
        public void setRichText(@Nullable JSONObject richText) {
            this.richText = richText;
        }

        /**
         * Check if the message has rich text formatting.
         */
        public boolean hasRichText() {
            return richText != null && richText.length() > 0;
        }

        public boolean isText() {
            return MessageType.TEXT.equals(messageType);
        }

        /** True for any of the media message types (image/video/document/audio/voice). */
        public boolean isMedia() {
            return MessageType.isMediaType(messageType);
        }

        public boolean isPending() {
            return MessageStatus.PENDING.equals(status);
        }
    }

    /**
     * One page of a chat's messages, newest-first (ordered by {@code chatSeq}
     * DESC server-side). {@code nextOlderSeq} is the {@code chatSeq} cursor to
     * request the next older page (null when there is none).
     */
    public static final class MessagePage {
        @Nullable
        public final List<CreangerMessage> messages;
        @Nullable
        public final Long nextOlderSeq;
        public final boolean hasMore;

        public MessagePage(@Nullable List<CreangerMessage> messages,
                           @Nullable Long nextOlderSeq, boolean hasMore) {
            this.messages = messages == null ? new java.util.ArrayList<>() : messages;
            this.nextOlderSeq = nextOlderSeq;
            this.hasMore = hasMore;
        }
    }
}