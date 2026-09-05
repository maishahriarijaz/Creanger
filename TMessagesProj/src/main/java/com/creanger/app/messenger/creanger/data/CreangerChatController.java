package com.creanger.app.messenger.creanger.data;

import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.data.CreangerMessageAsync.Callback;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessagePage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageReaction;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatusUpdate;
import com.creanger.app.messenger.creanger.realtime.MessageRealtimeClient;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen-level controller for a single Creanger chat (the chat activity's
 * "brain"). Wraps {@link CreangerMessageAsync} + {@link CreangerChatBridge} and
 * owns the state the chat screen needs to render:
 *
 *  - the display list (newest first, {@link CreangerMessageUiModel})
 *  - the paging cursor ({@code nextOlderSeq}) and a {@code hasMore} flag
 *  - the top-level screen phase: LOADING/READY/EMPTY/ERROR (network + typed API)
 *  - optimistic PENDING sends, confirmation reconcile, and FAILED + retry with
 *    the same {@code client_message_id}
 *
 * Deliberately Android-free and JVM-testable (no Looper/Handler/Context): the
 * {@link CreangerMessageAsync} it wraps is constructed with an injected
 * background executor and a main-poster, so in unit tests both are synchronous
 * and every {@link Listener} callback arrives on the same thread.
 *
 * All methods are safe to call from the main thread; the wrapped async posts
 * completion back on the main poster. Account-switch / logout isolation is
 * achieved by constructing a fresh controller per owner and calling
 * {@link #close()} (which clears the underlying cache) before the previous
 * account's data is ever observed.
 */
public final class CreangerChatController {

    /** Top-level rendering phase of a chat. */
    public enum Phase {
        /** Initial load in progress (spinner). */
        LOADING,
        /** At least one page is loaded (list visible). */
        READY,
        /** Authorized, no messages (empty state). */
        EMPTY,
        /** Transient network failure (offline) - retained list may be blank. */
        NETWORK_ERROR,
        /** Typed API error, e.g. 401/403 unauthorized. */
        AUTH_ERROR
    }

    /** Chat-screen callback sink; every call happens on the main thread. */
    public interface Listener {
        /** Display list changed (newest-first). */
        void onMessagesChanged(CreangerChatController controller, List<CreangerMessageUiModel> messages);

        /** Screen phase changed (LOADING -> READY/EMPTY/ERROR). */
        void onPhaseChanged(CreangerChatController controller, Phase phase);

        /** A send changed status: PENDING -> SENT (server id known) or FAILED (retryable). */
        void onSendStatusChanged(CreangerChatController controller, String clientMessageId, String status,
                                 String serverMessageId, Throwable error);
    }

    /**
     * Builds the {@link MessageRealtimeClient} for this chat, wired to the
     * controller's realtime listener. Injectable so the whole open-live-update
     * path is JVM-testable (scripts the transport/scheduler/token provider).
     * {@code null} when realtime is disabled for the build.
     */
    public interface RealtimeClientFactory {
        MessageRealtimeClient create(MessageRealtimeClient.Listener listener);
    }

    /** Recovery batch size for the {@code get_messages_since} cursor sync. */
    private static final int RECOVERY_LIMIT = 100;

    private final CreangerMessageAsync async;
    @Nullable
    private final RealtimeClientFactory realtimeFactory;
    @Nullable
    private MessageRealtimeClient realtime;
    private final String chatId;
    private final Listener listener;

    private Phase phase = Phase.LOADING;

    private List<CreangerMessageUiModel> messages = new ArrayList<>();

    private boolean hasMore;
    private Long nextOlderSeq;

    /** clientMessageId -> content, for retry after FAILED. */
    private final java.util.Map<String, String> pendingContentById = new java.util.concurrent.ConcurrentHashMap<>();

    public CreangerChatController(CreangerMessageAsync async, String chatId, Listener listener) {
        this(async, null, chatId, listener);
    }

    public CreangerChatController(CreangerMessageAsync async, @Nullable RealtimeClientFactory realtimeFactory,
                                  String chatId, Listener listener) {
        this.async = async;
        this.realtimeFactory = realtimeFactory;
        this.chatId = chatId;
        this.listener = listener;
    }

    public String getChatId() {
        return chatId;
    }

    public Phase getPhase() {
        return phase;
    }

    public boolean isAuthenticated() {
        return async.isAuthenticated();
    }

    public List<CreangerMessageUiModel> getMessages() {
        return messages;
    }

    public boolean hasMore() {
        return hasMore;
    }

    public Long getNextOlderSeq() {
        return nextOlderSeq;
    }

    // ---- loading ----

    /** Loads the newest page of the chat. Re-entrant safe (phase-driven). */
    public void refresh() {
        setPhase(Phase.LOADING);
        async.refreshMessages(chatId, CreangerChatBridge.DEFAULT_PAGE_SIZE, new Callback<MessagePage>() {
            @Override
            public void onSuccess(MessagePage page) {
                onPage(page);
                // Subscription (idempotent for this chat) starts once the first
                // page settles so the recovery cursor is seeded correctly.
                startRealtimeSubscription();
            }

            @Override
            public void onError(CreangerApiException error, Throwable ioError) {
                reportNetworkOrAuthError(error, ioError);
            }
        });
    }

    /** Appends the next older page of history (from the cursor). */
    public void loadOlder() {
        Long cursor = nextOlderSeq;
        if (cursor == null || !hasMore) {
            return;
        }
        async.loadOlderMessages(chatId, cursor, CreangerChatBridge.DEFAULT_PAGE_SIZE, new Callback<MessagePage>() {
            @Override
            public void onSuccess(MessagePage page) {
                onPage(page);
            }

            @Override
            public void onError(CreangerApiException error, Throwable ioError) {
                reportNetworkOrAuthError(error, ioError);
            }
        });
    }

    // ---- sending ----

    /**
     * Optimistic text send. The PENDING row is inserted immediately and becomes
     * visible on {@link #getMessages()}; the RPC runs in the background and on
     * success the row is reconciled to the server-confirmed message.
     *
     * @return the clientMessageId used (for later retry)
     */
    public String sendText(String content) {
        String clientMessageId = "cm-" + java.util.UUID.randomUUID().toString();
        pendingContentById.put(clientMessageId, content);
        async.sendTextMessage(chatId, clientMessageId, content, new Callback<String>() {
            @Override
            public void onSuccess(String serverId) {
                onSendStatus(clientMessageId, content, serverId, null);
                onMessagesChanged();
            }

            @Override
            public void onError(CreangerApiException error, Throwable ioError) {
                Throwable cause = error != null ? error : ioError;
                // Refresh the display list first so onSendStatusChanged observes
                // the FAILED row (not the stale PENDING copy).
                onMessagesChanged();
                onSendStatus(clientMessageId, content, null, cause);
                reportNetworkOrAuthError(error, ioError);
            }
        });
        onMessagesChanged();
        return clientMessageId;
    }

    /** Re-sends a previously-FAILED send reusing the same clientMessageId. */
    public void retrySend(String clientMessageId) {
        String content = pendingContentById.get(clientMessageId);
        if (content == null) {
            content = getMessageContent(clientMessageId);
        }
        if (content == null) {
            return;
        }
        final String resolvedContent = content;
        async.retrySend(chatId, clientMessageId, resolvedContent, new Callback<String>() {
            @Override
            public void onSuccess(String serverId) {
                onSendStatus(clientMessageId, resolvedContent, serverId, null);
                onMessagesChanged();
            }

            @Override
            public void onError(CreangerApiException error, Throwable ioError) {
                Throwable cause = error != null ? error : ioError;
                onMessagesChanged();
                onSendStatus(clientMessageId, resolvedContent, null, cause);
                reportNetworkOrAuthError(error, ioError);
            }
        });
    }

    // ---- editing / deleting ----

    /**
     * Optimistic text edit of an own message. The cached row switches to
     * {@code newContent} immediately (visible on {@link #getMessages()}); the
     * author-only {@code edit_message} RPC runs in the background and on
     * failure the pre-edit content is restored (rollback). The message id is a
     * Creanger UUID; authorization is enforced server-side by the RPC/RLS.
     */
    public void editMessage(String messageId, String newContent) {
        if (chatId == null || messageId == null
                || newContent == null || newContent.trim().isEmpty()) {
            return;
        }
        async.editMessage(chatId, messageId, newContent, new Callback<String>() {
            @Override
            public void onSuccess(String serverId) {
                onMessagesChanged();
            }

            @Override
            public void onError(CreangerApiException error, Throwable ioError) {
                onMessagesChanged(); // post-rollback state (edit was reverted)
                reportNetworkOrAuthError(error, ioError);
            }
        });
        onMessagesChanged(); // the optimistic edit is already visible
    }

    /**
     * Optimistic delete of an own message (soft delete per the schema: the
     * author-only {@code delete_message} RPC sets {@code messages.deleted_at}).
     * The cached row is removed immediately; on failure it is restored
     * (rollback).
     */
    public void deleteMessage(String messageId) {
        if (chatId == null || messageId == null) {
            return;
        }
        async.deleteMessage(chatId, messageId, new Callback<String>() {
            @Override
            public void onSuccess(String serverId) {
                onMessagesChanged();
            }

            @Override
            public void onError(CreangerApiException error, Throwable ioError) {
                onMessagesChanged(); // post-rollback state (row restored)
                reportNetworkOrAuthError(error, ioError);
            }
        });
        onMessagesChanged(); // the optimistic removal is already visible
    }

    // ---- lifecycle / isolation ----

    /** Clears the controller, unsubscribes Realtime and clears the underlying state. */
    public void close() {
        if (realtime != null) {
            realtime.unsubscribe();
            realtime = null;
        }
        async.clear();
        messages = new ArrayList<>();
        pendingContentById.clear();
        hasMore = false;
        nextOlderSeq = null;
        setPhase(Phase.LOADING);
    }

    // ---- internals ----

    /**
     * Starts (or idempotently re-affirms) the Realtime subscription for the
     * open chat. {@link MessageRealtimeClient#subscribe} is a no-op when the
     * same chat is already active, so repeated open/resume never creates a
     * second subscription or reconnect loop.
     */
    private void startRealtimeSubscription() {
        if (realtimeFactory == null || chatId == null) {
            return;
        }
        if (realtime == null) {
            realtime = realtimeFactory.create(realtimeListener);
        }
        realtime.subscribe(chatId, latestKnownSeq());
    }

    /** Newest {@code chat_seq} of the currently loaded list, or 0. */
    private long latestKnownSeq() {
        long last = 0;
        for (CreangerMessageUiModel m : async.getMessages(chatId)) {
            if (m.chatSeq != null && m.chatSeq > last) {
                last = m.chatSeq;
            }
        }
        return last;
    }

    /**
     * Realtime intake for the open chat. Incoming rows go through the same
     * repository merge/dedup as REST pages and send confirms (by {@code id}
     * and {@code client_message_id}), so a pushed row never duplicates a row
     * already on screen; an echo of the user's own send reconciles with the
     * optimistic PENDING copy via its {@code client_message_id}.
     */
    private final MessageRealtimeClient.Listener realtimeListener = new MessageRealtimeClient.Listener() {
        @Override
        public void onMessageReceived(String chatId, CreangerMessage message) {
            // Wrong-chat frames are already dropped by the parser/client; this
            // guard is belt-and-braces for a stale callback after a switch.
            if (!chatId.equals(CreangerChatController.this.chatId) || async == null) {
                return;
            }
            boolean inserted = async.applyRealtime(chatId, message);
            if (inserted) {
                onMessagesChanged();
            }
        }

        @Override
        public void onMessageEdited(String chatId, CreangerMessage message) {
            if (!chatId.equals(CreangerChatController.this.chatId) || async == null) {
                return;
            }
            boolean changed = async.applyRealtimeEdit(chatId, message);
            if (changed) {
                onMessagesChanged();
            }
        }

        @Override
        public void onMessageStatusChanged(String chatId, CreangerMessage message) {
            if (!chatId.equals(CreangerChatController.this.chatId) || async == null) {
                return;
            }
            boolean changed = async.applyRealtimeStatus(chatId, message);
            if (changed) {
                onMessagesChanged();
            }
        }

        @Override
        public void onMessageDeleted(String chatId, String messageId) {
            if (!chatId.equals(CreangerChatController.this.chatId) || async == null) {
                return;
            }
            boolean changed = async.applyRealtimeDelete(chatId, messageId);
            if (changed) {
                onMessagesChanged();
            }
        }

        @Override
        public void onReactionChanged(String chatId, MessageReaction reaction, boolean added) {
            if (!chatId.equals(CreangerChatController.this.chatId) || async == null) {
                return;
            }
            boolean changed = async.applyRealtimeReaction(chatId, reaction, added);
            if (changed) {
                onMessagesChanged();
            }
        }

        @Override
        public void onDuplicate(String chatId, CreangerMessage message) {
        }

        @Override
        public void onConnected(String chatId) {
            if (!chatId.equals(CreangerChatController.this.chatId) || async == null || realtime == null) {
                return;
            }
            // First successful connection closes the REST → WS join window: run
            // the same cursor recovery as a reconnect so rows inserted/edited/
            // deleted between the first page load and the live subscription are
            // never lost, even the rows that fall in the replication-slot race.
            recoverAll(realtime.getLastKnownSeq());
        }

        @Override
        public void onDisconnected(String chatId, Throwable cause) {
        }

        @Override
        public void onReconnected(String chatId, long lastKnownSeq) {
            if (!chatId.equals(CreangerChatController.this.chatId) || async == null) {
                return;
            }
            // Realtime is never the source of truth: after any gap run the
            // database cursor syncs — inserts ({@code get_messages_since}),
            // delivery/read statuses ({@code get_message_statuses_since}) and
            // edits/deletes ({@code get_message_changes_since}) — and let the
            // repository merge dedup against whatever already rendered.
            recoverAll(lastKnownSeq);
        }

        @Override
        public void onAuthError(String chatId, Throwable cause) {
        }
    };

    // ---- reconnect recovery ----

    /**
     * Runs every cursor recovery for the open chat after a (re)connect:
     *
     *  1. INSERTs via {@code get_messages_since} — {@code chat_seq} cursor,
     *     seeded with the newest seq the Realtime client has seen (the loaded
     *     page on first connect) so the REST → WS join window is closed
     *  2. delivery/read statuses via {@code get_message_statuses_since} — the
     *     repository's per-chat {@code updated_at} watermark (statuses never
     *     bump {@code chat_seq})
     *  3. edits/deletes via {@code get_message_changes_since} — the same
     *     {@code updated_at} watermark (edits/deletes never bump
     *     {@code chat_seq} either)
     *
     * Each pass is paginated until a short page so a backlog larger than one
     * batch converges in a single recovery; applied rows re-render through the
     * bridge. Realtime frames handled while disconnected are deduplicated by
     * the repository merge (id / {@code client_message_id}) exactly like a REST
     * page, so a recovered row never duplicates one already on screen.
     */
    private void recoverAll(long lastKnownSeq) {
        this.<CreangerMessage>recoverInPages(String.valueOf(lastKnownSeq),
                (chat, cursor, limit, cb) -> async.recoverSince(
                        chat, cursor != null ? Long.parseLong(cursor) : 0L, limit, cb),
                row -> row != null && row.chatSeq != null ? String.valueOf(row.chatSeq) : null);
        this.<MessageStatusUpdate>recoverInPages(null,
                (chat, cursor, limit, cb) -> async.recoverMessageStatuses(chat, cursor, limit, cb),
                row -> row != null ? row.updatedAt : null);
        this.<CreangerMessage>recoverInPages(null,
                (chat, cursor, limit, cb) -> async.recoverMessageChanges(chat, cursor, limit, cb),
                row -> row != null ? row.updatedAt : null);
    }

    /**
     * Drives one paginated recovery pass over the repository/async seams. A page
     * that comes back full is followed by another starting at the last row's
     * cursor, so a backlog never stalls at one batch; a short page (or a cursor
     * that fails to advance, guarding an equal-{@code updated_at} edge) ends the
     * pass.
     */
    private <T> void recoverInPages(@Nullable final String startCursor,
                                    final PageFetcher<T> fetch,
                                    @Nullable final RowCursor<T> cursorOf) {
        fetchRecoveryPage(startCursor, fetch, cursorOf);
    }

    private <T> void fetchRecoveryPage(@Nullable final String cursor,
                                       final PageFetcher<T> fetch,
                                       @Nullable final RowCursor<T> cursorOf) {
        fetch.fetch(chatId, cursor, RECOVERY_LIMIT, new Callback<List<T>>() {
            @Override
            public void onSuccess(List<T> rows) {
                // Any page that returned rows applied them to the repository cache
                // and must refresh the display list here (the wrapped bridge has
                // its own sink, not this controller's listener). Rows that are all
                // duplicates re-render to the same list — harmless, like the REST
                // refresh path.
                if (rows != null && !rows.isEmpty()) {
                    onMessagesChanged();
                }
                if (rows != null && rows.size() == RECOVERY_LIMIT && !rows.isEmpty() && cursorOf != null) {
                    String next = cursorOf.cursorOf(rows.get(rows.size() - 1));
                    if (next != null && !next.equals(cursor)) {
                        fetchRecoveryPage(next, fetch, cursorOf);
                    }
                }
                // Short page (or stalled cursor): the pass is complete. Rows
                // outside the loaded window converge on the next refresh.
            }

            @Override
            public void onError(CreangerApiException apiError, Throwable ioError) {
                // Recovery is best-effort exactly like the REST refresh: a
                // failure merely leaves the gap open for the next reconnect and
                // must never surface a transient network blip as an AUTH/NETWORK
                // phase change.
            }
        });
    }

    /** Fetches one page of a recovery cursor through the async seam. */
    private interface PageFetcher<T> {
        void fetch(String chatId, @Nullable String cursor, int limit, Callback<List<T>> cb);
    }

    /** Derives the resume cursor from the newest row of a recovery page. */
    private interface RowCursor<T> {
        @Nullable String cursorOf(T row);
    }

    private void onPage(MessagePage page) {
        this.hasMore = page.hasMore;
        this.nextOlderSeq = page.nextOlderSeq;
        messages = new ArrayList<>(async.getMessages(chatId));
        setPhase(messages.isEmpty() ? Phase.EMPTY : Phase.READY);
        if (listener != null) {
            listener.onMessagesChanged(this, messages);
        }
    }

    private void onMessagesChanged() {
        messages = new ArrayList<>(async.getMessages(chatId));
        if (listener != null) {
            listener.onMessagesChanged(this, messages);
        }
    }

    private void reportNetworkOrAuthError(CreangerApiException apiError, Throwable ioError) {
        if (apiError != null || !(ioError instanceof java.io.IOException)) {
            setPhase(Phase.AUTH_ERROR);
        } else {
            setPhase(Phase.NETWORK_ERROR);
        }
    }

    private void onSendStatus(String clientMessageId, String content, String serverId, Throwable error) {
        if (error != null) {
            pendingContentById.put(clientMessageId, content);
        } else if (serverId != null) {
            pendingContentById.remove(clientMessageId);
        }
        if (listener != null) {
            listener.onSendStatusChanged(this, clientMessageId, "sent", serverId, error);
        }
    }

    private String getMessageContent(String clientMessageId) {
        for (CreangerMessageUiModel m : messages) {
            if (clientMessageId != null && clientMessageId.equals(m.clientMessageId)) {
                return m.content;
            }
        }
        return null;
    }

    private void setPhase(Phase phase) {
        this.phase = phase;
        if (listener != null) {
            listener.onPhaseChanged(this, phase);
        }
    }
}