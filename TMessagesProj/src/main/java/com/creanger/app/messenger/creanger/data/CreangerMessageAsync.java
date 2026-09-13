package com.creanger.app.messenger.creanger.data;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment;
import com.creanger.app.messenger.creanger.model.MessageModels.MessagePage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageReaction;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatusUpdate;
import com.creanger.app.messenger.creanger.model.MessageModels.UploadedMedia;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background-executor bridge between the UI (ChatActivity) and
 * {@link CreangerChatBridge}.  Every public method posts work to a background
 * executor and delivers the result (or error) to the main thread via a
 * {@link Callback}.
 */
public final class CreangerMessageAsync {

    // ---- callback contract ----
    public interface Callback<T> {
        void onSuccess(T result);
        void onError(@Nullable CreangerApiException error, @Nullable Throwable ioError);
    }

    /** Post-to-main-thread abstraction (injectable so JVM tests run synchronously). */
    public interface MainPoster {
        void post(Runnable r);
    }

    private final CreangerChatBridge bridge;
    private final ExecutorService executor;
    private final MainPoster mainPoster;
    private final AtomicInteger idCounter = new AtomicInteger(0);

    /**
     * Bytes of in-memory (raw byte[]) media sends, kept until the send
     * confirms so a retry can genuinely re-upload them. From-path sends are
     * NOT cached here — they recover from the file on disk instead.
     */
    private final Map<String, byte[]> retryBytes = new ConcurrentHashMap<>();

    /**
     * Single-flight guard for retries, keyed by chat + client message id. A
     * duplicate retry issued while one is already in flight (or queued) is
     * coalesced: it issues no RPC and its callback is never fired. The guard
     * is registered when the first retry is submitted and removed when that
     * retry finishes (success or failure).
     */
    private final Map<String, Boolean> retryInFlight = new ConcurrentHashMap<>();

    /** Production constructor: single-thread worker + Android main-looper poster. */
    public CreangerMessageAsync(CreangerChatBridge bridge) {
        this(bridge, Executors.newSingleThreadExecutor(),
                r -> new Handler(Looper.getMainLooper()).post(r));
    }

    /** Test seam: inject a synchronous/direct executor and main-thread poster. */
    public CreangerMessageAsync(CreangerChatBridge bridge, ExecutorService executor, MainPoster mainPoster) {
        this.bridge = bridge;
        this.executor = executor;
        this.mainPoster = mainPoster;
    }

    /** Returns a unique client-message id for pending rows. */
    public String newClientMessageId() {
        return "creanger-" + System.currentTimeMillis() + "-" + idCounter.getAndIncrement();
    }

    private static String retryKey(String chatId, String clientMessageId) {
        return chatId + "\u0000" + clientMessageId;
    }

    /**
     * Claims the single-flight retry slot for (chat, client message id).
     * Returns true when a retry for the same message is already in flight (or
     * queued), in which case the caller must drop the duplicate: no RPC, no
     * callback.
     */
    private boolean beginRetry(String chatId, String clientMessageId) {
        return retryInFlight.putIfAbsent(retryKey(chatId, clientMessageId), Boolean.TRUE) != null;
    }

    /** Releases the single-flight retry slot after the retry completes. */
    private void endRetry(String chatId, String clientMessageId) {
        retryInFlight.remove(retryKey(chatId, clientMessageId));
    }

    /** Background run + main-thread callback helper. */
    private <T> void run(final CreangerBridgeTask<T> task, final Callback<T> cb) {
        executor.execute(() -> {
            try {
                final T result = task.run();
                if (cb != null) {
                    mainPoster.post(() -> cb.onSuccess(result));
                }
            } catch (CreangerApiException e) {
                if (cb != null) {
                    mainPoster.post(() -> cb.onError(e, null));
                }
            } catch (Exception e) {
                if (cb != null) {
                    mainPoster.post(() -> cb.onError(null, e));
                }
            }
        });
    }

    /** Functional interface for a background task that returns T or throws. */
    @FunctionalInterface
    private interface CreangerBridgeTask<T> {
        T run() throws CreangerApiException, IOException, InterruptedException;
    }

    // ========================================================================
    // Synchronous accessors (safe to call from any thread for cached data)
    // ========================================================================

    /** Cached display list (newest first) for a chat, or empty. */
    public List<CreangerMessageUiModel> getMessages(String chatId) {
        return bridge.getMessages(chatId);
    }

