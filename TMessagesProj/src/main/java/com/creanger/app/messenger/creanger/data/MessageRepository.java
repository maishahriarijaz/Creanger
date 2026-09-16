package com.creanger.app.messenger.creanger.data;

import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerMediaUploadClient;
import com.creanger.app.messenger.creanger.auth.AuthState;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment;
import com.creanger.app.messenger.creanger.model.MessageModels.MessagePage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageReaction;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatusUpdate;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageType;
import com.creanger.app.messenger.creanger.model.MessageModels.ReactionSummary;
import com.creanger.app.messenger.creanger.model.MessageModels.UploadedMedia;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Message foundation repository for the Creanger layer.
 *
 * Responsibilities:
 *  - fetch a chat's messages with deterministic per-chat {@code chat_seq}
 *    pagination (newest page first, older pages via the cursor)
 *  - send a text message with an optimistic/pending local copy that is replaced
 *    by the server-confirmed row on success
 *  - cache a minimal per-chat message list for fast re-open / offline restarts
 *  - clear everything on local logout / account switch (cache never leaks
 *    across accounts)
 *
 * The data plane is PostgREST/Supabase REST (see {@link CreangerChatApiClient});
 * the access-token JWT comes through {@link CreangerAuthEngine} so server-side
 * RLS authorization is respected end to end. Message ids are Creanger UUID
 * strings — deliberately isolated from TLRPC.
 */
public final class MessageRepository {

    /** Default page size for a chat history fetch. */
    public static final int DEFAULT_PAGE_SIZE = 30;

    private final CreangerAuthEngine engine;
    private final CreangerChatApiClient chatApi;
    @androidx.annotation.Nullable
    private final CreangerMediaUploadClient mediaUploadClient;

    /**
     * Minimal per-account cache of messages: key {@code chatId|ownerUserId},
     * value the newest-first list currently held (server rows + any pending
     * optimistic copies). Keyed by owning user so account switches never leak.
     */
    private final Map<String, List<CreangerMessage>> messagesCache = new ConcurrentHashMap<>();

    /**
     * Rollback snapshots for in-flight optimistic edits / deletes: key
     * {@code chatId|ownerUserId|messageId} -> the row before the optimistic
     * change. Held only while the RPC is outstanding; cleared on commit or
     * restored on failure so a rejected/offline operation never leaves the
     * local display mutated.
     */
    private final Map<String, CreangerMessage> pendingEditRollback = new ConcurrentHashMap<>();
    private final Map<String, CreangerMessage> pendingDeleteRollback = new ConcurrentHashMap<>();

    /**
     * Per-chat recovery watermark for delivery/read status changes: key
     * {@code chatId|ownerUserId} -> the newest {@code updated_at} already
     * applied locally. Status changes do not bump {@code chat_seq}, so after a
     * Realtime reconnect the missed statuses are recovered via
     * {@code get_message_statuses_since} anchored on this cursor.
     */
    private final Map<String, String> statusUpdatedAtByChat = new ConcurrentHashMap<>();

    /**
     * Per-chat recovery watermark for edit/delete changes: key
     * {@code chatId|ownerUserId} -> the newest {@code updated_at} already
     * applied locally. Edits and soft-deletes never bump {@code chat_seq}, so
     * they are recovered after a Realtime reconnect via
     * {@code get_message_changes_since} (migration 028) anchored on this
     * cursor.
     */
    private final Map<String, String> messageChangeUpdatedAtByChat = new ConcurrentHashMap<>();

    /**
     * Per-message reaction state: key {@code chatId|ownerUserId|messageId} ->
     * reactionKey -> {@link ReactionState} (the distinct users who reacted).
     * Reactions carry NO {@code chat_id} in their rows, so membership routing
     * happens here — a row whose {@code message_id} is not in the current
     * chat's loaded cache is dropped (see {@link #isMessageKnown}). Idempotent
     * set semantics make duplicate/out-of-order realtime frames collapse
     * naturally.
     */
    private final Map<String, Map<String, ReactionState>> reactionsByMessage = new ConcurrentHashMap<>();

    /**
     * Guards every read-modify-write sequence over {@link #messagesCache},
     * the rollback maps, the watermarks and the reaction state. The repository
     * is genuinely hit from several threads (realtime reader thread, send/
     * refresh executor, main thread optimistic phases); ConcurrentHashMap only
     * makes single operations atomic — the get->copy->put sequences here need
     * this lock to not lose updates. Published lists are replaced wholesale
     * (copy-on-write), so unlocked readers of an already-fetched list stay safe.
     */
    private final Object cacheLock = new Object();

    /**
     * Bumped by {@link #clearCachedMessages()} under {@link #cacheLock}.
     * Paths whose payload was produced OUTSIDE the lock (fetched REST pages,
     * realtime rows already handed to us) capture the generation before
     * publishing and drop their result when a clear happened in between, so a
     * logout can never leave freshly-fetched rows in the wiped cache.
     */
    private int cacheGeneration;

    public MessageRepository(CreangerAuthEngine engine, CreangerChatApiClient chatApi) {
        this(engine, chatApi, null);
    }

    public MessageRepository(CreangerAuthEngine engine, CreangerChatApiClient chatApi,
                             @androidx.annotation.Nullable CreangerMediaUploadClient mediaUploadClient) {
        this.engine = engine;
        this.chatApi = chatApi;
        this.mediaUploadClient = mediaUploadClient;
    }

    public boolean isAuthenticated() {
        return engine.getState() == AuthState.AUTHENTICATED;
    }

    public AuthState getState() {
        return engine.getState();
    }

    /** Cached messages (newest first) for a chat of the current user, or empty. */
    public List<CreangerMessage> getCachedMessages(String chatId) {
        List<CreangerMessage> list = messagesCache.get(key(chatId));
        return list != null ? list : new ArrayList<>();
    }

