package com.creanger.app.messenger.creanger.realtime;

import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Any-chat Realtime tap for the dialog list (pure JVM, fully unit-testable).
 *
 * The open-chat {@link MessageRealtimeClient} subscribes to exactly one chat;
 * the dialog list instead needs a nudge whenever ANY chat gains a message,
 * edit, status advance or delete so row previews/badges refresh without
 * leaving the screen. This watcher holds one channel subscription, parses
 * every frame with {@link RealtimeMessageParser#parseAnyChat(String)} (no chat
 * filtering) and surfaces message-table events through {@link Listener}.
 *
 * Lifecycle mirrors the client: blocking token acquisition + socket open run
 * on a background executor, callbacks arrive on the injected poster, drops
 * reconnect with capped exponential backoff, auth failures stop permanently,
 * and {@link #close()} tears everything down (logout / pause / destroy).
 */
public final class DialogListRealtimeWatcher {

    /** Supplies the current authenticated Creanger access-token JWT. */
    public interface AccessTokenProvider {
        String requireAccessToken() throws java.io.IOException,
                com.creanger.app.messenger.creanger.api.CreangerApiException;
    }

    /** Sink; every call is delivered on the poster thread. */
    public interface Listener {
        /**
         * A message-table event (insert/edit/status/delete) arrived for a
         * chat. The caller should refresh the dialog rows (coalesced).
         */
        void onMessagesChanged(String chatId);
    }

    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private final MessageRealtimeTransport transport;
    private final AccessTokenProvider tokenProvider;
    private final MessageRealtimeClient.RunnablePoster poster;
    private final MessageRealtimeClient.ReconnectScheduler scheduler;
    private final Listener listener;
    private final ExecutorService connectExecutor;

    private volatile boolean active;
    private volatile boolean connecting;
    private volatile long reconnectBackoffMs = INITIAL_BACKOFF_MS;

    public DialogListRealtimeWatcher(MessageRealtimeTransport transport,
                                     AccessTokenProvider tokenProvider,
                                     MessageRealtimeClient.RunnablePoster poster,
                                     MessageRealtimeClient.ReconnectScheduler scheduler,
                                     Listener listener) {
        this(transport, tokenProvider, poster, scheduler, listener, null);
    }

    public DialogListRealtimeWatcher(MessageRealtimeTransport transport,
                                     AccessTokenProvider tokenProvider,
                                     MessageRealtimeClient.RunnablePoster poster,
                                     MessageRealtimeClient.ReconnectScheduler scheduler,
                                     Listener listener,
                                     @Nullable ExecutorService connectExecutor) {
        this.transport = transport;
        this.tokenProvider = tokenProvider;
        this.poster = poster;
        this.scheduler = scheduler;
        this.listener = listener;
        this.connectExecutor = connectExecutor != null ? connectExecutor : daemonExecutor();
    }

    private static ExecutorService daemonExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "creanger-list-realtime-connect");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts watching (no-op when already active). The blocking open runs on
     * the background executor — safe to call from the UI thread.
     */
    public void start() {
        if (active) {
            return;
        }
        active = true;
        reconnectBackoffMs = INITIAL_BACKOFF_MS;
        transport.setListener(transportListener);
        connect();
    }

    /** Stops watching and closes the socket. Idempotent. */
    public void close() {
        active = false;
        transport.close();
    }

    /** Releases the background executor; the watcher must not be reused after. */
    public void destroy() {
        close();
        connectExecutor.shutdownNow();
    }

    public boolean isActive() {
        return active;
    }

    private void connect() {
        if (!active || connecting) {
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

    private void connectBlocking() {
        if (!active) {
            return;
        }
        final String token;
        try {
            token = tokenProvider.requireAccessToken();
        } catch (Throwable t) {
            scheduleReconnect();
            return;
        }
        try {
            transport.connect(token);
        } catch (Throwable t) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!active) {
            return;
        }
        long delay = reconnectBackoffMs;
        reconnectBackoffMs = Math.min(reconnectBackoffMs * 2, MAX_BACKOFF_MS);
        scheduler.schedule(delay, () -> {
            if (active) {
                connect();
            }
        });
    }

    private final MessageRealtimeTransport.Listener transportListener = new MessageRealtimeTransport.Listener() {
        @Override
        public void onTransportConnected() {
            if (!active) {
                transport.close();
            }
        }

        @Override
        public void onTransportFrame(String frameJson) {
            if (!active) {
                return;
            }
            RealtimeMessageParser.Result result = RealtimeMessageParser.parseAnyChat(frameJson);
            switch (result.kind) {
                case MESSAGE_INSERT:
                case MESSAGE_EDIT:
                case MESSAGE_STATUS:
                case MESSAGE_DELETE: {
                    final String chatId = result.chatId != null
                            ? result.chatId
                            : (result.message != null ? result.message.chatId : null);
                    if (chatId != null && listener != null) {
                        poster.post(() -> listener.onMessagesChanged(chatId));
                    }
                    break;
                }
                default:
                    break;
            }
        }

        @Override
        public void onTransportClosed(@Nullable Throwable cause) {
            if (!active) {
                return;
            }
            scheduleReconnect();
        }

        @Override
        public void onTransportUnauthorized(String reason) {
            active = false;
            transport.close();
        }
    };
}