    public void clear() {
        bridge.clear();
    }

    // ========================================================================
    // Realtime apply helpers (synchronous, on the caller's thread)
    // ========================================================================

    public boolean applyRealtime(String chatId, CreangerMessage message) {
        return bridge.applyRealtime(chatId, message);
    }

    public boolean applyRealtimeEdit(String chatId, CreangerMessage message) {
        return bridge.applyRealtimeEdit(chatId, message);
    }

    public boolean applyRealtimeDelete(String chatId, String messageId) {
        return bridge.applyRealtimeDelete(chatId, messageId);
    }

    public boolean applyRealtimeStatus(String chatId, CreangerMessage message) {
        return bridge.applyRealtimeStatus(chatId, message);
    }

    public boolean applyRealtimeReaction(String chatId, MessageReaction reaction, boolean added) {
        return bridge.applyRealtimeReaction(chatId, reaction, added);
    }

    public boolean applyOptimisticEdit(String chatId, String messageId, String newContent) {
        return bridge.applyOptimisticEdit(chatId, messageId, newContent);
    }

    public boolean applyOptimisticDelete(String chatId, String messageId) {
        return bridge.applyOptimisticDelete(chatId, messageId);
    }

    // ========================================================================
    // Page load / pagination
    // ========================================================================

    public void refreshMessages(String chatId, int limit, Callback<MessagePage> cb) {
        run(() -> bridge.refreshMessages(chatId, limit), cb);
    }

    public void refreshMessages(String chatId, Callback<MessagePage> cb) {
        run(() -> bridge.refreshMessages(chatId), cb);
    }

    public void loadOlderMessages(String chatId, long beforeSeq, int limit, Callback<MessagePage> cb) {
        run(() -> bridge.loadOlderMessages(chatId, beforeSeq, limit), cb);
    }

    // ========================================================================
    // Text send
    // ========================================================================

    public void sendReplyTextMessage(String chatId, String content, String replyToMessageId,
                                     Callback<String> cb) {
        run(() -> {
            String clientMessageId = newClientMessageId();
            bridge.insertPendingRow(chatId, clientMessageId, content, replyToMessageId);
            return bridge.sendTextMessage(chatId, clientMessageId, content, replyToMessageId);
        }, cb);
    }

    public void retrySend(String chatId, String clientMessageId, String content,
                          Callback<String> cb) {
        if (beginRetry(chatId, clientMessageId)) {
            return; // duplicate retry already in flight: coalesced, no RPC/callback
        }
        run(() -> {
            try {
                bridge.markPending(chatId, clientMessageId);
                return bridge.sendTextMessage(chatId, clientMessageId, content, null);
            } finally {
                endRetry(chatId, clientMessageId);
            }
        }, cb);
    }

    /** Idempotent send with an explicit client id (optimistic insert happens here). */
    public void sendTextMessage(String chatId, String clientMessageId, String content,
                                @Nullable String replyToMessageId, Callback<String> cb) {
        sendTextMessage(chatId, clientMessageId, content, replyToMessageId, null, cb);
    }

    /** Idempotent send carrying TL_iv.RichText JSON when formatting is available. */
    public void sendTextMessage(String chatId, String clientMessageId, String content,
                                @Nullable String replyToMessageId,
                                @Nullable org.json.JSONObject richText, Callback<String> cb) {
        bridge.insertPendingRow(chatId, clientMessageId, content, replyToMessageId);
        run(() -> bridge.sendTextMessage(chatId, clientMessageId, content, replyToMessageId, richText), cb);
    }

    /** Idempotent send without a reply. */
    public void sendTextMessage(String chatId, String clientMessageId, String content, Callback<String> cb) {
        bridge.insertPendingRow(chatId, clientMessageId, content);
        run(() -> bridge.sendTextMessage(chatId, clientMessageId, content), cb);
    }

    public boolean isAuthenticated() {
        return bridge.isAuthenticated();
    }

    public void cancelPending(String chatId, String clientMessageId, Callback<Boolean> cb) {
        run(() -> bridge.cancelPending(chatId, clientMessageId), cb);
    }

    // ========================================================================
    // Image send
    // ========================================================================