    /**
     * Fetches the newest page of a chat's messages and caches it. Returns the
     * page including {@code nextOlderSeq} (the chat_seq cursor for the next
     * older page).
     */
    public MessagePage refreshMessages(String chatId, int limit)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        MessagePage page = chatApi.listMessages(access, chatId, limit, null);
        // Merge: keep any local pending copies, dedupe by id, preserve newest-first.
        synchronized (cacheLock) {
            int gen = cacheGeneration;
            List<CreangerMessage> all = merge(cacheOf(chatId), page.messages);
            if (gen == cacheGeneration) {
                messagesCache.put(key(chatId), all);
            }
        }
        return new MessagePage(page.messages, page.nextOlderSeq, page.hasMore);
    }

    /**
     * Fetches an older page of history using the {@code beforeSeq} cursor.
     * Appends to the cache (older messages appended at the tail / older end).
     *
     * @return the older page; {@code hasMore == false} and empty messages when
     *         there is no more history
     */
    public MessagePage loadOlderMessages(String chatId, long beforeSeq, int limit)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        MessagePage page = chatApi.listMessages(access, chatId, limit, beforeSeq);
        if (!page.messages.isEmpty()) {
            synchronized (cacheLock) {
                int gen = cacheGeneration;
                List<CreangerMessage> all = merge(cacheOf(chatId), page.messages);
                if (gen == cacheGeneration) {
                    messagesCache.put(key(chatId), all);
                }
            }
        }
        return page;
    }

    /**
     * Sends a text message with optimistic local state:
     *  1. a pending local copy is immediately entered into the per-account cache
     *     (client sees it instantly, {@code isLocal == true})
     *  2. the server {@code send_text_message} RPC is called with the same
     *     {@code clientMessageId} for idempotency
     *  3. on success the server-confirmed row replaces the pending copy
     *
     * {@code replyToMessageId} is the authoritative Creanger UUID of the
     * replied-to message (never a synthetic Telegram id) and is preserved on
     * both the optimistic copy and the confirmed row.
     *
     * A retry with a {@code clientMessageId} that already has a pending or
     * confirmed copy does NOT overwrite it (first write wins, matching
     * migration 022): the existing cached message id/content is preserved and
     * the idempotent RPC returns the original server id.
     *
     * @return the message id the server confirmed
     */
    public String sendTextMessage(String chatId, String clientMessageId, String content,
                                  @Nullable String replyToMessageId)
            throws IOException, CreangerApiException {
        return sendTextMessage(chatId, clientMessageId, content, replyToMessageId, null);
    }

    /**
     * Sends a text message, carrying {@code richText} (TL_iv.RichText JSON)
     * through the canonical 5-argument RPC contract when formatting is
     * available; plain-text callers use the overload above.
     */
    public String sendTextMessage(String chatId, String clientMessageId, String content,
                                  @Nullable String replyToMessageId,
                                  @Nullable org.json.JSONObject richText)
            throws IOException, CreangerApiException {
        List<CreangerMessage> current = cacheOf(chatId);
        CreangerMessage existing = findByClientId(current, clientMessageId);

        String ownerId = ownerId();
        if (existing == null) {
            CreangerMessage local = new CreangerMessage(
                    "local-" + clientMessageId, chatId, ownerId, MessageType.TEXT, content, MessageStatus.PENDING,
                    clientMessageId, null, null, null, null, null, replyToMessageId, true);
            addLocal(chatId, local);
        }

        String access = engine.requireAccessToken();
        String serverId = chatApi.sendTextMessage(access, chatId, clientMessageId, content, replyToMessageId,
                richText);

        confirmSend(chatId, clientMessageId, serverId, ownerId, existing != null);
        return serverId;
    }

    /**
     * Convenience for a plain (non-reply) text send. A retry of an existing
     * failed/pending copy reuses the cached row's reply relationship, so a
     * reply never loses its target when re-sent.
     */
    public String sendTextMessage(String chatId, String clientMessageId, String content)
            throws IOException, CreangerApiException {
        String replyTo = findByClientId(cacheOf(chatId), clientMessageId) != null
                ? findByClientId(cacheOf(chatId), clientMessageId).replyToMessageId : null;
        return sendTextMessage(chatId, clientMessageId, content, replyTo);
    }

    /**
     * Uploads an image's bytes through the Creanger Media API
     * ({@code /v1/media/upload-image}, which stores the image via ImageBB
     * server-side), returning the provider-neutral metadata that a
     * {@code send_media_message} attachment row needs (storage provider + key +
     * public URL). This is the ONLY place the raw bytes of a media send travel;
     * the send itself persists metadata only. Requires the repository to be
     * constructed with the {@link CreangerMediaUploadClient} (production wiring
     * in {@code CreangerAuth}).
     */
    public UploadedMedia uploadImage(String chatId, String fileName, byte[] imageData, String mimeType)
            throws IOException, CreangerApiException {
        if (mediaUploadClient == null) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "media upload is not wired in this build", null, 0));
        }
        String access = engine.requireAccessToken();
        return mediaUploadClient.uploadImage(access, chatId, fileName, imageData, mimeType);
    }

    /**
     * Uploads a video's bytes to the Creanger Media API
     * ({@code /v1/media/upload-video}, which stores the video
     * via Cloudinary server-side and returns the provider-neutral metadata that a
     * {@code send_media_message} attachment row needs (storage provider + key +
     * public URL + thumbnail URL). This is the ONLY place the raw bytes of a
     * video send travel; the send itself persists metadata only. Requires the
     * repository to be constructed with the {@link CreangerMediaUploadClient}
     * (production wiring in {@code CreangerAuth}).
     */
    public UploadedMedia uploadVideo(String chatId, String fileName, byte[] videoData, String mimeType)
            throws IOException, CreangerApiException {
        if (mediaUploadClient == null) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "media upload is not wired in this build", null, 0));
        }
        String access = engine.requireAccessToken();
        return mediaUploadClient.uploadVideo(access, chatId, fileName, videoData, mimeType);
    }

    /**
     * Uploads audio bytes (mp3/m4a/wav/ogg) to the Creanger Media API
     * ({@code /v1/media/upload-audio}, which stores the audio via Cloudinary
     * server-side and returns the provider-neutral metadata — including the
     * server-computed {@code durationMs} — that a {@code send_media_message}
     * attachment row needs). This is the ONLY place the raw bytes of an
     * audio/voice send travel; the send itself persists metadata only. Requires
     * the repository to be constructed with the {@link CreangerMediaUploadClient}
     * (production wiring in {@code CreangerAuth}).
     */
    public UploadedMedia uploadAudio(String chatId, String fileName, byte[] audioData, String mimeType)
            throws IOException, CreangerApiException {
        if (mediaUploadClient == null) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "media upload is not wired in this build", null, 0));
        }
        String access = engine.requireAccessToken();
        return mediaUploadClient.uploadAudio(access, chatId, fileName, audioData, mimeType);
    }

    /**
     * Uploads arbitrary document bytes (pdf/zip/text/...) to the Creanger Media
     * API ({@code /v1/media/upload-document}, which stores the document via
     * Cloudinary {@code raw} server-side and returns provider-neutral metadata)
     * that a {@code send_media_message} attachment row needs. The upload
     * {@code fileName} is sanitized into the Cloudinary {@code public_id}, so
     * the returned {@code storageKey} preserves the displayable file name (the
     * adapter renders documents from {@code MediaAttachment.storageKey}).
     * Requires the repository to be constructed with the
     * {@link CreangerMediaUploadClient} (production wiring in {@code CreangerAuth}).
     */
    public UploadedMedia uploadDocument(String chatId, String fileName, byte[] documentData, String mimeType)
            throws IOException, CreangerApiException {
        if (mediaUploadClient == null) {
            throw new CreangerApiException(200, new ApiError(ApiError.INTERNAL_ERROR,
                    "media upload is not wired in this build", null, 0));
        }
        String access = engine.requireAccessToken();
        return mediaUploadClient.uploadDocument(access, chatId, fileName, documentData, mimeType);
    }

    /**
     * Sends a media message with optimistic local state, identical to
     * {@link #sendTextMessage} except that the row carries a {@code messageType}
     * (one of {@code MessageType.IMAGE/VIDEO/DOCUMENT/AUDIO/VOICE}), an optional
     * caption (the message's {@code content}) and the {@link MediaAttachment}
     * metadata list:
     *
     *  1. a pending local copy (isLocal == true, attachments + type preserved)
     *     is immediately entered into the per-account cache
     *  2. the server {@code send_media_message} RPC (migration 029) runs with
     *     the same {@code clientMessageId} for idempotency; it persists the
     *     message, its media rows and its attachment rows atomically
     *  3. on success the server-confirmed row replaces the pending copy,
     *     preserving the same messageType + attachments
     *
     * A retry never duplicates or mutates: the RPC returns the original id and
     * the cached row's type/attachments/caption are kept verbatim.
     *
     * @return the message id the server confirmed
     */
    public String sendMediaMessage(String chatId, String clientMessageId, String messageType,
                                   @Nullable String caption, @Nullable String replyToMessageId,
                                   List<MediaAttachment> attachments)
            throws IOException, CreangerApiException {
        List<CreangerMessage> current = cacheOf(chatId);
        CreangerMessage existing = findByClientId(current, clientMessageId);

        String ownerId = ownerId();
        if (existing == null) {
            CreangerMessage local = new CreangerMessage(
                    "local-" + clientMessageId, chatId, ownerId, messageType, caption, MessageStatus.PENDING,
                    clientMessageId, null, null, null, null, null, replyToMessageId, true,
                    attachments);
            addLocal(chatId, local);
        }

        String access = engine.requireAccessToken();
        String serverId = chatApi.sendMediaMessage(access, chatId, clientMessageId, messageType,
                caption, replyToMessageId, attachments);

        confirmSend(chatId, clientMessageId, serverId, ownerId, existing != null);
        return serverId;
    }

    /** Convenience for a plain (non-reply) media send. */
    public String sendMediaMessage(String chatId, String clientMessageId, String messageType,
                                   @Nullable String caption, List<MediaAttachment> attachments)
            throws IOException, CreangerApiException {
        return sendMediaMessage(chatId, clientMessageId, messageType, caption, null, attachments);
    }

    /**
     * Ingests a row that arrived over Realtime into the per-account cache,
     * reusing the same merge/dedup (by id and by {@code client_message_id}) as
     * the REST pages — so a message that is both pushed and later polled never
     * duplicates. Cross-path duplicates (a send confirmation the client already
     * holds; a refresh that re-lists a pushed row) collapse here.
     *
     * @return {@code true} when the row is new to the display (its id was not
     *         already held as a server row — which also covers a Realtime row
     *         replacing the user's own pending optimistic copy, whose local id
     *         differs), {@code false} when it was already cached — the caller
     *         then skips any re-render
     */
    public boolean ingestRealtime(String chatId, CreangerMessage message) {
        if (message == null || message.id == null || message.chatId == null) {
            return false;
        }
        synchronized (cacheLock) {
            // A clear() that raced this frame wins: the row was produced outside
            // the lock, so publishing it into a freshly wiped cache would
            // resurrect data the logout just removed.
            int gen = cacheGeneration;
            // Realtime rows deliberately carry NO attachment payload (the 020
            // publication excludes media/message_attachments). When the incoming
            // media row already exists in the cache WITH metadata — e.g. the echo
            // of the user's own just-confirmed image send, or a row that arrived
            // via the REST projection earlier — the held metadata is preserved, so
            // the bubble never flashes blank mid-ingest. Rows we truly don't know
            // yet keep the empty list (they converge via the REST snapshot,
            // recoverAttachments).
            if (message.isMedia() && message.attachments.isEmpty()) {
                CreangerMessage held = findById(cacheOf(chatId), message.id);
                if (held != null && !held.attachments.isEmpty()) {
                    message = withAttachments(message, held.attachments);
                }
            }
            boolean alreadyShown = containsServerRowById(cacheOf(chatId), message.id);
            List<CreangerMessage> all = merge(cacheOf(chatId), java.util.Collections.singletonList(message));
            if (gen != cacheGeneration) {
                return false;
            }
            messagesCache.put(key(chatId), all);
            return !alreadyShown;
        }
    }

    /**
     * Forward sync used to recover missed messages after a Realtime reconnect:
     * every row with {@code chat_seq > afterSeq} (oldest first) via the
     * {@code get_messages_since} RPC, merged into the cache. This is the
     * database cursor sync that makes Realtime strictly best-effort.
     *
     * @return the recovered rows (ascending {@code chat_seq}), for reporting
     */
    public List<CreangerMessage> syncSince(String chatId, long afterSeq, int limit)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        List<CreangerMessage> recovered = chatApi.getMessagesSince(access, chatId, afterSeq, limit);
        if (!recovered.isEmpty()) {
            synchronized (cacheLock) {
                int gen = cacheGeneration;
                List<CreangerMessage> all = merge(cacheOf(chatId), recovered);
                if (gen == cacheGeneration) {
                    messagesCache.put(key(chatId), all);
                }
            }
        }
        return recovered;
    }

    /**
     * Applies a server-side edit row received over Realtime. The incoming row
     * is authoritative (content/edited_at from the DB): it replaces the cached
     * row by id. If the incoming edit carries the SAME content as what is
     * already cached, nothing changed — this collapses the echo of the user's
     * own optimistic edit (no duplicate UI render) while still converging a
     * genuine server-side edit.
     *
     * @return {@code true} when the display row actually changed
     */
    public boolean applyRealtimeEdit(String chatId, CreangerMessage message) {
        if (message == null || message.id == null || message.chatId == null) {
            return false;
        }
        synchronized (cacheLock) {
            String k = key(chatId);
            List<CreangerMessage> current = cacheOf(chatId);
            // Edit frames and the 028 recovery projection carry NO attachments.
            // Preserve the held metadata (mirrors the ingestRealtime guard), so a
            // caption edit on an image/video never blanks the bubble until the
            // next recoverAttachments snapshot prunes it for real.
            if (message.isMedia() && message.attachments.isEmpty()) {
                CreangerMessage held = findById(current, message.id);
                if (held != null && !held.attachments.isEmpty()) {
                    message = withAttachments(message, held.attachments);
                }
            }
            boolean changed = false;
            List<CreangerMessage> updated = new ArrayList<>();
            for (CreangerMessage m : current) {
                if (message.id.equals(m.id)) {
                    if (!sameEdit(m, message)) {
                        changed = true;
                    }
                    updated.add(message);
                } else {
                    updated.add(m);
                }
            }
            if (!changed) {
                return false;
            }
            messagesCache.put(k, updated);
            return true;
        }
    }

    /**
     * Applies a server-side deletion (soft-delete tombstone or DELETE event):
     * removes the row from the cache by id. Idempotent — deleting a row that
     * is not (or no longer) cached is a no-op.
     *
     * @return {@code true} when a cached row was actually removed
     */
    public boolean applyRealtimeDelete(String chatId, String messageId) {
        if (messageId == null) {
            return false;
        }
        synchronized (cacheLock) {
            String k = key(chatId);
            List<CreangerMessage> current = cacheOf(chatId);
            List<CreangerMessage> updated = new ArrayList<>();
            boolean removed = false;
            for (CreangerMessage m : current) {
                if (messageId.equals(m.id)) {
                    removed = true; // drop the row (pending copies have a different id)
                } else {
                    updated.add(m);
                }
            }
            if (!removed) {
                return false;
            }
            messagesCache.put(k, updated);
            // A deleted message can no longer carry reactions.
            reactionsByMessage.remove(messageReactionKey(chatId, messageId));
            return true;
        }
    }

    /**
     * Optimistic text edit, phase 1 (main thread): the cached row is updated
     * to {@code newContent} immediately, and the pre-edit row is snapshotted
     * for {@link #rollbackEdit}. The caller then runs
     * {@link #completeEdit} (the author-only {@code edit_message} RPC) and
     * calls {@link #rollbackEdit} on failure. Authorization is enforced by the
     * RPC/RLS server-side; the message id is a Creanger UUID that is never
     * rewritten here.
     *
     * @return {@code true} when a cached row was optimistically updated
     */
    public boolean applyOptimisticEdit(String chatId, String messageId, String newContent) {
        if (messageId == null) {
            return false;
        }
        synchronized (cacheLock) {
            String k = key(chatId);
            String rk = rollbackKey(chatId, messageId);
            List<CreangerMessage> current = cacheOf(chatId);
            List<CreangerMessage> updated = new ArrayList<>();
            boolean changed = false;
            for (CreangerMessage m : current) {
                if (messageId.equals(m.id)) {
                    pendingEditRollback.putIfAbsent(rk, m); // first write wins for rollback
                    if (!sameEdit(m, newContent)) {
                        changed = true;
                    }
                    updated.add(withEditedContent(m, newContent));
                } else {
                    updated.add(m);
                }
            }
            if (changed) {
                messagesCache.put(k, updated);
            }
            return changed;
        }
    }

    /**
     * Optimistic text edit, phase 2 (background): runs the author-only
     * {@code edit_message} RPC. On success the optimistic content is kept and
     * the rollback snapshot discarded (the server row — exact edited_at —
     * converges via the Realtime echo or the next refresh/recovery).
     *
     * @return the message id the server confirmed
     */
    public String completeEdit(String chatId, String messageId, String newContent)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        String serverId = chatApi.editMessage(access, messageId, newContent);
        pendingEditRollback.remove(rollbackKey(chatId, messageId));
        return serverId;
    }

    /** Restores the pre-edit row after a failed edit RPC (rollback). */
    public void rollbackEdit(String chatId, String messageId) {
        synchronized (cacheLock) {
            String rk = rollbackKey(chatId, messageId);
            CreangerMessage snapshot = pendingEditRollback.remove(rk);
            if (snapshot == null) {
                return;
            }
            String k = key(chatId);
            List<CreangerMessage> current = cacheOf(chatId);
            List<CreangerMessage> updated = new ArrayList<>();
            boolean replaced = false;
            for (CreangerMessage m : current) {
                if (messageId.equals(m.id)) {
                    updated.add(snapshot);
                    replaced = true;
                } else {
                    updated.add(m);
                }
            }
            if (replaced) {
                messagesCache.put(k, updated);
            } else if (snapshot.isLocal) {
                updated.add(snapshot);
                messagesCache.put(k, merge(updated, java.util.Collections.emptyList()));
            }
        }
    }

    /**
     * Optimistic delete, phase 1 (main thread): the cached row is removed
     * immediately and snapshotted for {@link #rollbackDelete}. The caller then
     * runs {@link #completeDelete} and calls {@link #rollbackDelete} on
     * failure. Deletion follows the schema's soft-delete model
     * ({@code messages.deleted_at} via the author-only {@code delete_message}
     * RPC — never a hard delete and never a client-supplied sender id).
     *
     * @return {@code true} when a cached row was optimistically removed
     */
    public boolean applyOptimisticDelete(String chatId, String messageId) {
        if (messageId == null) {
            return false;
        }
        synchronized (cacheLock) {
            String k = key(chatId);
            String rk = rollbackKey(chatId, messageId);
            List<CreangerMessage> current = cacheOf(chatId);
            List<CreangerMessage> updated = new ArrayList<>();
            boolean removed = false;
            for (CreangerMessage m : current) {
                if (messageId.equals(m.id)) {
                    pendingDeleteRollback.putIfAbsent(rk, m);
                    removed = true;
                } else {
                    updated.add(m);
                }
            }
            if (removed) {
                messagesCache.put(k, updated);
            }
            return removed;
        }
    }

    /**
     * Optimistic delete, phase 2 (background): runs the author-only
     * {@code delete_message} RPC (soft delete). On success the row stays
     * removed and the rollback snapshot is discarded.
     *
     * @return the message id the server confirmed
     */
    public String completeDelete(String chatId, String messageId)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        String serverId = chatApi.deleteMessage(access, messageId);
        pendingDeleteRollback.remove(rollbackKey(chatId, messageId));
        return serverId;
    }

    /**
     * Bulk delete, phase 2 (background): runs the author-only
     * {@code bulk_delete_messages} RPC (migration 040 — one atomic call that
     * soft-deletes only the caller's own rows) for ids previously removed via
     * {@link #applyOptimisticDelete}. On success the rollback snapshots of the
     * confirmed ids are discarded and the confirmed id list is returned. Rows
     * the server skipped (not own) stay removed locally; the caller refreshes
     * to reconcile. Transport failure throws without touching the cache — the
     * caller rolls back per id via {@link #rollbackDelete}.
     */
    public List<String> completeBulkDelete(String chatId, List<String> messageIds)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        List<String> confirmed = chatApi.bulkDeleteMessages(access, messageIds);
        if (confirmed == null) {
            return new ArrayList<>();
        }
        for (String id : confirmed) {
            pendingDeleteRollback.remove(rollbackKey(chatId, id));
        }
        return confirmed;
    }

    // ---- close friends (migration 040 audience list) ----

    /** The caller's close-friends audience ids (owner-scoped, never null). */
    public List<String> listCloseFriendIds() throws IOException, CreangerApiException {
        return chatApi.listCloseFriendIds(engine.requireAccessToken());
    }

    /** Adds a user to the caller's close-friends audience. */
    public void addCloseFriend(String friendId) throws IOException, CreangerApiException {
        chatApi.addCloseFriend(engine.requireAccessToken(), friendId);
    }

    /** Removes a user from the caller's close-friends audience. */
    public void removeCloseFriend(String friendId) throws IOException, CreangerApiException {
        chatApi.removeCloseFriend(engine.requireAccessToken(), friendId);
    }

    /** Restores the pre-delete row after a failed delete RPC (rollback). */
    public void rollbackDelete(String chatId, String messageId) {
        synchronized (cacheLock) {
            String rk = rollbackKey(chatId, messageId);
            CreangerMessage snapshot = pendingDeleteRollback.remove(rk);
            if (snapshot == null) {
                return;
            }
            String k = key(chatId);
            List<CreangerMessage> updated = new ArrayList<>();
            updated.add(snapshot);
            for (CreangerMessage m : cacheOf(chatId)) {
                if (!messageId.equals(m.id)) {
                    updated.add(m);
                }
            }
            messagesCache.put(k, merge(updated, java.util.Collections.emptyList()));
        }
    }

    /**
     * Cancels a still-pending local send: removes the PENDING optimistic copy
     * matching {@code clientMessageId} from the per-account cache. Local-only —
     * a pending copy is not yet server-confirmed, so no RPC is issued; if the
     * send already reached the server, the confirmed row returns via the
     * Realtime/REST reconcile (idempotent, deduped by {@code client_message_id}).
     * Only local pending rows are ever removed — confirmed server rows and
     * other messages/chats are untouched (the cache is keyed per
     * {@code chatId|owner}).
     *
     * @return {@code true} when a pending copy was actually removed
     */
    public boolean cancelPending(String chatId, String clientMessageId) {
        if (clientMessageId == null) {
            return false;
        }
        synchronized (cacheLock) {
            String k = key(chatId);
            List<CreangerMessage> current = cacheOf(chatId);
            List<CreangerMessage> updated = new ArrayList<>();
            boolean removed = false;
            for (CreangerMessage m : current) {
                if (m.isLocal && clientMessageId.equals(m.clientMessageId)) {
                    removed = true;
                } else {
                    updated.add(m);
                }
            }
            if (removed) {
                messagesCache.put(k, updated);
            }
            return removed;
        }
    }

    /**
     * Applies a recipient-driven delivery/read status change received over
     * Realtime (migration 026). The status on the cached row is advanced
     * monotonically ({@code sent < delivered < read}); a regression or an
     * exact repeat is a no-op, so out-of-order events and Realtime echoes
     * never move a read message backwards or retrigger a render.
     *
     * @return {@code true} when the display row's status actually advanced
     */
    public boolean applyRealtimeStatus(String chatId, CreangerMessage message) {
        if (message == null || message.id == null || message.chatId == null) {
            return false;
        }
        return applyStatus(chatId, message.id, message.status, message.updatedAt);
    }

    /**
     * Marks the delivery/read state of an incoming message through the
     * recipient-only {@code mark_message_status} RPC (migration 026). Applied
     * to the cache only on success (the server is the authority); the
     * {@code updated_at} watermark advances so a reconnect recovery never
     * re-applies it.
     *
     * @return the message id the server confirmed
     */
    public String markMessageStatus(String chatId, String messageId, String status)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        String serverId = chatApi.markMessageStatus(access, messageId, status);
        // The RPC was authorized + advanced server-side; reflect it locally.
        // The status recovery watermark is deliberately NOT advanced here: the
        // RPC response carries no server updated_at, and stamping the CLIENT
        // clock into the server-domain cursor would let a skewed clock skip
        // every future recovered row. Recovery replaying our own ack is
        // idempotent (monotonic apply), so leaving the cursor untouched is the
        // safe direction.
        applyStatus(chatId, messageId, status, null);
        return serverId;
    }

    /**
     * Recovers delivery/read status changes missed while Realtime was down via
     * the {@code get_message_statuses_since} RPC (migration 026). Each returned
     * status is applied monotonically to the matching cached row (rows not in
     * the loaded window are skipped). When {@code afterUpdatedAt} is
     * {@code null}, the repository's per-chat watermark is used so consecutive
     * reconnects resume exactly where the last one stopped; a fresh session
     * with no watermark recovers everything.
     *
     * @return the recovered status updates (ascending {@code updated_at})
     */
    public List<MessageStatusUpdate> recoverMessageStatuses(String chatId,
                                                            @Nullable String afterUpdatedAt, int limit)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        String cursor = afterUpdatedAt != null ? afterUpdatedAt : lastStatusUpdatedAt(chatId);
        List<MessageStatusUpdate> updates =
                chatApi.getMessageStatusesSince(access, chatId, cursor, limit);
        for (MessageStatusUpdate update : updates) {
            if (update.messageId == null) {
                continue;
            }
            applyStatus(chatId, update.messageId, update.status, update.updatedAt);
        }
        return updates;
    }

    /**
     * Recovers edits/soft-deletes missed while Realtime was down via the
     * {@code get_message_changes_since} RPC (migration 028). Edits and deletes
     * never bump {@code chat_seq}, so neither {@link #syncSince} (insert cursor)
     * nor {@link #recoverMessageStatuses} (status cursor) can recover them; the
     * {@code updated_at} watermark closes that gap:
     *
     *  - an edit row (deleted_at NULL) replaces the cached row by id via
     *    {@link #applyRealtimeEdit}
     *  - a tombstone row (deleted_at set) drops the cached row by id via
     *    {@link #applyRealtimeDelete}
     *  - rows outside the loaded window are skipped but the watermark still
     *    advances, so a recovery never re-fetches them (their content converges
     *    on the next page refresh)
     *
     * When {@code afterUpdatedAt} is {@code null} the repository's per-chat
     * watermark is used so consecutive reconnects resume exactly where the
     * last one stopped; a fresh session with no watermark recovers everything.
     *
     * @return the recovered change rows (ascending {@code updated_at})
     */
    public List<CreangerMessage> recoverMessageChanges(String chatId,
                                                       @Nullable String afterUpdatedAt, int limit)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        String cursor = afterUpdatedAt != null ? afterUpdatedAt : lastMessageChangeUpdatedAt(chatId);
        List<CreangerMessage> changes = chatApi.getMessageChangesSince(access, chatId, cursor, limit);
        for (CreangerMessage change : changes) {
            if (change == null || change.id == null || change.updatedAt == null) {
                continue;
            }
            // The watermark always tracks the newest updated_at observed, even
            // when the row could not be applied (not in the loaded window or a
            // duplicate), so a recovery never re-fetches it.
            trackMessageChangeUpdatedAt(chatId, change.updatedAt);
            if (change.deletedAt != null) {
                applyRealtimeDelete(chatId, change.id);
            } else {
                applyRealtimeEdit(chatId, change);
            }
        }
        return changes;
    }

    /** Clears the local message cache (local logout / account isolation). */
    public void clearCachedMessages() {
        synchronized (cacheLock) {
            cacheGeneration++;
            messagesCache.clear();
            pendingEditRollback.clear();
            pendingDeleteRollback.clear();
            statusUpdatedAtByChat.clear();
            messageChangeUpdatedAtByChat.clear();
            reactionsByMessage.clear();
        }
    }

    // ---- reactions ----

    /**
     * Applies a realtime reaction row to the per-message reaction state
     * (migration 027). {@code INSERT}/{@code UPDATE} with {@code added == true}
     * adds the reacting user (a user may hold several reactions per message;
     * each distinct reaction is a separate entry); {@code DELETE} with
     * {@code added == false} removes them. Set semantics make the operation
     * idempotent: a re-delivered add/remove collapses to {@code false} (no
     * re-render) and an out-of-order pair never corrupts counts.
     *
     * @return {@code true} when the reaction state actually changed
     */
    public boolean applyRealtimeReaction(String chatId, MessageReaction reaction, boolean added) {
        if (reaction == null || reaction.messageId == null || reaction.reaction == null
                || reaction.userId == null) {
            return false;
        }
        // Reactions carry no chat id, so a row whose message is outside the
        // loaded window is dropped here (mirrors applyStatus).
        if (!isMessageKnown(chatId, reaction.messageId)) {
            return false;
        }
        synchronized (cacheLock) {
            Map<String, ReactionState> byMessage =
                    reactionsByMessage.computeIfAbsent(messageReactionKey(chatId, reaction.messageId),
                            k -> new ConcurrentHashMap<>());
            ReactionState state = byMessage.computeIfAbsent(reactionKey(reaction),
                    k -> new ReactionState(reaction));
            return added ? state.userIds.add(reaction.userId) : state.userIds.remove(reaction.userId);
        }
    }

    /**
     * REPLACES the reaction state for every message of the chat with a
     * snapshot fetched from the server. Used after a page load and after a
     * Realtime reconnect: reactions can be DELETED, so a watermark cursor
     * cannot reconstruct removals — the full current row set for the loaded
     * message window is applied as authoritative state instead.
     */
    public void setReactions(String chatId, List<MessageReaction> reactions) {
        String owner = ownerId();
        String chatPrefix = chatId + "|" + owner + "|";
        synchronized (cacheLock) {
            reactionsByMessage.entrySet().removeIf(e -> e.getKey().startsWith(chatPrefix));
            if (reactions == null) {
                return;
            }
            for (MessageReaction r : reactions) {
                if (r == null || r.messageId == null || r.reaction == null || r.userId == null) {
                    continue;
                }
                Map<String, ReactionState> byMessage =
                        reactionsByMessage.computeIfAbsent(messageReactionKey(chatId, r.messageId),
                                k -> new ConcurrentHashMap<>());
                byMessage.computeIfAbsent(reactionKey(r), k -> new ReactionState(r)).userIds.add(r.userId);
            }
        }
    }

    /**
     * Aggregated reaction summaries for one message of the current user's
     * chat, in an arbitrary but stable order. {@code count} is the number of
     * DISTINCT users per reaction; {@code chosen} marks the current user's own
     * reaction. Empty when the message has no reactions (or is not cached).
     */
    public List<ReactionSummary> getReactionSummaries(String chatId, String messageId) {
        synchronized (cacheLock) {
            Map<String, ReactionState> byMessage = reactionsByMessage.get(messageReactionKey(chatId, messageId));
            if (byMessage == null || byMessage.isEmpty()) {
                return new ArrayList<>();
            }
            List<ReactionSummary> out = new ArrayList<>();
            for (ReactionState state : byMessage.values()) {
                if (state.userIds.isEmpty()) {
                    continue;
                }
                out.add(new ReactionSummary(state.reaction, state.isCustomEmoji, state.customEmojiId,
                        state.userIds.size(), state.userIds.contains(ownerId())));
            }
            return out;
        }
    }

    /**
     * True when {@code messageId} is held in the chat's loaded cache. Realtime
     * reaction rows carry no {@code chat_id}, so the repository answers the
     * "is this reaction for the open chat and the loaded window?" question:
     * rows for unknown messages are dropped before they ever reach the UI.
     */
    public boolean isMessageKnown(String chatId, String messageId) {
        if (messageId == null) {
            return false;
        }
        return containsId(cacheOf(chatId), messageId);
    }

    /**
     * Adds the current user's reaction to a message through the idempotent
     * {@code add_reaction} RPC (migration 027). The server is the authority —
     * the local reaction state converges via the realtime echo (or the next
     * snapshot).
     *
     * @return the message id the server confirmed
     */
    public String addReaction(String chatId, String messageId, String reaction)
            throws IOException, CreangerApiException {
        return addReaction(chatId, messageId, reaction, false, null);
    }

    /**
     * Same as {@link #addReaction(String, String, String)}, carrying the custom
     * {@link MessageReaction} fields so a custom/Premium reaction reaches the
     * {@code add_reaction} RPC's {@code p_is_custom_emoji}/{@code
     * p_custom_emoji_id} arguments.
     */
    public String addReaction(String chatId, String messageId, String reaction,
                              boolean isCustomEmoji, String customEmojiId)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        return chatApi.addReaction(access, messageId, reaction, isCustomEmoji, customEmojiId);
    }

    /**
     * Removes the current user's reaction from a message through the idempotent
     * {@code remove_reaction} RPC (migration 027). Only the caller's own row is
     * ever deleted server-side.
     *
     * @return the message id the server confirmed
     */
    public String removeReaction(String chatId, String messageId, String reaction)
            throws IOException, CreangerApiException {
        return removeReaction(chatId, messageId, reaction, false, null);
    }

    /**
     * Same as {@link #removeReaction(String, String, String)}, mirroring the
     * custom {@link MessageReaction} fields used when the reaction was added so
     * the {@code remove_reaction} RPC finds the caller's exact row.
     */
    public String removeReaction(String chatId, String messageId, String reaction,
                                 boolean isCustomEmoji, String customEmojiId)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        return chatApi.removeReaction(access, messageId, reaction, isCustomEmoji, customEmojiId);
    }

    /**
     * Reconnect/page-load reaction recovery: fetches the CURRENT rows for the
     * {@code messageIds} in the loaded window via the REST data plane
     * ({@code message_reactions?message_id=in.(...)}) and replaces the chat's
     * reaction state with that authoritative snapshot. Reactions can be
     * deleted, so a watermark cursor cannot reconstruct removals — a full
     * replace of the loaded window is the recovery mechanism.
     */
    public void recoverReactions(String chatId, List<String> messageIds)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        List<MessageReaction> rows = (messageIds == null || messageIds.isEmpty())
                ? new ArrayList<>() : chatApi.getReactions(access, messageIds);
        setReactions(chatId, rows);
    }

    // ---- attachments ----

    /**
     * Reconnect/page-load attachment recovery: fetches the CURRENT attachment
     * rows for the {@code messageIds} in the loaded window via the REST data
     * plane ({@code message_attachments?message_id=in.(...)} with the
     * {@code media} row embedded) and attaches them onto the cached messages.
     *
     * Rows that arrived WITHOUT media metadata — Realtime inserts (the realtime
     * publication deliberately excludes media/message_attachments, migration
     * 020) and the {@code get_messages_since} / {@code get_message_changes_since}
     * RPC projections — are exactly what this fills. Rows that already carry
     * attachments (the embedded REST list) are overwritten with the
     * authoritative snapshot, and a message with no current attachment rows has
     * stale metadata pruned.
     */
    public void recoverAttachments(String chatId, List<String> messageIds)
            throws IOException, CreangerApiException {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        String access = engine.requireAccessToken();
        List<MediaAttachment> rows = chatApi.getAttachments(access, messageIds);
        attachMedia(chatId, rows);
    }

    /**
     * Applies a set of attachment rows onto the chat's cached messages (matched
     * by {@code message_id}): each cached message's attachments are replaced by
     * its rows from {@code rows}. Messages with no rows get their attachments
     * cleared (snapshot semantics — stale metadata must not linger).
     */
    public void attachMedia(String chatId, List<MediaAttachment> rows) {
        synchronized (cacheLock) {
            String k = key(chatId);
            List<CreangerMessage> current = cacheOf(chatId);
            if (current.isEmpty()) {
                return;
            }
            Map<String, List<MediaAttachment>> byMessage = new java.util.LinkedHashMap<>();
            if (rows != null) {
                for (MediaAttachment r : rows) {
                    if (r == null || r.messageId == null) {
                        continue;
                    }
                    byMessage.computeIfAbsent(r.messageId, x -> new ArrayList<>()).add(r);
                }
            }
            List<CreangerMessage> updated = new ArrayList<>();
            for (CreangerMessage m : current) {
                updated.add(withAttachments(m, byMessage.get(m.id)));
            }
            messagesCache.put(k, updated);
        }
    }

    /** A copy of {@code m} with its {@code attachments} replaced. */
    private static CreangerMessage withAttachments(CreangerMessage m, List<MediaAttachment> attachments) {
        return new CreangerMessage(
                m.id, m.chatId, m.senderId, m.messageType, m.content, m.status,
                m.clientMessageId, m.chatSeq, m.createdAt, m.editedAt, m.deletedAt,
                m.updatedAt, m.replyToMessageId, m.isLocal, attachments);
    }

    /**
     * Full-text search within a chat via the {@code search_messages_in_chat}
     * RPC (migration 017). Returns matching messages ordered by
     * {@code created_at DESC}. Results are NOT cached — they are a
     * point-in-time snapshot for the search UI.
     */
    public List<CreangerMessage> searchMessagesInChat(String chatId, String query, int limit)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        return chatApi.searchMessagesInChat(access, chatId, query, limit);
    }

    // ---- pin/unpin messages ----

    /**
     * Pins a message by setting {@code metadata.pinned = true} on the
     * {@code messages} row. Caller must be a chat member with pin permission.
     */
    public String pinMessage(String messageId)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        return chatApi.pinMessage(access, messageId);
    }

    /**
     * Unpins a message by removing {@code metadata.pinned} from the
     * {@code messages} row. Idempotent.
     */
    public String unpinMessage(String messageId)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        return chatApi.unpinMessage(access, messageId);
    }

    /**
     * Returns all pinned messages for the given chat, ordered by
     * {@code created_at DESC} (newest pin first).
     */
    public List<CreangerMessage> getPinnedMessages(String chatId)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        return chatApi.getPinnedMessages(access, chatId);
    }

    // ---- forward messages (migration 004 message_forwards) ----

    /**
     * Forwards a Creanger message to another Creanger chat.
     * Reads the source message, creates a copy in {@code destChatId}, and
     * inserts a {@code message_forwards} record.
     *
     * @return the new forwarded message UUID in the destination chat.
     */
    public String forwardMessage(String sourceMsgId, String destChatId)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        String userId = engine.currentUser() != null ? engine.currentUser().id : null;
        if (userId == null || userId.isEmpty()) {
            throw new CreangerApiException(401, new ApiError(ApiError.UNAUTHORIZED,
                    "user not authenticated", null, 0));
        }
        return chatApi.forwardMessage(access, userId, sourceMsgId, destChatId);
    }

    // ---- edit media (replace attachment — no migration needed) ----

    /**
     * Replaces the media attachment on an existing message and optionally
     * updates its caption.  Composes from existing primitives (no new RPC).
     */
    public void replaceMediaAttachment(String messageId, String oldMediaId,
                                        String newMediaId, int position,
                                        @Nullable String caption) throws CreangerApiException {
        String access;
        try {
            access = engine.requireAccessToken();
        } catch (java.io.IOException e) {
            throw new CreangerApiException(0,
                    new ApiError(
                            ApiError.NETWORK_ERROR,
                            e.getMessage(), null, 0));
        }
        try {
            chatApi.replaceMediaAttachment(access, messageId, oldMediaId,
                    newMediaId, position, caption);
        } catch (java.io.IOException e) {
            throw new CreangerApiException(0,
                    new ApiError(
                            ApiError.NETWORK_ERROR,
                            e.getMessage(), null, 0));
        }
    }

    /**
     * Updates caption via the edit_message RPC.  Null caption is a no-op.
     */
    public void editMessageCaption(String messageId, @Nullable String caption) throws CreangerApiException {
        if (caption == null) return;
        String access;
        try {
            access = engine.requireAccessToken();
        } catch (java.io.IOException e) {
            throw new CreangerApiException(0,
                    new ApiError(
                            ApiError.NETWORK_ERROR,
                            e.getMessage(), null, 0));
        }
        try {
            chatApi.editMessageCaption(access, messageId, caption);
        } catch (java.io.IOException e) {
            throw new CreangerApiException(0,
                    new ApiError(
                            ApiError.NETWORK_ERROR,
                            e.getMessage(), null, 0));
        }
    }

    /**
     * Inserts a new media row and returns its UUID.
     */
    public String insertMedia(String storageProvider, String storageKey,
                              @Nullable String publicUrl, @Nullable String deliveryUrl,
                              String mimeType, long sizeBytes,
                              @Nullable Integer width, @Nullable Integer height,
                              @Nullable Integer duration) throws CreangerApiException {
        String access;
        try {
            access = engine.requireAccessToken();
        } catch (java.io.IOException e) {
            throw new CreangerApiException(0,
                    new ApiError(
                            ApiError.NETWORK_ERROR,
                            e.getMessage(), null, 0));
        }
        try {
            return chatApi.insertMedia(access, ownerId(), storageProvider, storageKey,
                    publicUrl, deliveryUrl, mimeType, sizeBytes, width, height, duration);
        } catch (java.io.IOException e) {
            throw new CreangerApiException(0,
                    new ApiError(
                            ApiError.NETWORK_ERROR,
                            e.getMessage(), null, 0));
        }
    }

    // ---- drafts (migration 006) ----

    /**
     * Saves (upserts) a draft for the given chat. Idempotent via the
     * {@code (user_id, chat_id, topic_id)} unique constraint. Pass null/empty
     * content to delete the draft.
     */
    public void saveDraft(String chatId, @Nullable String content,
                          @Nullable String replyToMessageId)
            throws IOException, CreangerApiException {
        saveDraft(chatId, content, replyToMessageId, null);
    }

    /**
     * Saves (upserts) a draft scoped to (owner, chat, topic). The owner id
     * comes from the authenticated session, never from caller input.
     */
    public void saveDraft(String chatId, @Nullable String content,
                          @Nullable String replyToMessageId, @Nullable String topicId)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        chatApi.saveDraft(access, ownerId(), chatId, content, replyToMessageId, topicId);
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

    /** Fetches the owner's draft for the given chat+topic, or null. */
    @Nullable
    public CreangerChatApiClient.DraftInfo getDraft(String chatId, @Nullable String topicId)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        return chatApi.getDraft(access, ownerId(), chatId, topicId);
    }

    /** Deletes the current user's draft for the given chat (idempotent). */
    public void deleteDraft(String chatId)
            throws IOException, CreangerApiException {
        deleteDraft(chatId, null);
    }

    /** Deletes the owner's draft for the given chat+topic (idempotent). */
    public void deleteDraft(String chatId, @Nullable String topicId)
            throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        chatApi.deleteDraft(access, ownerId(), chatId, topicId);
    }

    /** Logs out locally: wipes tokens + cache via the engine. */
    public void clear() {
        clearCachedMessages();
        engine.clearLocalSession();
    }

    // ---- helpers ----

    private List<CreangerMessage> cacheOf(String chatId) {
        List<CreangerMessage> list = messagesCache.get(key(chatId));
        return list != null ? list : new ArrayList<>();
    }

    private String key(String chatId) {
        return chatId + "|" + ownerId();
    }

    /** Merge key for the optimistic rollback snapshots; message-scoped. */
    private String rollbackKey(String chatId, String messageId) {
        return key(chatId) + "|" + messageId;
    }

    private String ownerId() {
        if (engine.currentUser() != null && engine.currentUser().id != null) {
            return engine.currentUser().id;
        }
        String refresh = engine.currentRefreshToken();
        return refresh != null ? refresh : "";
    }

    /**
     * Merges pending/optimistic rows with server rows into a single
     * newest-first list. Pending local copies (no {@code chatSeq}) sort to the
     * top; server rows order by {@code chatSeq} DESC (deterministic per-chat
     * order). Dedupes by id; a server row supersedes any stale local copy that
     * shares the same id or {@code client_message_id}. A server row from a more
     * recent fetch also supersedes an older cached server row with the same id
     * (so edited content/edited_at converge on the next refresh or recovery).
     * Soft-deleted rows ({@code deleted_at} set) are tombstones: they never
     * enter the cache and any cached copy with the same id is dropped.
     */
    private static List<CreangerMessage> merge(List<CreangerMessage> existing, List<CreangerMessage> incoming) {
        Map<String, CreangerMessage> byId = new java.util.LinkedHashMap<>();
        List<CreangerMessage> combined = new ArrayList<>();
        combined.addAll(existing);
        combined.addAll(incoming);
        for (CreangerMessage m : combined) {
            if (m == null) {
                continue;
            }
            if (m.deletedAt != null) {
                if (m.id != null) {
                    byId.remove(m.id); // tombstone: drop any cached copy
                }
                continue;
            }
            CreangerMessage prev = byId.get(m.id);
            if (prev == null) {
                byId.put(m.id, m);
            } else if (!m.isLocal) {
                // Server rows are authoritative and reflect the newest fetch.
                // But a server copy WITHOUT chat_seq (the just-confirmed send
                // that the RPC confirmed by id only) must NOT clobber a server
                // copy that already carries its real chat_seq (the Realtime
                // echo) — otherwise the ordering key is lost until the next
                // full reload re-sorts the list.
                boolean prevHasSeq = prev.chatSeq != null;
                boolean mHasSeq = m.chatSeq != null;
                if (prevHasSeq && !mHasSeq) {
                    // keep prev (seq-bearing); optionally upgrade attachments
                    if (prev.attachments.isEmpty() && !m.attachments.isEmpty()) {
                        byId.put(m.id, withAttachments(prev, m.attachments));
                    }
                } else if (!prevHasSeq && mHasSeq) {
                    // incoming echo carries the real seq; keep any attachment
                    // metadata the confirmed copy already held
                    byId.put(m.id, prev.attachments.isEmpty() || !m.attachments.isEmpty()
                            ? m : withAttachments(m, prev.attachments));
                } else {
                    byId.put(m.id, m);
                }
            }
        }
        // Dedupe by client_message_id too: two copies of the same send must collapse.
        Map<String, CreangerMessage> byClientId = new java.util.LinkedHashMap<>();
        for (CreangerMessage m : byId.values()) {
            if (m.clientMessageId != null) {
                CreangerMessage prev = byClientId.get(m.clientMessageId);
                if (prev == null || (prev.isLocal && !m.isLocal)) {
                    byClientId.put(m.clientMessageId, m);
                }
            } else {
                byClientId.put(m.id, m);
            }
        }
        List<CreangerMessage> result = new ArrayList<>(byClientId.values());
        result.sort(MessageRepository::compareNewestFirst);
        return result;
    }

    /** Deterministic newest-first: pending/top by no chat_seq, then chat_seq DESC. */
    private static int compareNewestFirst(CreangerMessage a, CreangerMessage b) {
        boolean aPending = a.chatSeq == null;
        boolean bPending = b.chatSeq == null;
        if (aPending != bPending) {
            return aPending ? -1 : 1; // pending (unsent) sorts to the top
        }
        if (a.chatSeq != null && b.chatSeq != null) {
            if (a.chatSeq < b.chatSeq) {
                return 1;
            }
            if (a.chatSeq > b.chatSeq) {
                return -1;
            }
        }
        String aTime = a.createdAt != null ? a.createdAt : "";
        String bTime = b.createdAt != null ? b.createdAt : "";
        return bTime.compareTo(aTime);
    }

    private void addLocal(String chatId, CreangerMessage local) {
        synchronized (cacheLock) {
            List<CreangerMessage> merged = new ArrayList<>();
            merged.add(local);
            List<CreangerMessage> all = merge(cacheOf(chatId), merged);
            messagesCache.put(key(chatId), all);
        }
    }

    private static CreangerMessage findByClientId(List<CreangerMessage> list, String clientMessageId) {
        for (CreangerMessage m : list) {
            if (m.clientMessageId != null && m.clientMessageId.equals(clientMessageId)) {
                return m;
            }
        }
        return null;
    }

    private static CreangerMessage findById(List<CreangerMessage> list, String id) {
        if (id == null) {
            return null;
        }
        for (CreangerMessage m : list) {
            if (id.equals(m.id)) {
                return m;
            }
        }
        return null;
    }

    /** A copy of {@code m} with {@code content} replaced and edit markers set. */
    private static CreangerMessage withEditedContent(CreangerMessage m, String content) {
        String now = nowIsoUtc();
        return new CreangerMessage(
                m.id, m.chatId, m.senderId, m.messageType, content, m.status,
                m.clientMessageId, m.chatSeq, m.createdAt, m.editedAt != null ? m.editedAt : now,
                m.deletedAt, now, m.replyToMessageId, m.isLocal, m.attachments);
    }

    /**
     * Current UTC time as ISO-8601 {@code yyyy-MM-dd'T'HH:mm:ss'Z'} — no
     * {@code java.time} (min SDK 21, forbidden by AGENTS.md). UTC lexicographic
     * order equals chronological order, which the newest-first cache sort and
     * the edit echo-collapse both rely on.
     */
    private static String nowIsoUtc() {
        final java.util.GregorianCalendar c = new java.util.GregorianCalendar(
                java.util.TimeZone.getTimeZone("UTC"));
        return String.format(java.util.Locale.US,
                "%04d-%02d-%02dT%02d:%02d:%02dZ",
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND));
    }

    /** True when {@code m} already carries {@code content} (an echo/no-op edit). */
    private static boolean sameEdit(CreangerMessage m, String content) {
        return (m.content == null && content == null)
                || (m.content != null && m.content.equals(content));
    }

    /**
     * True when an incoming server row carries the SAME edit state as the
     * cached row. Collapses the echo of the user's own optimistic edit (both
     * hold the new content — the server's authoritative edited_at timestamp on
     * the incoming row does NOT retrigger a render); a genuine server edit
     * always differs by content.
     */
    private static boolean sameEdit(CreangerMessage cached, CreangerMessage incoming) {
        return sameEdit(cached, incoming.content);
    }

    private static boolean containsServerRowById(List<CreangerMessage> list, String id) {
        if (id == null) {
            return false;
        }
        for (CreangerMessage m : list) {
            if (!m.isLocal && id.equals(m.id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsId(List<CreangerMessage> list, String id) {
        if (id == null) {
            return false;
        }
        for (CreangerMessage m : list) {
            if (id.equals(m.id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Advances the status of a cached row monotonically (migration 026). A
     * regression ({@code rank(new) <= rank(current)}, including an exact
     * repeat) is a no-op; only when the status actually advances is the row
     * rewritten and the {@code updated_at} watermark bumped. Rows not in the
     * cache (outside the loaded window, or a not-yet-merged send) are ignored —
     * they converge on the next refresh/recovery with the correct status.
     *
     * @return {@code true} when the display row's status actually advanced
     */
    private boolean applyStatus(String chatId, String messageId, String status,
                                @Nullable String updatedAt) {
        if (messageId == null || status == null) {
            return false;
        }
        synchronized (cacheLock) {
            // The watermark always tracks the newest updated_at we have observed,
            // even when the status itself could not advance (e.g. an out-of-order
            // delivered after a read) so a recovery never re-fetches it.
            if (updatedAt != null) {
                trackStatusUpdatedAt(chatId, updatedAt);
            }
            String k = key(chatId);
            List<CreangerMessage> current = cacheOf(chatId);
            boolean changed = false;
            List<CreangerMessage> updated = new ArrayList<>();
            for (CreangerMessage m : current) {
                if (messageId.equals(m.id) && MessageStatus.rank(status) > MessageStatus.rank(m.status)) {
                    changed = true;
                    updated.add(withStatus(m, status, updatedAt));
                } else {
                    updated.add(m);
                }
            }
            if (!changed) {
                return false;
            }
            messagesCache.put(k, updated);
            return true;
        }
    }

    /** A copy of {@code m} with {@code status} replaced and {@code updated_at} refreshed. */
    private static CreangerMessage withStatus(CreangerMessage m, String status, @Nullable String updatedAt) {
        return new CreangerMessage(
                m.id, m.chatId, m.senderId, m.messageType, m.content, status,
                m.clientMessageId, m.chatSeq, m.createdAt, m.editedAt, m.deletedAt,
                updatedAt != null ? updatedAt : m.updatedAt, m.replyToMessageId, m.isLocal,
                m.attachments);
    }

    /** Newest {@code updated_at} observed for a chat's status updates, or null. */
    private String lastStatusUpdatedAt(String chatId) {
        return statusUpdatedAtByChat.get(key(chatId));
    }

    /** Lexicographic max works for ISO-8601 UTC timestamps. */
    private void trackStatusUpdatedAt(String chatId, String updatedAt) {
        String k = key(chatId);
        synchronized (cacheLock) {
            String current = statusUpdatedAtByChat.get(k);
            if (current == null || current.compareTo(updatedAt) < 0) {
                statusUpdatedAtByChat.put(k, updatedAt);
            }
        }
    }

    /** Newest {@code updated_at} observed for a chat's edit/delete changes, or null. */
    private String lastMessageChangeUpdatedAt(String chatId) {
        return messageChangeUpdatedAtByChat.get(key(chatId));
    }

    /** Lexicographic max works for ISO-8601 UTC timestamps. */
    private void trackMessageChangeUpdatedAt(String chatId, String updatedAt) {
        String k = key(chatId);
        synchronized (cacheLock) {
            String current = messageChangeUpdatedAtByChat.get(k);
            if (current == null || current.compareTo(updatedAt) < 0) {
                messageChangeUpdatedAtByChat.put(k, updatedAt);
            }
        }
    }

    /**
     * Replaces the pending local copy (matched by client_message_id) with the
     * confirmed server row. When the send was a retry of an already-held copy
     * ({@code wasRetry}), the original content/id are preserved verbatim
     * (first write wins).
     */
    private void confirmSend(String chatId, String clientMessageId, String serverId,
                             String senderId, boolean wasRetry) {
        synchronized (cacheLock) {
            String k = key(chatId);
            List<CreangerMessage> current = cacheOf(chatId);
            List<CreangerMessage> updated = new ArrayList<>();
            for (CreangerMessage m : current) {
                if (m.clientMessageId != null && m.clientMessageId.equals(clientMessageId) && m.isLocal) {
                    CreangerMessage confirmed = new CreangerMessage(
                            serverId, chatId, senderId, m.messageType, m.content, MessageStatus.SENT,
                            clientMessageId, m.chatSeq, m.createdAt != null ? m.createdAt : nowIsoUtc(),
                            null, null, null,
                            m.replyToMessageId, false, m.attachments);
                    updated.add(confirmed);
                } else {
                    updated.add(m);
                }
            }
            // The confirmed row still carries no chat_seq (the RPC returns only
            // the id), so keep the cache's newest-first invariant explicitly:
            // the createdAt stamp orders consecutive same-day confirms, and the
            // re-sort guards against any positional drift from in-place swaps.
            // The Realtime/REST row with the real chat_seq supersedes this copy
            // through the normal merge.
            updated.sort(MessageRepository::compareNewestFirst);
            messagesCache.put(k, updated);
        }
    }

    // ---- reaction helpers ----

    /** Cache key for one message's reaction state ({@code chatId|owner|messageId}). */
    private String messageReactionKey(String chatId, String messageId) {
        return key(chatId) + "|" + messageId;
    }

    /** Stable per-reaction key (distinguishes emoji from custom emoji rows). */
    private static String reactionKey(MessageReaction r) {
        return reactionKey(r.reaction, r.isCustomEmoji, r.customEmojiId);
    }

    private static String reactionKey(String reaction, boolean isCustomEmoji,
                                      @Nullable String customEmojiId) {
        return reaction + "\u0000" + (isCustomEmoji ? "c:" + customEmojiId : "e");
    }

    /**
     * Accumulates the distinct users behind one reaction of one message.
     * Recreated per {@link #setReactions} snapshot and mutated idempotently by
     * {@link #applyRealtimeReaction}.
     */
    private static final class ReactionState {
        final String reaction;
        final boolean isCustomEmoji;
        @Nullable
        final String customEmojiId;
        final java.util.Set<String> userIds = new java.util.HashSet<>();

        ReactionState(MessageReaction r) {
            this.reaction = r.reaction;
            this.isCustomEmoji = r.isCustomEmoji;
            this.customEmojiId = r.customEmojiId;
        }
    }
}
