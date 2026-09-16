package com.creanger.app.messenger.creanger.data;

import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment;
import com.creanger.app.messenger.creanger.model.MessageModels.MessagePage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageReaction;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatusUpdate;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageType;
import com.creanger.app.messenger.creanger.model.MessageModels.UploadedMedia;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UI-facing controller over {@link MessageRepository} for the chat screen.
 *
 * Responsibilities (all synchronous; call off the main thread):
 *  - maintain the newest-first display list for a chat (mirrors the
 *    repository's per-account cache, {@code chat_seq} DESC ordering)
 *  - drive pagination with the {@code nextOlderSeq} cursor ({@code hasMore})
 *  - optimistic text send: an immediate local PENDING row the user sees
 *    instantly, reconciled with the server-confirmed row via
 *    {@code client_message_id} (idempotent, first write wins per the RPC)
 *  - failed sends stay local (FAILED) for retry; logout/account switch clears
 *    everything
 *
 * The UI observes {@link Listener} callbacks; threading is up to the caller
 * (typically an executor + main-handler pair, see {@link CreangerMessageAsync}).
 */
public final class CreangerChatBridge {

    public interface Listener {
        /** Display list changed (page load, older page appended, send/reconcile). */
        void onMessagesChanged(String chatId, List<CreangerMessageUiModel> messages);

        /** An operation failed; {@code apiError} and/or {@code ioError} carries the cause. */
        void onError(String chatId, CreangerApiException apiError, Throwable ioError);
    }

    public static final int DEFAULT_PAGE_SIZE = MessageRepository.DEFAULT_PAGE_SIZE;

    private final MessageRepository repository;
    private final String ownerId;
    private final Listener listener;

    /** chatId -> display list, newest (top) first with pending on top. */
    private final Map<String, List<CreangerMessageUiModel>> chatMessages = new ConcurrentHashMap<>();

    public CreangerChatBridge(MessageRepository repository, String ownerId, @Nullable Listener listener) {
        this.repository = repository;
        this.ownerId = ownerId;
        this.listener = listener;
    }

    public boolean isAuthenticated() {
        return repository.isAuthenticated();
    }

    /** Cached display list (newest first) for a chat, or empty. */
    public List<CreangerMessageUiModel> getMessages(String chatId) {
        List<CreangerMessageUiModel> list = chatMessages.get(chatId);
        return list != null ? list : new ArrayList<>();
    }

    /**
     * Loads the newest page of a chat, replacing the display list. Pending
     * local copies (from in-flight sends) are preserved on top by the
     * repository merge.
     */
    public MessagePage refreshMessages(String chatId, int limit) throws IOException, CreangerApiException {
        try {
            MessagePage page = repository.refreshMessages(chatId, limit);
            resync(chatId);
            notifyChanged(chatId);
            return page;
        } catch (IOException | CreangerApiException e) {
            notifyError(chatId, e);
            throw e;
        }
    }

    /** Convenience for the newest page at the default page size. */
    public MessagePage refreshMessages(String chatId) throws IOException, CreangerApiException {
        return refreshMessages(chatId, DEFAULT_PAGE_SIZE);
    }

    /**
     * Appends the next older page of history using the {@code nextOlderSeq}
     * cursor; older rows land at the tail. Returns the older page ({@code
     * hasMore == false} + empty messages when history is exhausted).
     */
    public MessagePage loadOlderMessages(String chatId, long beforeSeq, int limit)
            throws IOException, CreangerApiException {
        try {
            MessagePage page = repository.loadOlderMessages(chatId, beforeSeq, limit);
            if (!page.messages.isEmpty()) {
                resync(chatId);
                notifyChanged(chatId);
            }
            return page;
        } catch (IOException | CreangerApiException e) {
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Optimistic text send. Two phase:
     *  - {@link #insertPendingRow} is called by the UI the moment the user taps
     *    send (main thread), so the PENDING row renders instantly
     *  - this method performs the idempotent {@code send_text_message} RPC off
     *    the main thread and reconciles: success swaps the local row to the
     *    confirmed server row, failure marks it FAILED for retry
     *
     * {@code replyToMessageId} is the authoritative Creanger UUID of the
     * replied-to message and is preserved on the pending and confirmed rows.
     *
     * @return the server-confirmed message id
     */
    public String sendTextMessage(String chatId, String clientMessageId, String content,
                                  @Nullable String replyToMessageId)
            throws IOException, CreangerApiException {
        return sendTextMessage(chatId, clientMessageId, content, replyToMessageId, null);
    }

    /** Sends text, carrying TL_iv.RichText JSON when formatting is available. */
    public String sendTextMessage(String chatId, String clientMessageId, String content,
                                  @Nullable String replyToMessageId,
                                  @Nullable org.json.JSONObject richText)
            throws IOException, CreangerApiException {
        try {
            String serverId = repository.sendTextMessage(chatId, clientMessageId, content, replyToMessageId,
                    richText);
            resync(chatId);
            notifyChanged(chatId);
            return serverId;
        } catch (IOException | CreangerApiException e) {
            // The optimistic copy stays in the display list; mark it FAILED so
            // the UI shows the retry affordance without scrolling it away.
            markFailed(chatId, clientMessageId);
            notifyError(chatId, e);
            throw e;
        }
    }

    /** Convenience for a plain (non-reply) text send. */
    public String sendTextMessage(String chatId, String clientMessageId, String content)
            throws IOException, CreangerApiException {
        return sendTextMessage(chatId, clientMessageId, content, null);
    }

    /**
     * Optimistic media send (mirrors {@link #sendTextMessage}). Two phase:
     *  - {@link #insertPendingRow} inserts the PENDING media row (with its
     *    {@code messageType} + attachment metadata) the moment the user acts
     *  - this method runs the idempotent {@code send_media_message} RPC off the
     *    main thread; success swaps the local row for the confirmed server row
     *    (type + attachments preserved), failure marks it FAILED for retry
     *
     * @return the server-confirmed message id
     */
    public String sendMediaMessage(String chatId, String clientMessageId, String messageType,
                                   @Nullable String caption, @Nullable String replyToMessageId,
                                   List<MediaAttachment> attachments)
            throws IOException, CreangerApiException {
        try {
            String serverId = repository.sendMediaMessage(chatId, clientMessageId, messageType,
                    caption, replyToMessageId, attachments);
            resync(chatId);
            notifyChanged(chatId);
            return serverId;
        } catch (IOException | CreangerApiException e) {
            markFailed(chatId, clientMessageId);
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Optimistic IMAGE send (Media Send phase): uploads the raw bytes to the
     * Creanger/Admin Media API (Edge Function -> ImageBB server-side), then
     * runs the idempotent {@code send_media_message} RPC with the returned
     * provider-neutral metadata (storage key + public URL). Three phase,
     * mirroring {@link #sendTextMessage}:
     *  - {@link #insertPendingRow} already rendered the PENDING media row
     *  - this method uploads the bytes (background) and sends the metadata
     *  - success swaps the local row for the confirmed server row (type +
     *    attachments preserved); failure marks it FAILED for retry
     *
     * The RPC is idempotent per {@code clientMessageId}, so a retry never
     * duplicates a sent message.
     *
     * @return the server-confirmed message id
     */
    public String sendImageMessage(String chatId, String clientMessageId, String fileName,
                                   byte[] imageData, String mimeType, @Nullable String caption,
                                   @Nullable String replyToMessageId)
            throws IOException, CreangerApiException {
        try {
            UploadedMedia uploaded = repository.uploadImage(chatId, fileName, imageData, mimeType);
            MediaAttachment attachment = new MediaAttachment(
                    null, null, null, 0, null,
                    uploaded.storageProvider, uploaded.storageKey, uploaded.publicUrl,
                    uploaded.deliveryUrl, uploaded.mimeType, uploaded.sizeBytes,
                    null, null, null, null, null, null,
                    uploaded.thumbnailUrl, uploaded.previewUrl,
                    null, null);
            String serverId = repository.sendMediaMessage(chatId, clientMessageId, MessageType.IMAGE,
                    caption, replyToMessageId, Collections.singletonList(attachment));
            resync(chatId);
            notifyChanged(chatId);
            return serverId;
        } catch (IOException | CreangerApiException e) {
            markFailed(chatId, clientMessageId);
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Optimistic VIDEO send (Media Phase 5A): uploads the raw bytes to the
     * Creanger/Admin Media API (Edge Function -> Cloudinary server-side), then
     * runs the idempotent {@code send_media_message} RPC with the returned
     * provider-neutral metadata (storage key + public URL + thumbnail URL). Three
     * phase, mirroring {@link #sendImageMessage}:
     *  - {@link #insertPendingRow} already rendered the PENDING media row
     *  - this method uploads the bytes (background) and sends the metadata
     *  - success swaps the local row for the confirmed server row (type +
     *    attachments preserved); failure marks it FAILED for retry
     *
     * The RPC is idempotent per {@code clientMessageId}, so a retry never
     * duplicates a sent message.
     *
     * @return the server-confirmed message id
     */
    public String sendVideoMessage(String chatId, String clientMessageId, String fileName,
                                   byte[] videoData, String mimeType, @Nullable String caption,
                                   @Nullable String replyToMessageId)
            throws IOException, CreangerApiException {
        try {
            UploadedMedia uploaded = repository.uploadVideo(chatId, fileName, videoData, mimeType);
            MediaAttachment attachment = new MediaAttachment(
                    null, null, null, 0, null,
                    uploaded.storageProvider, uploaded.storageKey, uploaded.publicUrl,
                    uploaded.deliveryUrl, uploaded.mimeType, uploaded.sizeBytes,
                    null, uploaded.width, uploaded.height, uploaded.durationMs,
                    null, null,
                    uploaded.thumbnailUrl, uploaded.previewUrl,
                    null, null);
            String serverId = repository.sendMediaMessage(chatId, clientMessageId, MessageType.VIDEO,
                    caption, replyToMessageId, Collections.singletonList(attachment));
            resync(chatId);
            notifyChanged(chatId);
            return serverId;
        } catch (IOException | CreangerApiException e) {
            markFailed(chatId, clientMessageId);
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Optimistic DOCUMENT send: uploads the raw bytes to the Creanger Media API
     * ({@code /v1/media/upload-document} -> Cloudinary {@code raw} server-side),
     * then runs the idempotent {@code send_media_message} RPC with the returned
     * provider-neutral metadata. Three phase, mirroring {@link #sendImageMessage}:
     *  - {@link #insertPendingRow} already rendered the PENDING media row
     *  - this method uploads the bytes (background) and sends the metadata
     *  - success swaps the local row for the confirmed server row (type +
     *    attachments preserved); failure marks it FAILED for retry
     *
     * The RPC is idempotent per {@code clientMessageId}, so a retry never
     * duplicates a sent message.
     *
     * @return the server-confirmed message id
     */
    public String sendDocumentMessage(String chatId, String clientMessageId, String fileName,
                                      byte[] documentData, String mimeType, @Nullable String caption,
                                      @Nullable String replyToMessageId)
            throws IOException, CreangerApiException {
        try {
            UploadedMedia uploaded = repository.uploadDocument(chatId, fileName, documentData, mimeType);
            MediaAttachment attachment = new MediaAttachment(
                    null, null, null, 0, null,
                    uploaded.storageProvider, uploaded.storageKey, uploaded.publicUrl,
                    uploaded.deliveryUrl, uploaded.mimeType, uploaded.sizeBytes,
                    null, null, null, null, null, null,
                    null, null, null, null);
            String serverId = repository.sendMediaMessage(chatId, clientMessageId, MessageType.DOCUMENT,
                    caption, replyToMessageId, Collections.singletonList(attachment));
            resync(chatId);
            notifyChanged(chatId);
            return serverId;
        } catch (IOException | CreangerApiException e) {
            markFailed(chatId, clientMessageId);
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Optimistic AUDIO send: uploads the raw bytes to the Creanger Media API
     * ({@code /v1/media/upload-audio} -> Cloudinary server-side, resource_type
     * video with audio auto-detect), then runs the idempotent
     * {@code send_media_message} RPC with the returned provider-neutral metadata
     * (including the server-computed {@code durationMs}). Three phase, mirroring
     * {@link #sendImageMessage}.
     *
     * The RPC is idempotent per {@code clientMessageId}, so a retry never
     * duplicates a sent message.
     *
     * @return the server-confirmed message id
     */
    public String sendMusicMessage(String chatId, String clientMessageId, String fileName,
                                   byte[] audioData, String mimeType, @Nullable String caption,
                                   @Nullable String replyToMessageId)
            throws IOException, CreangerApiException {
        return sendAudioMessage(chatId, clientMessageId, MessageType.AUDIO, fileName,
                audioData, mimeType, caption, replyToMessageId);
    }

    /**
     * Optimistic VOICE send: identical to audio, but the row carries the
     * {@code voice} message type so the UI renders the round/compact voice-message
     * bubble with the server-computed play duration ({@code durationMs}).
     *
     * The RPC is idempotent per {@code clientMessageId}, so a retry never
     * duplicates a sent message.
     *
     * @return the server-confirmed message id
     */
    public String sendVoiceMessage(String chatId, String clientMessageId, String fileName,
                                   byte[] audioData, String mimeType, @Nullable String caption,
                                   @Nullable String replyToMessageId)
            throws IOException, CreangerApiException {
        return sendAudioMessage(chatId, clientMessageId, MessageType.VOICE, fileName,
                audioData, mimeType, caption, replyToMessageId);
    }

    /** Shared implementation for the audio/voice send paths. */
    private String sendAudioMessage(String chatId, String clientMessageId, String messageType,
                                    String fileName, byte[] audioData, String mimeType,
                                    @Nullable String caption, @Nullable String replyToMessageId)
            throws IOException, CreangerApiException {
        try {
            UploadedMedia uploaded = repository.uploadAudio(chatId, fileName, audioData, mimeType);
            MediaAttachment attachment = new MediaAttachment(
                    null, null, null, 0, null,
                    uploaded.storageProvider, uploaded.storageKey, uploaded.publicUrl,
                    uploaded.deliveryUrl, uploaded.mimeType, uploaded.sizeBytes,
                    null, null, null, uploaded.durationMs, null, null,
                    null, null, null, null);
            String serverId = repository.sendMediaMessage(chatId, clientMessageId, messageType,
                    caption, replyToMessageId, Collections.singletonList(attachment));
            resync(chatId);
            notifyChanged(chatId);
            return serverId;
        } catch (IOException | CreangerApiException e) {
            markFailed(chatId, clientMessageId);
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Inserts an immediate PENDING media row for {@code clientMessageId} (main
     * thread), carrying the {@code messageType} + attachment metadata so the
     * media bubble renders the instant the user sends (before the RPC resolves).
     * {@code replyToMessageId} is carried in as the authoritative reply target.
     */
    public void insertPendingRow(String chatId, String clientMessageId, String messageType,
                                 @Nullable String caption, @Nullable String replyToMessageId,
                                 List<MediaAttachment> attachments) {
        List<CreangerMessageUiModel> current = new ArrayList<>(getMessages(chatId));
        List<CreangerMessageUiModel> merged = new ArrayList<>();
        for (CreangerMessageUiModel m : current) {
            if (m.clientMessageId != null && m.clientMessageId.equals(clientMessageId)) {
                continue;
            }
            merged.add(m);
        }
        CreangerMessageUiModel pending = new CreangerMessageUiModel(
                "local-" + clientMessageId, chatId, ownerId, caption, MessageStatus.PENDING,
                clientMessageId, null, null, replyToMessageId, true, true, messageType,
                attachments);
        merged.add(0, pending);
        chatMessages.put(chatId, merged);
        notifyChanged(chatId);
    }

    /** Convenience for a plain (non-reply) media pending insert. */
    public void insertPendingRow(String chatId, String clientMessageId, String messageType,
                                 @Nullable String caption, List<MediaAttachment> attachments) {
        insertPendingRow(chatId, clientMessageId, messageType, caption, null, attachments);
    }

    /**
     * Inserts an immediate PENDING row for {@code clientMessageId} (main
     * thread). {@code replyToMessageId} is carried into the pending copy so the
     * reply preview is routed by the authoritative Creanger UUID.
     */
    public void insertPendingRow(String chatId, String clientMessageId, String content,
                                 @Nullable String replyToMessageId) {
        List<CreangerMessageUiModel> current = new ArrayList<>(getMessages(chatId));
        // Dedupe: replace any row with the same client_message_id (retry).
        List<CreangerMessageUiModel> merged = new ArrayList<>();
        for (CreangerMessageUiModel m : current) {
            if (m.clientMessageId != null && m.clientMessageId.equals(clientMessageId)) {
                continue;
            }
            merged.add(m);
        }
        CreangerMessageUiModel pending = new CreangerMessageUiModel(
                "local-" + clientMessageId, chatId, ownerId, content, MessageStatus.PENDING,
                clientMessageId, null, null, replyToMessageId, true, true);
        merged.add(0, pending);
        chatMessages.put(chatId, merged);
        notifyChanged(chatId);
    }

    /** Convenience for a plain (non-reply) pending insert. */
    public void insertPendingRow(String chatId, String clientMessageId, String content) {
        insertPendingRow(chatId, clientMessageId, content, null);
    }

    /** Marks a local PENDING row FAILED (kept for retry, shown with an error glyph). */
    public void markFailed(String chatId, String clientMessageId) {
        setStatus(chatId, clientMessageId, MessageStatus.FAILED);
    }

    /** Marks a previously-FAILED local row back to PENDING for a retry. */
    public void markPending(String chatId, String clientMessageId) {
        setStatus(chatId, clientMessageId, MessageStatus.PENDING);
    }

    /** Rewrites the status of a local row, preserving every other projection field. */
    private void setStatus(String chatId, String clientMessageId, String status) {
        List<CreangerMessageUiModel> current = new ArrayList<>(getMessages(chatId));
        for (int i = 0; i < current.size(); i++) {
            CreangerMessageUiModel m = current.get(i);
            if (m.clientMessageId != null && m.clientMessageId.equals(clientMessageId)) {
                current.set(i, new CreangerMessageUiModel(
                        m.id, m.chatId, m.senderId, m.content, status,
                        m.clientMessageId, m.chatSeq, m.createdAt, m.replyToMessageId,
                        m.isLocal, m.out, m.messageType, m.reactions, m.attachments));
            }
        }
        chatMessages.put(chatId, current);
        notifyChanged(chatId);
    }

    /**
     * Cancels a still-pending local send: removes the PENDING row for
     * {@code clientMessageId} from the display list (local-only, no server
     * RPC — the row is not yet confirmed). The pending copy can live in either
     * the repository cache (an in-flight send) or the display list alone
     * ({@link #insertPendingRow}), so both are purged and the display is
     * resynced — a canceled row never resurfaces from either source. If the
     * send already reached the server, the confirmed row re-enters through the
     * idempotent Realtime/REST reconcile. Scoped to the given chat, so only
     * that message's pending copy is removed.
     *
     * @return {@code true} when a pending row was actually removed
     */
    public boolean cancelPending(String chatId, String clientMessageId) {
        boolean removedRepo = repository.cancelPending(chatId, clientMessageId);
        List<CreangerMessageUiModel> current = new ArrayList<>(getMessages(chatId));
        boolean removedDisplay = false;
        List<CreangerMessageUiModel> updated = new ArrayList<>();
        for (CreangerMessageUiModel m : current) {
            if (m.isLocal && clientMessageId != null && clientMessageId.equals(m.clientMessageId)) {
                removedDisplay = true;
            } else {
                updated.add(m);
            }
        }
        chatMessages.put(chatId, updated);
        if (removedRepo || removedDisplay) {
            resync(chatId);
            notifyChanged(chatId);
        }
        return removedRepo || removedDisplay;
    }

    /**
     * Applies a message row received over Realtime. The repository merge
     * dedups against everything already cached (REST pages, send confirms), so
     * pushing the same id/client-message-id again never duplicates.
     *
     * @return {@code true} when a new row entered the display list (notify the
     *         UI), {@code false} when the row was already known (duplicate)
     */
    public boolean applyRealtime(String chatId, CreangerMessage message) {
        boolean inserted = repository.ingestRealtime(chatId, message);
        resync(chatId);
        if (inserted) {
            notifyChanged(chatId);
        }
        return inserted;
    }

    /**
     * Applies a server-side EDIT received over Realtime. The repository treats
     * the row as authoritative and (by content/edited_at equality) collapses an
     * echo of the user's own optimistic edit, so a local edit plus its realtime
     * echo never renders twice.
     *
     * @return {@code true} when the display list actually changed
     */
    public boolean applyRealtimeEdit(String chatId, CreangerMessage message) {
        boolean changed = repository.applyRealtimeEdit(chatId, message);
        resync(chatId);
        if (changed) {
            notifyChanged(chatId);
        }
        return changed;
    }

    /** Applies a server-side DELETE (tombstone/DELETE event) from Realtime. */
    public boolean applyRealtimeDelete(String chatId, String messageId) {
        boolean changed = repository.applyRealtimeDelete(chatId, messageId);
        resync(chatId);
        if (changed) {
            notifyChanged(chatId);
        }
        return changed;
    }

    /**
     * Applies a recipient-driven delivery/read status change received over
     * Realtime (migration 026). The repository advances the status
     * monotonically, so an out-of-order or repeated status event collapses and
     * never re-renders.
     *
     * @return {@code true} when the display list's row status actually advanced
     */
    public boolean applyRealtimeStatus(String chatId, CreangerMessage message) {
        boolean changed = repository.applyRealtimeStatus(chatId, message);
        resync(chatId);
        if (changed) {
            notifyChanged(chatId);
        }
        return changed;
    }

    /**
     * Marks the delivery/read state of an incoming message through the
     * recipient-only {@code mark_message_status} RPC (migration 026); applied
     * to the display on success only.
     *
     * @return the message id the server confirmed (local apply made the status
     *         visible to the sender via realtime / the next recovery)
     */
    public String markMessageStatus(String chatId, String messageId, String status)
            throws IOException, CreangerApiException {
        try {
            String serverId = repository.markMessageStatus(chatId, messageId, status);
            resync(chatId);
            notifyChanged(chatId);
            return serverId;
        } catch (IOException | CreangerApiException e) {
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Recovers delivery/read status changes missed while Realtime was down via
     * the {@code get_message_statuses_since} RPC (migration 026), merging them
     * into the display list. Anchored on the repository's per-chat
     * {@code updated_at} watermark when {@code afterUpdatedAt} is null.
     *
     * @return the recovered status updates (ascending {@code updated_at})
     */
    public java.util.List<MessageStatusUpdate> recoverMessageStatuses(
            String chatId, @Nullable String afterUpdatedAt, int limit)
            throws IOException, CreangerApiException {
        try {
            java.util.List<MessageStatusUpdate> updates =
                    repository.recoverMessageStatuses(chatId, afterUpdatedAt, limit);
            if (!updates.isEmpty()) {
                resync(chatId);
                notifyChanged(chatId);
            }
            return updates;
        } catch (IOException | CreangerApiException e) {
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Recovers edits/deletes missed while Realtime was down via the
     * {@code get_message_changes_since} RPC (migration 028), merging them into
     * the display list. Anchored on the repository's per-chat
     * {@code updated_at} watermark when {@code afterUpdatedAt} is null. Edits
     * and soft-deletes never bump {@code chat_seq}, so this complements
     * {@link #recoverSince}.
     *
     * @return the recovered change rows (ascending {@code updated_at})
     */
    public List<CreangerMessage> recoverMessageChanges(
            String chatId, @Nullable String afterUpdatedAt, int limit)
            throws IOException, CreangerApiException {
        try {
            List<CreangerMessage> changes =
                    repository.recoverMessageChanges(chatId, afterUpdatedAt, limit);
            if (!changes.isEmpty()) {
                resync(chatId);
                notifyChanged(chatId);
            }
            return changes;
        } catch (IOException | CreangerApiException e) {
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Optimistic edit, phase 1 (main thread): the cached row switches to
     * {@code newContent} immediately and the pre-edit row is snapshotted for
     * rollback. Returns {@code true} when the display changed.
     */
    public boolean applyOptimisticEdit(String chatId, String messageId, String newContent) {
        boolean changed = repository.applyOptimisticEdit(chatId, messageId, newContent);
        resync(chatId);
        if (changed) {
            notifyChanged(chatId);
        }
        return changed;
    }

    /**
     * Optimistic edit, phase 2 (background): runs the author-only
     * {@code edit_message} RPC. On success the optimistic content stays; on
     * failure the pre-edit row is restored (rollback) BEFORE the error
     * propagates to the caller.
     *
     * @return the message id the server confirmed
     */
    public String completeEdit(String chatId, String messageId, String newContent)
            throws IOException, CreangerApiException {
        try {
            String serverId = repository.completeEdit(chatId, messageId, newContent);
            resync(chatId);
            notifyChanged(chatId);
            return serverId;
        } catch (IOException | CreangerApiException e) {
            repository.rollbackEdit(chatId, messageId);
            resync(chatId);
            notifyChanged(chatId);
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Optimistic delete, phase 1 (main thread): the cached row is removed
     * immediately and snapshotted for rollback. Returns {@code true} when the
     * display changed.
     */
    public boolean applyOptimisticDelete(String chatId, String messageId) {
        boolean changed = repository.applyOptimisticDelete(chatId, messageId);
        resync(chatId);
        if (changed) {
            notifyChanged(chatId);
        }
        return changed;
    }

    /**
     * Optimistic delete, phase 2 (background): runs the author-only, soft-delete
     * {@code delete_message} RPC. On failure the deleted row is restored
     * (rollback) BEFORE the error propagates to the caller.
     *
     * @return the message id the server confirmed
     */
    public String completeDelete(String chatId, String messageId)
            throws IOException, CreangerApiException {
        try {
            String serverId = repository.completeDelete(chatId, messageId);
            resync(chatId);
            notifyChanged(chatId);
            return serverId;
        } catch (IOException | CreangerApiException e) {
            repository.rollbackDelete(chatId, messageId);
            resync(chatId);
            notifyChanged(chatId);
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Bulk delete, phase 2 (background): one atomic
     * {@code bulk_delete_messages} RPC for ids previously removed via
     * {@link #applyOptimisticDelete}. On failure every id rolls back BEFORE
     * the error propagates to the caller.
     *
     * @return the message ids the server confirmed
     */
    public List<String> completeBulkDelete(String chatId, List<String> messageIds)
            throws IOException, CreangerApiException {
        try {
            List<String> confirmed = repository.completeBulkDelete(chatId, messageIds);
            resync(chatId);
            notifyChanged(chatId);
            return confirmed;
        } catch (IOException | CreangerApiException e) {
            if (messageIds != null) {
                for (String id : messageIds) {
                    repository.rollbackDelete(chatId, id);
                }
            }
            resync(chatId);
            notifyChanged(chatId);
            notifyError(chatId, e);
            throw e;
        }
    }

    // ---- close friends (migration 040 audience list) ----

    /** The caller's close-friends audience ids (owner-scoped, never null). */
    public List<String> listCloseFriendIds() throws IOException, CreangerApiException {
        return repository.listCloseFriendIds();
    }

    /** Adds a user to the caller's close-friends audience. */
    public void addCloseFriend(String friendId) throws IOException, CreangerApiException {
        repository.addCloseFriend(friendId);
    }

    /** Removes a user from the caller's close-friends audience. */
    public void removeCloseFriend(String friendId) throws IOException, CreangerApiException {
        repository.removeCloseFriend(friendId);
    }

    /**
     * Recovers messages missed while Realtime was down using the existing
     * database cursor sync ({@code get_messages_since}): everything with
     * {@code chat_seq > afterSeq}, merged into the cache. This runs after a
     * reconnect; Realtime is never the source of truth.
     *
     * @return the recovered rows (ascending {@code chat_seq})
     */
    public List<CreangerMessage> recoverSince(String chatId, long afterSeq, int limit)
            throws IOException, CreangerApiException {
        try {
            List<CreangerMessage> recovered = repository.syncSince(chatId, afterSeq, limit);
            if (!recovered.isEmpty()) {
                resync(chatId);
                notifyChanged(chatId);
            }
            return recovered;
        } catch (IOException | CreangerApiException e) {
            notifyError(chatId, e);
            throw e;
        }
    }

    // ---- reactions ----

    /**
     * Adds the current user's reaction to a message through the idempotent
     * {@code add_reaction} RPC (migration 027), reflecting it on the display
     * only after the server confirms. The realtime echo (or the next
     * snapshot) converges the exact server-side count.
     *
     * @return the message id the server confirmed
     */
    public String addReaction(String chatId, String messageId, String reaction)
            throws IOException, CreangerApiException {
        return addReaction(chatId, messageId, reaction, false, null);
    }

    /**
     * Same as {@link #addReaction(String, String, String)} with the custom
     * {@code MessageReaction} fields forwarded to the {@code add_reaction} RPC.
     */
    public String addReaction(String chatId, String messageId, String reaction,
                              boolean isCustomEmoji, String customEmojiId)
            throws IOException, CreangerApiException {
        try {
            String serverId = repository.addReaction(chatId, messageId, reaction,
                    isCustomEmoji, customEmojiId);
            resync(chatId);
            notifyChanged(chatId);
            return serverId;
        } catch (IOException | CreangerApiException e) {
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Removes the current user's reaction from a message through the idempotent
     * {@code remove_reaction} RPC (migration 027). Only the caller's own
     * reaction is ever removed, server-side.
     *
     * @return the message id the server confirmed
     */
    public String removeReaction(String chatId, String messageId, String reaction)
            throws IOException, CreangerApiException {
        return removeReaction(chatId, messageId, reaction, false, null);
    }

    /**
     * Same as {@link #removeReaction(String, String, String)} mirroring the
     * custom {@code MessageReaction} fields to the {@code remove_reaction} RPC.
     */
    public String removeReaction(String chatId, String messageId, String reaction,
                                 boolean isCustomEmoji, String customEmojiId)
            throws IOException, CreangerApiException {
        try {
            String serverId = repository.removeReaction(chatId, messageId, reaction,
                    isCustomEmoji, customEmojiId);
            resync(chatId);
            notifyChanged(chatId);
            return serverId;
        } catch (IOException | CreangerApiException e) {
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Applies a realtime reaction row (INSERT/UPDATE or DELETE on
     * {@code message_reactions}) to the display list. The repository drops rows
     * for messages outside the loaded window ({@code isMessageKnown}); the set
     * semantics collapse duplicates and out-of-order frames.
     *
     * @return {@code true} when the display list actually changed
     */
    public boolean applyRealtimeReaction(String chatId, MessageReaction reaction, boolean added) {
        boolean changed = repository.applyRealtimeReaction(chatId, reaction, added);
        resync(chatId);
        if (changed) {
            notifyChanged(chatId);
        }
        return changed;
    }

    /**
     * Fetch-and-replace reaction state for the given message ids of a chat:
     * runs {@code getReactions} (the {@code message_reactions} REST snapshot)
     * and replaces the repository state for the chat. Used after a page load
     * and after a Realtime reconnect — reactions can be deleted, so the full
     * current row set is authoritative. Empty/unknown ids are handled as a
     * full replace with whatever the server returns.
     */
    public void loadReactions(String chatId, List<String> messageIds)
            throws IOException, CreangerApiException {
        try {
            repository.recoverReactions(chatId, messageIds);
            resync(chatId);
            notifyChanged(chatId);
        } catch (IOException | CreangerApiException e) {
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * True when {@code messageId} is in the chat's loaded window (reaction
     * rows carry no chat id, so this gates realtime reaction events).
     */
    public boolean isMessageKnown(String chatId, String messageId) {
        return repository.isMessageKnown(chatId, messageId);
    }

    /**
     * Fetch-and-attach media metadata for the given message ids of a chat
     * ({@code message_attachments?message_id=in.(...)} REST snapshot) and
     * attach it onto the cached rows. Used after a page load and after a
     * Realtime reconnect — Realtime rows and RPC projections carry no
     * attachment payload, so the snapshot fills them.
     */
    public void loadAttachments(String chatId, List<String> messageIds)
            throws IOException, CreangerApiException {
        try {
            repository.recoverAttachments(chatId, messageIds);
            resync(chatId);
            notifyChanged(chatId);
        } catch (IOException | CreangerApiException e) {
            notifyError(chatId, e);
            throw e;
        }
    }

    /**
     * Full-text search within a chat via the {@code search_messages_in_chat}
     * RPC (migration 017). Returns matching messages as UI models ordered by
     * {@code created_at DESC}. NOT cached — point-in-time snapshot.
     */
    public List<CreangerMessageUiModel> searchMessagesInChat(String chatId, String query, int limit)
            throws IOException, CreangerApiException {
        List<CreangerMessage> results = repository.searchMessagesInChat(chatId, query, limit);
        List<CreangerMessageUiModel> out = new ArrayList<>(results.size());
        for (CreangerMessage m : results) {
            out.add(CreangerMessageUiModel.from(m, ownerId,
                    repository.getReactionSummaries(chatId, m.id)));
        }
        return out;
    }

    // ---- pin/unpin messages ----

    /**
     * Pins a message by setting {@code metadata.pinned = true}.  Caller must
     * be a chat member with pin permission.
     */
    public String pinMessage(String messageId)
            throws IOException, CreangerApiException {
        return repository.pinMessage(messageId);
    }

    /**
     * Unpins a message by removing {@code metadata.pinned}. Idempotent.
     */
    public String unpinMessage(String messageId)
            throws IOException, CreangerApiException {
        return repository.unpinMessage(messageId);
    }

    /**
     * Returns all pinned messages for the given chat as UI models, ordered
     * by {@code created_at DESC} (newest pin first).
     */
    public List<CreangerMessageUiModel> getPinnedMessages(String chatId)
            throws IOException, CreangerApiException {
        List<CreangerMessage> pinned = repository.getPinnedMessages(chatId);
        List<CreangerMessageUiModel> out = new ArrayList<>(pinned.size());
        for (CreangerMessage m : pinned) {
            out.add(CreangerMessageUiModel.from(m, ownerId,
                    repository.getReactionSummaries(chatId, m.id)));
        }
        return out;
    }

    // ---- forward messages (migration 004 message_forwards) ----

    /**
     * Forwards a Creanger message to another Creanger chat. Returns the new
     * forwarded message UUID in the destination chat.
     */
    public String forwardMessage(String sourceMsgId, String destChatId)
            throws IOException, CreangerApiException {
        return repository.forwardMessage(sourceMsgId, destChatId);
    }

    // ---- edit media (replace attachment) ----

    public void replaceMediaAttachment(String messageId, String oldMediaId,
                                        String newMediaId, int position,
                                        @Nullable String caption)
            throws CreangerApiException {
        repository.replaceMediaAttachment(messageId, oldMediaId, newMediaId, position, caption);
    }

    public void editMessageCaption(String messageId, @Nullable String caption)
            throws CreangerApiException {
        repository.editMessageCaption(messageId, caption);
    }

    public String insertMedia(String storageProvider, String storageKey,
                              @Nullable String publicUrl, @Nullable String deliveryUrl,
                              String mimeType, long sizeBytes,
                              @Nullable Integer width, @Nullable Integer height,
                              @Nullable Integer duration)
            throws CreangerApiException {
        return repository.insertMedia(storageProvider, storageKey, publicUrl, deliveryUrl,
                mimeType, sizeBytes, width, height, duration);
    }

    /** Uploads raw image bytes via the Creanger Media API (server-side provider secrets). */
    public UploadedMedia uploadImage(String chatId, String fileName, byte[] imageData, String mimeType)
            throws IOException, CreangerApiException {
        return repository.uploadImage(chatId, fileName, imageData, mimeType);
    }

    /** Uploads raw video bytes via the Creanger Media API (server-side provider secrets). */
    public UploadedMedia uploadVideo(String chatId, String fileName, byte[] videoData, String mimeType)
            throws IOException, CreangerApiException {
        return repository.uploadVideo(chatId, fileName, videoData, mimeType);
    }

    // ---- drafts (migration 006) ----

    /**
     * Saves (upserts) a draft for the given chat. Idempotent. Pass null/empty
     * content to delete the draft.
     */
    public void saveDraft(String chatId, @Nullable String content,
                          @Nullable String replyToMessageId)
            throws IOException, CreangerApiException {
        saveDraft(chatId, content, replyToMessageId, null);
    }

    /** Saves a draft scoped to (owner, chat, topic); topic null = main chat. */
    public void saveDraft(String chatId, @Nullable String content,
                          @Nullable String replyToMessageId, @Nullable String topicId)
            throws IOException, CreangerApiException {
        repository.saveDraft(chatId, content, replyToMessageId, topicId);
    }

    /**
     * Fetches the current user's draft for the given chat, or null when
     * no draft exists.
     */
    @Nullable
    public CreangerChatApiClient.DraftInfo getDraft(String chatId)
            throws IOException, CreangerApiException {
        return getDraft(chatId, null);
    }

    /** Fetches the draft for the given chat+topic, or null. */
    @Nullable
    public CreangerChatApiClient.DraftInfo getDraft(String chatId, @Nullable String topicId)
            throws IOException, CreangerApiException {
        return repository.getDraft(chatId, topicId);
    }

    /** Deletes the current user's draft for the given chat (idempotent). */
    public void deleteDraft(String chatId)
            throws IOException, CreangerApiException {
        deleteDraft(chatId, null);
    }

    /** Deletes the draft for the given chat+topic (idempotent). */
    public void deleteDraft(String chatId, @Nullable String topicId)
            throws IOException, CreangerApiException {
        repository.deleteDraft(chatId, topicId);
    }

    /** Clears the whole display state (local logout / account switch isolation). */
    public void clear() {
        chatMessages.clear();
    }

    // ---- helpers ----

    /** Rebuilds the display list from the repository cache (merged + sorted). */
    private void resync(String chatId) {
        List<CreangerMessage> cached = repository.getCachedMessages(chatId);
        List<CreangerMessageUiModel> rows = new ArrayList<>();
        for (CreangerMessage m : cached) {
            rows.add(CreangerMessageUiModel.from(m, ownerId,
                    repository.getReactionSummaries(chatId, m.id)));
        }
        // Preserve any UI-visible pending rows not yet confirmed server-side.
        // getMessages() is newest-first, so collect the still-pending block and
        // prepend it in ONE reverse pass: add(0, ...) per row would flip the
        // block's relative order and stack rapid sends oldest-first until the
        // next full reload re-sorted them.
        java.util.ArrayList<CreangerMessageUiModel> pendings = new java.util.ArrayList<>();
        for (CreangerMessageUiModel existing : getMessages(chatId)) {
            if (existing.isLocal && !containsClientId(rows, existing.clientMessageId)) {
                pendings.add(existing);
            }
        }
        for (int i = pendings.size() - 1; i >= 0; i--) {
            rows.add(0, pendings.get(i));
        }
        chatMessages.put(chatId, rows);
    }

    private static boolean containsClientId(List<CreangerMessageUiModel> rows, String clientMessageId) {
        if (clientMessageId == null) {
            return false;
        }
        for (CreangerMessageUiModel m : rows) {
            if (clientMessageId.equals(m.clientMessageId)) {
                return true;
            }
        }
        return false;
    }

    private void notifyChanged(String chatId) {
        if (listener != null) {
            listener.onMessagesChanged(chatId, getMessages(chatId));
        }
    }

    private void notifyError(String chatId, Exception cause) {
        if (listener != null) {
            CreangerApiException api = cause instanceof CreangerApiException ? (CreangerApiException) cause : null;
            Throwable io = cause instanceof IOException ? cause : null;
            listener.onError(chatId, api, io);
        }
    }
}