    public void sendImageMessageFromPath(String chatId, String localPath, String mimeType,
                                         @Nullable String caption, @Nullable String replyToMessageId,
                                         Callback<String> cb) {
        final String clientMessageId = newClientMessageId();
        final String fileName = baseFileName(localPath);
        bridge.insertPendingRow(chatId, clientMessageId, "image", caption, replyToMessageId,
                java.util.Collections.singletonList(
                        com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment
                                .createPending(fileName, mimeType, localPath)));
        run(() -> {
            byte[] data = readFile(localPath);
            if (data == null || data.length == 0) {
                bridge.markFailed(chatId, clientMessageId);
                throw new IOException("unable to read image file " + localPath);
            }
            return bridge.sendImageMessage(chatId, clientMessageId, fileName, data, mimeType,
                    caption, replyToMessageId);
        }, cb);
    }

    /** Raw-bytes send (tests / callers that already hold the file in memory). */
    public void sendImageMessage(String chatId, String clientMessageId, String fileName,
                                 byte[] data, String mimeType, @Nullable String caption,
                                 @Nullable String replyToMessageId, Callback<String> cb) {
        bridge.insertPendingRow(chatId, clientMessageId, "image", caption, replyToMessageId,
                java.util.Collections.singletonList(
                        com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment
                                .createPending(fileName, mimeType, null)));
        retryBytes.put(clientMessageId, data);
        run(() -> bridge.sendImageMessage(chatId, clientMessageId, fileName, data, mimeType,
                caption, replyToMessageId), cleaningCallback(clientMessageId, cb));
    }

    /** Raw-bytes voice send (in-memory audio). */
    public void sendVoiceMessage(String chatId, String clientMessageId, String fileName,
                                 byte[] data, String mimeType, @Nullable String caption,
                                 @Nullable String replyToMessageId, Callback<String> cb) {
        bridge.insertPendingRow(chatId, clientMessageId, "voice", caption, replyToMessageId,
                java.util.Collections.singletonList(
                        com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment
                                .createPending(fileName, mimeType, null)));
        retryBytes.put(clientMessageId, data);
        run(() -> bridge.sendVoiceMessage(chatId, clientMessageId, fileName, data, mimeType,
                caption, replyToMessageId), cleaningCallback(clientMessageId, cb));
    }

    /** Raw-bytes document send (in-memory document). */
    public void sendDocumentMessage(String chatId, String clientMessageId, String fileName,
                                    byte[] data, String mimeType, @Nullable String caption,
                                    @Nullable String replyToMessageId, Callback<String> cb) {
        bridge.insertPendingRow(chatId, clientMessageId, "document", caption, replyToMessageId,
                java.util.Collections.singletonList(
                        com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment
                                .createPending(fileName, mimeType, null)));
        retryBytes.put(clientMessageId, data);
        run(() -> bridge.sendDocumentMessage(chatId, clientMessageId, fileName, data, mimeType,
                caption, replyToMessageId), cleaningCallback(clientMessageId, cb));
    }

    /** Raw-bytes music send (in-memory audio). */
    public void sendMusicMessage(String chatId, String clientMessageId, String fileName,
                                 byte[] data, String mimeType, @Nullable String caption,
                                 @Nullable String replyToMessageId, Callback<String> cb) {
        bridge.insertPendingRow(chatId, clientMessageId, "audio", caption, replyToMessageId,
                java.util.Collections.singletonList(
                        com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment
                                .createPending(fileName, mimeType, null)));
        retryBytes.put(clientMessageId, data);
        run(() -> bridge.sendMusicMessage(chatId, clientMessageId, fileName, data, mimeType,
                caption, replyToMessageId), cleaningCallback(clientMessageId, cb));
    }

    private Callback<String> cleaningCallback(String clientMessageId, Callback<String> delegate) {
        if (delegate == null) {
            return new Callback<String>() {
                @Override public void onSuccess(String r) { retryBytes.remove(clientMessageId); }
                @Override public void onError(CreangerApiException e, Throwable t) {}
            };
        }
        return new Callback<String>() {
            @Override public void onSuccess(String r) { retryBytes.remove(clientMessageId); delegate.onSuccess(r); }
            @Override public void onError(CreangerApiException e, Throwable t) { delegate.onError(e, t); }
        };
    }

    /**
     * Re-attempts a FAILED image send. Recovers the pending row's recorded
     * local file (so retry genuinely re-uploads) or re-uses bytes passed by
     * the caller when the file is no longer on disk.
     */
    public void retrySendImageMessage(String chatId, String clientMessageId, Callback<String> cb) {
        retrySendImageMessage(chatId, clientMessageId, null, cb);
    }

