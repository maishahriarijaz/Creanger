package com.creanger.app.messenger.creanger.realtime;

import org.json.JSONObject;

import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageReaction;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Subscription orchestrator for exactly ONE open Creanger chat.
 *
 * Responsibilities (pure JVM, fully unit-testable):
 *  - subscribe to a single chat ("subscribe only for the currently opened
 *    Creanger chat"); unsubscribing closes the socket and stops reconnects
 *  - feed every raw Realtime frame through {@link RealtimeMessageParser} and
 *    drop wrong-chat / wrong-table frames before they reach the UI; INSERT
 *    becomes {@link Listener#onMessageReceived}, an edit becomes
 *    {@link Listener#onMessageEdited}, a delivery/read status advance becomes
 *    {@link Listener#onMessageStatusChanged} and a deletion becomes
 *    {@link Listener#onMessageDeleted} — each deduplicated per event kind
 *  - deduplicate repeated events via {@link MessageRealtimeDeduplicator} (by
 *    {@code id} and {@code client_message_id})
 *  - track the newest {@code chat_seq} seen so a reconnect can recover exactly
 *    the messages missed while offline
 *  - reconnect with capped exponential backoff; after any gap fire
 *    {@link Listener#onReconnected} so the caller runs the existing cursor
 *    sync ({@code get_messages_since}) instead of trusting Realtime alone
 *  - treat authorization failures as permanent (no reconnect loop) and surface
 *    them via {@link Listener#onAuthError}
 *  - everything is cleared on unsubscribe/close for logout / account isolation
 *
 * The client never touches Android types: the transport is pluggable and every
 * callback is delivered through the injected {@link RunnablePoster} (main
 * Looper on device, synchronous in JVM tests). Blocking work (access-token
 * acquisition and the socket open) runs on a background executor so the caller
 * thread stays responsive; tests inject a synchronous executor.
 */
public final class MessageRealtimeClient {

    /** Supplies the current authenticated Creanger access-token JWT. */
    public interface AccessTokenProvider {
        String requireAccessToken() throws IOException, CreangerApiException;
    }

    /** Relocates listener callbacks onto a specific thread. */
    public interface RunnablePoster {
        void post(Runnable runnable);
    }

    /** Schedules a delayed reconnect attempt (Handler on device, immediate in tests). */
    public interface ReconnectScheduler {
        void schedule(long delayMillis, Runnable task);
    }

    /** Chat-screen sink; every call is delivered on the poster thread. */
    public interface Listener {
        /** A message was accepted as new for the open chat. */
        void onMessageReceived(String chatId, CreangerMessage message);

        /**
         * A message in the open chat was edited (content/edited_at changed by
         * the {@code edit_message} RPC). Only fired for non-duplicate edits.
         */
        void onMessageEdited(String chatId, CreangerMessage message);

        /**
         * A message's delivery/read status advanced via the
         * {@code mark_message_status} RPC (migration 026). Only fired for
         * non-duplicate statuses; an out-of-order / regressing status is still
         * surfaced so the repository can apply it monotonically.
         */
        void onMessageStatusChanged(String chatId, CreangerMessage message);

        /**
         * A message in the open chat was deleted for everyone (soft-delete
         * tombstone via UPDATE, or a DELETE event). Only fired once per id.
         */
        void onMessageDeleted(String chatId, String messageId);

        /**
         * A {@code message_reactions} row was added/removed for a message.
         * {@code added == true} is an INSERT/UPDATE (the user now has the
         * reaction), {@code added == false} is a DELETE (the user no longer has
         * it). Fired once per state change; the repository is authoritative and
         * drops rows for messages outside the loaded window.
         */
        void onReactionChanged(String chatId, MessageReaction reaction, boolean added);

        /**
         * An ephemeral broadcast typing event was received. Another user started
         * or stopped typing in the subscribed chat.
         *
         * @param chatId  the chat UUID
         * @param userId  the user who is typing (Creanger UUID)
         * @param isTyping true if typing started, false if typing stopped
         */
        void onTypingChanged(String chatId, String userId, boolean isTyping);

        /** An event was received but is a duplicate of a prior one. */
        void onDuplicate(String chatId, CreangerMessage message);

        /** First successful connection to Realtime for this chat. */
        void onConnected(String chatId);

        /** The socket dropped; a reconnect is scheduled automatically. */
        void onDisconnected(String chatId, Throwable cause);

        /**
         * Connection restored after a gap. {@code lastKnownSeq} is the newest
         * {@code chat_seq} the client has seen; the caller should run the
         * existing {@code get_messages_since} cursor sync to recover anything
         * missed while offline.
         */
        void onReconnected(String chatId, long lastKnownSeq);

        /** Permanent auth/authorization failure — subscription is stopped. */
        void onAuthError(String chatId, Throwable cause);
    }

    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private final MessageRealtimeTransport transport;
    private final AccessTokenProvider tokenProvider;
    private final RunnablePoster poster;
    private final ReconnectScheduler scheduler;
    private final Listener listener;
    private final MessageRealtimeDeduplicator deduplicator;
    private final ExecutorService connectExecutor;

    private volatile boolean active;
    private volatile String chatId;
    private volatile boolean connected;
    private volatile long lastKnownSeq;
    private volatile long reconnectBackoffMs = INITIAL_BACKOFF_MS;
    private volatile boolean needsRecovery;
    private volatile boolean connecting;

    public MessageRealtimeClient(MessageRealtimeTransport transport,
                                 AccessTokenProvider tokenProvider,
                                 RunnablePoster poster,
                                 ReconnectScheduler scheduler,
                                 Listener listener) {
        this(transport, tokenProvider, poster, scheduler, listener, null);
    }

    public MessageRealtimeClient(MessageRealtimeTransport transport,
                                 AccessTokenProvider tokenProvider,
                                 RunnablePoster poster,
                                 ReconnectScheduler scheduler,
                                 Listener listener,
                                 @Nullable ExecutorService connectExecutor) {
        this.transport = transport;
        this.tokenProvider = tokenProvider;
        this.poster = poster;
        this.scheduler = scheduler;
        this.listener = listener;
        this.deduplicator = new MessageRealtimeDeduplicator();
        this.connectExecutor = connectExecutor != null ? connectExecutor : daemonExecutor();
    }

    private static ExecutorService daemonExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "creanger-realtime-connect");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Subscribes to a chat (replacing any previous subscription). {@code
     * initialLastSeq} seeds the recovery cursor — normally the newest
     * {@code chat_seq} of the currently loaded page, or {@code 0}.
     */
    public void subscribe(String chatId, long initialLastSeq) {
        if (chatId == null || chatId.isEmpty()) {
            return;
        }
        if (this.active && this.chatId != null && this.chatId.equals(chatId)) {
            return;
        }
        unsubscribe();
        this.chatId = chatId;
        this.active = true;
        this.connected = false;
        this.needsRecovery = false;
        this.lastKnownSeq = initialLastSeq;
        this.reconnectBackoffMs = INITIAL_BACKOFF_MS;
        this.transport.setListener(transportListener);
        connect();
    }

    /** Stops the subscription, closes the socket and forgets all dedup state. */
    public void unsubscribe() {
        this.active = false;
        this.connected = false;
        this.chatId = null;
        this.deduplicator.clearAll();
        this.transport.close();
    }

    public void close() {
        unsubscribe();
        connectExecutor.shutdownNow();
    }

    public boolean isActive() {
        return active;
    }

    public String getChatId() {
        return chatId;
    }

    /** Newest {@code chat_seq} observed via Realtime for the open chat. */
    public long getLastKnownSeq() {
        return lastKnownSeq;
    }

    /**
     * Sends an ephemeral typing broadcast to the other users in the open chat.
     * The event is broadcast via the existing Phoenix channel and is NOT
     * persisted to the database. No-op when the transport is not connected.
     *
     * @param userId   the Creanger UUID of the user who is typing
     * @param isTyping true if typing started, false to clear
     */
    public void sendTyping(String userId, boolean isTyping) {
        if (!active || chatId == null) {
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("user_id", userId);
            payload.put("chat_id", chatId);
            payload.put("is_typing", isTyping);
            transport.sendBroadcast("typing", payload.toString());
        } catch (Exception e) {
            // Broadcast failures are non-fatal; typing is best-effort.
        }
    }

    // ---- connection lifecycle ----

    /** Async entry: runs the blocking open on the background executor. */
    private void connect() {
        if (!active || chatId == null || connecting) {
            return;
        }
        connecting = true;
        connectExecutor.execute(() -> {
            try {
                connectBlocking();
            } finally {
                connecting = false;
            }
        });
    }

    /** Blocking token acquisition + socket open; runs on the connect executor. */
    private void connectBlocking() {
        if (!active || chatId == null) {
            return;
        }
        final String token;
        try {
            token = tokenProvider.requireAccessToken();
        } catch (Throwable t) {
            if (isAuthFailure(t)) {
                failAuth(t);
            } else {
                scheduleReconnect(t);
            }
            return;
        }
        try {
            transport.connect(token);
        } catch (Throwable t) {
            if (isAuthFailure(t)) {
                failAuth(t);
            } else {
                scheduleReconnect(t);
            }
        }
    }

    /** Fires on every gap/failure so reconnect always recovers missed rows. */
    private void scheduleReconnect(Throwable cause) {
        final String chat = chatId;
        if (!active || chat == null) {
            return;
        }
        this.needsRecovery = true;
        long delay = reconnectBackoffMs;
        reconnectBackoffMs = Math.min(reconnectBackoffMs * 2, MAX_BACKOFF_MS);
        scheduler.schedule(delay, () -> {
            if (active && chat.equals(chatId)) {
                connect();
            }
        });
    }

    private void failAuth(Throwable cause) {
        final String chat = chatId;
        this.active = false;
        this.connected = false;
        this.needsRecovery = false;
        this.transport.close();
        if (listener != null) {
            poster.post(() -> listener.onAuthError(chat, cause));
        }
    }

    private static boolean isAuthFailure(Throwable t) {
        if (!(t instanceof CreangerApiException)) {
            return false;
        }
        CreangerApiException e = (CreangerApiException) t;
        return e.is(ApiError.UNAUTHORIZED)
                || e.is(ApiError.TOKEN_INVALID)
                || e.is(ApiError.TOKEN_EXPIRED)
                || e.is(ApiError.SESSION_EXPIRED_OR_REVOKED)
                || e.is(ApiError.MISSING_TOKEN);
    }

    // ---- transport callbacks (transport thread) ----

    private final MessageRealtimeTransport.Listener transportListener = new MessageRealtimeTransport.Listener() {
        @Override
        public void onTransportConnected() {
            if (!active) {
                transport.close();
                return;
            }
            boolean wasConnected = connected;
            connected = true;
            final String chat = chatId;
            final long seq = lastKnownSeq;
            if (wasConnected || needsRecovery) {
                needsRecovery = false;
                if (listener != null) {
                    poster.post(() -> listener.onReconnected(chat, seq));
                }
            } else {
                if (listener != null) {
                    poster.post(() -> listener.onConnected(chat));
                }
            }
        }

        @Override
        public void onTransportFrame(String frameJson) {
            final String chat = chatId;
            if (!active || chat == null) {
                return;
            }
            RealtimeMessageParser.Result result = RealtimeMessageParser.parse(chat, frameJson);
            switch (result.kind) {
                case MESSAGE_INSERT: {
                    if (result.message == null) {
                        return;
                    }
                    if (result.message.chatSeq != null) {
                        lastKnownSeq = Math.max(lastKnownSeq, result.message.chatSeq);
                    }
                    if (deduplicator.isNew(chat, result.message)) {
                        final CreangerMessage message = result.message;
                        if (listener != null) {
                            poster.post(() -> listener.onMessageReceived(chat, message));
                        }
                    } else if (listener != null) {
                        final CreangerMessage message = result.message;
                        poster.post(() -> listener.onDuplicate(chat, message));
                    }
                    break;
                }
                case MESSAGE_EDIT: {
                    if (result.message == null) {
                        return;
                    }
                    if (deduplicator.isNewEdit(chat, result.message)) {
                        final CreangerMessage message = result.message;
                        if (listener != null) {
                            poster.post(() -> listener.onMessageEdited(chat, message));
                        }
                    } else if (listener != null) {
                        final CreangerMessage message = result.message;
                        poster.post(() -> listener.onDuplicate(chat, message));
                    }
                    break;
                }
                case MESSAGE_STATUS: {
                    if (result.message == null) {
                        return;
                    }
                    if (deduplicator.isNewStatus(chat, result.message.id, result.message.status)) {
                        final CreangerMessage message = result.message;
                        if (listener != null) {
                            poster.post(() -> listener.onMessageStatusChanged(chat, message));
                        }
                    } else if (listener != null) {
                        final CreangerMessage message = result.message;
                        poster.post(() -> listener.onDuplicate(chat, message));
                    }
                    break;
                }
                case MESSAGE_DELETE: {
                    if (result.deletedMessageId == null) {
                        return;
                    }
                    if (deduplicator.isNewDelete(chat, result.deletedMessageId)) {
                        final String deletedId = result.deletedMessageId;
                        if (listener != null) {
                            poster.post(() -> listener.onMessageDeleted(chat, deletedId));
                        }
                    } else if (listener != null) {
                        poster.post(() -> listener.onDuplicate(chat, null));
                    }
                    break;
                }
                case REACTION_CHANGE: {
                    if (result.reaction == null) {
                        return;
                    }
                    final MessageReaction reaction = result.reaction;
                    final boolean added = result.reactionAdded;
                    if (deduplicator.isNewReactionChange(chat, reaction.messageId, reaction.userId,
                            reaction.reaction, added)) {
                        if (listener != null) {
                            poster.post(() -> listener.onReactionChanged(chat, reaction, added));
                        }
                    } else if (listener != null) {
                        poster.post(() -> listener.onDuplicate(chat, null));
                    }
                    break;
                }
                case TYPING: {
                    final String typingUserId = result.typingUserId;
                    final boolean isTyping = result.isTyping;
                    if (typingUserId != null && listener != null) {
                        poster.post(() -> listener.onTypingChanged(chat, typingUserId, isTyping));
                    }
                    break;
                }
                case UNAUTHORIZED: {
                    failAuth(new CreangerApiException(0,
                            new ApiError(ApiError.UNAUTHORIZED, "realtime join rejected", null, 0)));
                    break;
                }
                case JOINED:
                case IGNORED:
                case MALFORMED:
                default:
                    break;
            }
        }

        @Override
        public void onTransportClosed(@Nullable Throwable cause) {
            connected = false;
            final String chat = chatId;
            if (!active || chat == null) {
                return;
            }
            if (listener != null) {
                poster.post(() -> listener.onDisconnected(chat, cause));
            }
            scheduleReconnect(cause);
        }

        @Override
        public void onTransportUnauthorized(String reason) {
            failAuth(new CreangerApiException(0, new ApiError(ApiError.UNAUTHORIZED, reason, null, 0)));
        }
    };
}