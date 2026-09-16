package com.creanger.app.messenger.creanger.data;

import androidx.annotation.Nullable;

import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.CreangerPresence;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime presence for the open Creanger chat — completes the migration-036
 * surface end to end (the RPC and the REST read existed, nothing called them):
 *
 *  - <b>Own presence</b>: pushes {@code online} via the {@code set_presence}
 *    SECURITY DEFINER RPC while the chat is foregrounded, {@code offline}
 *    (touching last_seen_at) when it pauses. The RPC is idempotent, so a
 *    missed pause transition self-corrects on the next online push.
 *  - <b>Peer presence</b>: REST read of {@code user_presence} for the peer id
 *    (RLS: SELECT for all authenticated) with an in-memory cache; the chat
 *    screen calls {@link Listener#onPeerPresence} to render the header
 *    subtitle. Live transitions arrive over Realtime presence diffs — the
 *    controller only owns the cold-start/reconnect fallback read.
 *
 * Android-free core with an injected background executor and clock (main
 * thread never blocks); every callback is delivered through the injected
 * {@link MainPoster}. No logging per the creanger conventions.
 */
public final class CreangerPresenceController {

    /** Delivers callbacks to the main thread (AndroidUtilities on device). */
    public interface MainPoster {
        void post(Runnable runnable);
    }

    /** Sink for the chat screen; all calls arrive on the main poster. */
    public interface Listener {
        /**
         * @param chatId      the open chat
         * @param peerId      the peer user id the row belongs to
         * @param status      presence_status enum value, or null when the peer has no row
         * @param lastSeenAtMs the peer's last_seen_at (UTC epoch millis, 0 when unknown)
         */
        void onPeerPresence(String chatId, String peerId, @Nullable String status, long lastSeenAtMs);
    }

    /** Peer row cache lifetime before a fresh REST read. */
    static final long PEER_REFRESH_MS = 60_000L;
    /** Offline rows older than this render without a last-seen timestamp. */
    static final long LAST_SEEN_MAX_AGE_MS = 30L * 86_400_000L;

    private final CreangerChatApiClient api;
    private final java.util.concurrent.Callable<String> tokenProvider;
    private final MainPoster poster;
    private final Listener listener;
    private final ExecutorService executor;
    private final Clock clock;

    private final AtomicBoolean onlinePushed = new AtomicBoolean();
    private volatile String chatId;
    private volatile String peerId;
    private volatile boolean destroyed;

    /** peerId -> {status, lastSeenAtMs, cacheExpiryMs} last known rows. */
    private final Map<String, Object[]> peerRows = new HashMap<>();

    /** Injectable clock for JVM tests. */
    public interface Clock {
        long nowMillis();
    }

    public CreangerPresenceController(CreangerChatApiClient api,
                                      java.util.concurrent.Callable<String> tokenProvider,
                                      MainPoster poster,
                                      Listener listener) {
        this(api, tokenProvider, poster, listener,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "creanger-presence");
                    t.setDaemon(true);
                    return t;
                }),
                System::currentTimeMillis);
    }

    /** Full injection constructor — public so JVM tests in the creanger test package can drive it synchronously. */
    public CreangerPresenceController(CreangerChatApiClient api,
                                      java.util.concurrent.Callable<String> tokenProvider,
                                      MainPoster poster,
                                      Listener listener,
                                      ExecutorService executor,
                                      Clock clock) {
        this.api = api;
        this.tokenProvider = tokenProvider;
        this.poster = poster;
        this.listener = listener;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * Binds the controller to the open chat: pushes own {@code online} and
     * fetches the peer row (cached within {@link #PEER_REFRESH_MS}).
     */
    public void onChatOpened(String chatId, String peerId) {
        if (chatId == null || destroyed) {
            return;
        }
        this.chatId = chatId;
        this.peerId = peerId;
        pushOwn(CreangerPresence.ONLINE, true);
        if (peerId != null) {
            Object[] cached = peerRows.get(peerId);
            long now = clock.nowMillis();
            if (cached == null || now - (Long) cached[2] > PEER_REFRESH_MS) {
                fetchPeer(chatId, peerId);
            } else {
                emitPeer(chatId, peerId, (String) cached[0], (Long) cached[1]);
            }
        }
    }

    /** Chat paused: pushes own {@code offline} (last_seen_at refreshed). */
    public void onChatPaused() {
        if (destroyed) {
            return;
        }
        pushOwn(CreangerPresence.OFFLINE, true);
    }

    /** Stops everything; safe to call repeatedly (fragment teardown). */
    public void destroy() {
        // Push offline BEFORE flagging destroyed — pushOwn early-returns once
        // destroyed, and the last_seen_at refresh must still fire.
        pushOwn(CreangerPresence.OFFLINE, true);
        destroyed = true;
        chatId = null;
        peerId = null;
        peerRows.clear();
    }

    /**
     * Live peer transition from a Realtime presence diff: refreshes the cache
     * entry from the REST row (the diff carries no last_seen value) so the
     * header shows a consistent state.
     */
    public void onRealtimePresenceDiff(String chatId, String peerUserId) {
        if (destroyed || chatId == null || peerUserId == null) {
            return;
        }
        if (!chatId.equals(this.chatId) || !peerUserId.equals(this.peerId)) {
            return;
        }
        fetchPeer(chatId, peerUserId);
    }

    // ---- internals ----

    private void pushOwn(String status, boolean touchLastSeen) {
        if (destroyed) {
            return;
        }
        if (CreangerPresence.ONLINE.equals(status)) {
            if (!onlinePushed.compareAndSet(false, true)) {
                return; // already online; the RPC would be a no-op write
            }
        } else {
            onlinePushed.set(false);
        }
        executor.execute(() -> {
            try {
                api.setPresence(tokenProvider.call(), status, touchLastSeen);
            } catch (Exception ignored) {
                // Presence is best-effort; a missed push self-corrects on the
                // next transition (the RPC upserts and normalizes timestamps).
            }
        });
    }

    /** Periodic own-heartbeat while the chat stays foregrounded. */
    void heartbeatTick() {
        if (chatId != null && onlinePushed.get()) {
            onlinePushed.set(false);
            pushOwn(CreangerPresence.ONLINE, true);
        }
    }

    private void fetchPeer(final String chatId, final String peerId) {
        if (destroyed) {
            return;
        }
        executor.execute(() -> {
            String status = null;
            long lastSeen = 0;
            try {
                JSONArray rows = api.getPresence(tokenProvider.call(), Collections.singletonList(peerId));
                if (rows.length() > 0) {
                    JSONObject row = rows.optJSONObject(0);
                    if (row != null) {
                        status = row.isNull("status") ? null : row.optString("status", null);
                        lastSeen = parseEpoch(row.optString("last_seen_at", null));
                    }
                }
            } catch (Exception ignored) {
                // keep nulls: the header falls back to "no subtitle"
            }
            final String fStatus = status;
            final long fLastSeen = lastSeen;
            Object[] cached = peerRows.get(peerId);
            long now = clock.nowMillis();
            if (cached == null || (Long) cached[2] < now) {
                peerRows.put(peerId, new Object[]{fStatus, fLastSeen, now + PEER_REFRESH_MS});
            }
            emitPeer(chatId, peerId, fStatus, fLastSeen);
        });
    }

    private void emitPeer(String chatId, String peerId, @Nullable String status, long lastSeen) {
        if (listener == null || destroyed) {
            return;
        }
        final String fStatus = status;
        final long fLastSeen = lastSeen;
        poster.post(() -> {
            if (!destroyed) {
                listener.onPeerPresence(chatId, peerId, fStatus, fLastSeen);
            }
        });
    }

    /** Reuses the shared ISO-8601 parser from the realtime meter. */
    static long parseEpoch(@Nullable String iso) {
        return com.creanger.app.messenger.creanger.realtime.RealtimeLatency.parseEpochMillis(iso);
    }
}