    public void retrySendImageMessage(String chatId, String clientMessageId, @Nullable byte[] data,
                                      Callback<String> cb) {
        retryMedia(chatId, clientMessageId, data, cb);
    }

    /** Looks up the cached UI row for a pending/failed client message id. */
    @Nullable
    private CreangerMessageUiModel findPendingRow(String chatId, String clientMessageId) {
        for (CreangerMessageUiModel m : bridge.getMessages(chatId)) {
            if (clientMessageId.equals(m.clientMessageId)) {
                return m;
            }
        }
        return null;
    }

    // ========================================================================
    // Video send
    // ========================================================================

    public void sendVideoMessageFromPath(String chatId, String videoPath,
                                         @Nullable String posterPath, String mimeType,
                                         @Nullable String caption, @Nullable String replyToMessageId,
                                         Callback<String> cb) {
        final String clientMessageId = newClientMessageId();
        final String fileName = baseFileName(videoPath);
        List<MediaAttachment> pendingAtts = new ArrayList<>();
        pendingAtts.add(com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment
                .createPendingVideo(fileName, mimeType, posterPath));
        bridge.insertPendingRow(chatId, clientMessageId, "video", caption, replyToMessageId, pendingAtts);
        run(() -> {
            byte[] data = readFile(videoPath);
            if (data == null || data.length == 0) {
                bridge.markFailed(chatId, clientMessageId);
                throw new IOException("unable to read video file " + videoPath);
            }
            return bridge.sendVideoMessage(chatId, clientMessageId, fileName, data, mimeType,
                    caption, replyToMessageId);
        }, cb);
    }

    public void retrySendVideoMessage(String chatId, String clientMessageId, Callback<String> cb) {
        retrySendVideoMessage(chatId, clientMessageId, null, cb);
    }

    public void retrySendVideoMessage(String chatId, String clientMessageId, @Nullable byte[] data,
                                      Callback<String> cb) {
        retryMedia(chatId, clientMessageId, data, cb);
    }

    // ========================================================================
    // Document send
    // ========================================================================

    public void sendDocumentMessageFromPath(String chatId, String localPath, String mimeType,
                                            @Nullable String caption, @Nullable String replyToMessageId,
                                            Callback<String> cb) {
        final String clientMessageId = newClientMessageId();
        final String fileName = baseFileName(localPath);
        bridge.insertPendingRow(chatId, clientMessageId, "document", caption, replyToMessageId,
                java.util.Collections.singletonList(
                        com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment
                                .createPending(fileName, mimeType, localPath)));
        run(() -> {
            byte[] data = readFile(localPath);
            if (data == null || data.length == 0) {
                bridge.markFailed(chatId, clientMessageId);
                throw new IOException("unable to read document file " + localPath);
            }
            return bridge.sendDocumentMessage(chatId, clientMessageId, fileName, data, mimeType,
                    caption, replyToMessageId);
        }, cb);
    }

    public void retrySendDocumentMessage(String chatId, String clientMessageId, Callback<String> cb) {
        retrySendDocumentMessage(chatId, clientMessageId, null, cb);
    }

    public void retrySendDocumentMessage(String chatId, String clientMessageId, @Nullable byte[] data,
                                         Callback<String> cb) {
        retryMedia(chatId, clientMessageId, data, cb);
    }

    // ========================================================================
    // Music send
    // ========================================================================

    public void sendMusicMessageFromPath(String chatId, String localPath, String mimeType,
                                         @Nullable String caption, @Nullable String replyToMessageId,
                                         Callback<String> cb) {
        final String clientMessageId = newClientMessageId();
        final String fileName = baseFileName(localPath);
        bridge.insertPendingRow(chatId, clientMessageId, "audio", caption, replyToMessageId,
                java.util.Collections.singletonList(
                        com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment
                                .createPending(fileName, mimeType, localPath)));
        run(() -> {
            byte[] data = readFile(localPath);
            if (data == null || data.length == 0) {
                bridge.markFailed(chatId, clientMessageId);
                throw new IOException("unable to read audio file " + localPath);
            }
            return bridge.sendMusicMessage(chatId, clientMessageId, fileName, data, mimeType,
                    caption, replyToMessageId);
        }, cb);
    }

    public void retrySendAudioMessage(String chatId, String clientMessageId, Callback<String> cb) {
        retrySendAudioMessage(chatId, clientMessageId, null, cb);
    }

    public void retrySendAudioMessage(String chatId, String clientMessageId, @Nullable byte[] data,
                                      Callback<String> cb) {
        retryMedia(chatId, clientMessageId, data, cb);
    }

    /**
     * Shared media retry: routes by the FAILED row's original message type so
     * a retried send keeps its type (voice stays voice, audio stays audio),
     * recovers the bytes (in-memory cache, then the recorded local file), and
     * re-fires upload + RPC with the same idempotent client message id.
     */
    private void retryMedia(String chatId, String clientMessageId, @Nullable byte[] explicitBytes,
                            Callback<String> cb) {
        if (beginRetry(chatId, clientMessageId)) {
            return; // duplicate retry already in flight: coalesced, no RPC/callback
        }
        run(() -> {
            try {
                CreangerMessageUiModel row = findPendingRow(chatId, clientMessageId);
                String type = row != null ? row.messageType : null;
                MediaAttachment att = row != null && !row.attachments.isEmpty() ? row.attachments.get(0) : null;
                byte[] bytes = explicitBytes != null ? explicitBytes : retryBytes.get(clientMessageId);
                String fileName = att != null ? att.storageKey : null;
                String mimeType = att != null ? att.mimeType : null;
                if (bytes == null && att != null && att.localPath != null) {
                    bytes = readFile(att.localPath);
                }
                if (bytes == null || bytes.length == 0) {
                    // Stay FAILED (not PENDING) so the user can retry once the
                    // file is available again.
                    bridge.markFailed(chatId, clientMessageId);
                    throw new IOException("retry: original media file is no longer available");
                }
                bridge.markPending(chatId, clientMessageId);
                String content = row != null ? row.content : null;
                String replyTo = row != null ? row.replyToMessageId : null;
                if ("video".equals(type)) {
                    return bridge.sendVideoMessage(chatId, clientMessageId, fileName, bytes, mimeType, content, replyTo);
                }
                if ("document".equals(type)) {
                    return bridge.sendDocumentMessage(chatId, clientMessageId, fileName, bytes, mimeType, content, replyTo);
                }
                if ("audio".equals(type)) {
                    return bridge.sendMusicMessage(chatId, clientMessageId, fileName, bytes, mimeType, content, replyTo);
                }
                if ("voice".equals(type)) {
                    return bridge.sendVoiceMessage(chatId, clientMessageId, fileName, bytes, mimeType, content, replyTo);
                }
                return bridge.sendImageMessage(chatId, clientMessageId, fileName, bytes, mimeType, content, replyTo);
            } finally {
                endRetry(chatId, clientMessageId);
            }
        }, cleaningCallback(clientMessageId, cb));
    }

    // ========================================================================
    // Voice send
    // ========================================================================

    public void sendVoiceMessageFromPath(String chatId, String audioPath, String mimeType,
                                         @Nullable String caption,
                                         @Nullable String replyToMessageId, Callback<String> cb) {
        final String clientMessageId = newClientMessageId();
        final String fileName = baseFileName(audioPath);
        bridge.insertPendingRow(chatId, clientMessageId, "voice", caption, replyToMessageId,
                java.util.Collections.singletonList(
                        com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment
                                .createPending(fileName, mimeType, audioPath)));
        run(() -> {
            byte[] data = readFile(audioPath);
            if (data == null || data.length == 0) {
                bridge.markFailed(chatId, clientMessageId);
                throw new IOException("unable to read voice file " + audioPath);
            }
            return bridge.sendVoiceMessage(chatId, clientMessageId, fileName, data, mimeType,
                    caption, replyToMessageId);
        }, cb);
    }

    // ========================================================================
    // Edit / delete message
    // ========================================================================

    public void editMessage(String chatId, String messageId, String newContent, Callback<String> cb) {
        // Optimistic: the edited content is visible on the calling (UI) thread
        // immediately; only the authoritative RPC runs on the worker.
        bridge.applyOptimisticEdit(chatId, messageId, newContent);
        run(() -> bridge.completeEdit(chatId, messageId, newContent), cb);
    }

    public void deleteMessage(String chatId, String messageId, Callback<String> cb) {
        // Optimistic: the row disappears on the calling (UI) thread immediately.
        bridge.applyOptimisticDelete(chatId, messageId);
        run(() -> bridge.completeDelete(chatId, messageId), cb);
    }

    // ========================================================================
    // Message status
    // ========================================================================

    public void markMessageStatus(String chatId, String messageId, String status,
                                  Callback<String> cb) {
        run(() -> bridge.markMessageStatus(chatId, messageId, status), cb);
    }

    public void recoverMessageStatuses(String chatId, String afterUpdatedAt, int limit,
                                       Callback<List<MessageStatusUpdate>> cb) {
        run(() -> bridge.recoverMessageStatuses(chatId, afterUpdatedAt, limit), cb);
    }

    public void recoverMessageChanges(String chatId, String afterUpdatedAt, int limit,
                                      Callback<List<CreangerMessage>> cb) {
        run(() -> bridge.recoverMessageChanges(chatId, afterUpdatedAt, limit), cb);
    }

    public void recoverSince(String chatId, long afterSeq, int limit,
                             Callback<List<CreangerMessage>> cb) {
        run(() -> bridge.recoverSince(chatId, afterSeq, limit), cb);
    }

    // ========================================================================
    // Reactions
    // ========================================================================

    public void addReaction(String chatId, String messageId, String reaction, Callback<String> cb) {
        run(() -> bridge.addReaction(chatId, messageId, reaction), cb);
    }

    public void removeReaction(String chatId, String messageId, String reaction,
                               Callback<String> cb) {
        run(() -> bridge.removeReaction(chatId, messageId, reaction), cb);
    }

    public void loadReactions(String chatId, List<String> messageIds, Callback<Void> cb) {
        run(() -> {
            bridge.loadReactions(chatId, messageIds);
            return null;
        }, cb);
    }

    // ========================================================================
    // Attachments
    // ========================================================================

    /**
     * Optimistic send for an already-uploaded media payload (migration 029):
     * inserts the immediate PENDING row, then persists via the
     * {@code send_media_message} RPC on the worker.
     */
    public void sendMediaMessage(String chatId, String clientMessageId, String messageType,
                                 @Nullable String caption, List<MediaAttachment> attachments,
                                 Callback<String> cb) {
        bridge.insertPendingRow(chatId, clientMessageId, messageType, caption, null, attachments);
        run(() -> bridge.sendMediaMessage(chatId, clientMessageId, messageType, caption, null,
                attachments), cb);
    }

    public void loadAttachments(String chatId, List<String> messageIds, Callback<Void> cb) {
        run(() -> {
            bridge.loadAttachments(chatId, messageIds);
            return null;
        }, cb);
    }

    // ========================================================================
    // Search
    // ========================================================================

    public void searchMessagesInChat(String chatId, String query, int limit,
                                     Callback<List<CreangerMessageUiModel>> cb) {
        run(() -> bridge.searchMessagesInChat(chatId, query, limit), cb);
    }

    // ========================================================================
    // Pin / unpin messages
    // ========================================================================

    public void pinMessage(String messageId, Callback<String> cb) {
        run(() -> bridge.pinMessage(messageId), cb);
    }

    public void unpinMessage(String messageId, Callback<String> cb) {
        run(() -> bridge.unpinMessage(messageId), cb);
    }

    public void getPinnedMessages(String chatId, Callback<List<CreangerMessageUiModel>> cb) {
        run(() -> bridge.getPinnedMessages(chatId), cb);
    }

    // ========================================================================
    // Forward messages
    // ========================================================================

    public void forwardMessage(String sourceMsgId, String destChatId, Callback<String> cb) {
        run(() -> bridge.forwardMessage(sourceMsgId, destChatId), cb);
    }

    // ========================================================================
    // Edit media (replace attachment)
    // ========================================================================

    /**
     * Low-level attachment swap (after upload). Uses existing primitives:
     * DELETE old message_attachments + INSERT new one.
     */
    public void replaceMediaAttachment(String messageId, String oldMediaId,
                                       String newMediaId, int position,
                                       @Nullable String caption, Callback<Void> cb) {
        run(() -> {
            bridge.replaceMediaAttachment(messageId, oldMediaId, newMediaId, position, caption);
            return null;
        }, cb);
    }

    /**
     * Full edit-media flow: uploads a new file from a local path, inserts
     * a new {@code media} row, swaps the {@code message_attachments} link,
     * and optionally updates the caption via the {@code edit_message} RPC.
     * Runs upload + swap on the background executor; callback fires on main.
     */
    public void replaceMediaAttachment(String chatId, String messageId,
                                       String oldMediaId, String localPath,
                                       String fileName, String mimeType,
                                       boolean isVideo,
                                       @Nullable String newCaption,
                                       Callback<Void> cb) {
        run(() -> {
            byte[] data = readFile(localPath);
            if (data == null || data.length == 0) {
                throw new IOException("unable to read file for media replace: " + localPath);
            }
            // Step 1: Upload new media via existing Creanger upload pipeline
            UploadedMedia uploaded;
            if (isVideo) {
                uploaded = bridge.uploadVideo(chatId, fileName, data, mimeType);
            } else {
                uploaded = bridge.uploadImage(chatId, fileName, data, mimeType);
            }
            if (uploaded == null || uploaded.storageKey == null) {
                throw new IOException("media upload returned null for " + fileName);
            }
            // Step 2: Insert a new media row via PostgREST
            String mediaId = bridge.insertMedia(
                    uploaded.storageProvider, uploaded.storageKey,
                    uploaded.publicUrl, uploaded.deliveryUrl,
                    uploaded.mimeType, uploaded.sizeBytes,
                    uploaded.width, uploaded.height, uploaded.durationMs);
            if (mediaId == null || mediaId.isEmpty()) {
                throw new IOException("media row insert returned no id for " + fileName);
            }
            // Step 3: Swap the attachment link (DELETE old + INSERT new)
            bridge.replaceMediaAttachment(messageId, oldMediaId, mediaId, 0, null);
            // Step 4: Optionally update caption via edit_message RPC
            if (newCaption != null) {
                bridge.editMessageCaption(messageId, newCaption);
            }
            return null;
        }, cb);
    }

    /**
     * Updates a message caption via the edit_message RPC on the background
     * executor; callback fires on the main thread.
     */
    public void editMessageCaption(String messageId, @Nullable String caption, Callback<Void> cb) {
        run(() -> {
            bridge.editMessageCaption(messageId, caption);
            return null;
        }, cb);
    }

    // ========================================================================
    // Drafts (migration 006)
    // ========================================================================

    /**
     * Saves (upserts) a draft for the given chat on the background executor.
     * Pass null/empty content to delete the draft.
     */
    public void saveDraft(String chatId, @Nullable String content,
                          @Nullable String replyToMessageId, Callback<Void> cb) {
        saveDraft(chatId, content, replyToMessageId, null, cb);
    }

    /** Saves a draft scoped to (owner, chat, topic); topic null = main chat. */
    public void saveDraft(String chatId, @Nullable String content,
                          @Nullable String replyToMessageId, @Nullable String topicId,
                          Callback<Void> cb) {
        run(() -> {
            bridge.saveDraft(chatId, content, replyToMessageId, topicId);
            return null;
        }, cb);
    }

    public void getDraft(String chatId, Callback<CreangerChatApiClient.DraftInfo> cb) {
        getDraft(chatId, null, cb);
    }

    /** Fetches the draft for the given chat+topic. */
    public void getDraft(String chatId, @Nullable String topicId,
                         Callback<CreangerChatApiClient.DraftInfo> cb) {
        run(() -> bridge.getDraft(chatId, topicId), cb);
    }

    public void deleteDraft(String chatId, Callback<Void> cb) {
        deleteDraft(chatId, null, cb);
    }

    /** Deletes the draft for the given chat+topic. */
    public void deleteDraft(String chatId, @Nullable String topicId, Callback<Void> cb) {
        run(() -> {
            bridge.deleteDraft(chatId, topicId);
            return null;
        }, cb);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static String baseFileName(String path) {
        if (path == null) return "file";
        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return sep >= 0 ? path.substring(sep + 1) : path;
    }

    @Nullable
    private static byte[] readFile(String path) {
        try {
            if (path == null) return null;
            File f = new File(path);
            if (!f.exists() || !f.canRead()) return null;
            long len = f.length();
            if (len <= 0 || len > 100L * 1024 * 1024 || len > Integer.MAX_VALUE) return null;
            byte[] data = new byte[(int) len];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            try {
                int offset = 0;
                int read;
                while (offset < data.length && (read = fis.read(data, offset, data.length - offset)) > 0) {
                    offset += read;
                }
                if (offset != data.length) return null;
            } finally {
                fis.close();
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }
}
