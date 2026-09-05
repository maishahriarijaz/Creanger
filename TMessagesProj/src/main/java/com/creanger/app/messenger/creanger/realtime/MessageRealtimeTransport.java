package com.creanger.app.messenger.creanger.realtime;

import java.io.IOException;

/**
 * Raw Realtime wire transport abstraction — deliberately thin so the whole
 * realtime intake (parsing, dedup, reconnect, recovery) is JVM-testable with a
 * scripted transport while the device build uses
 * {@link SocketMessageRealtimeTransport}.
 *
 * Implementations own the socket lifecycle (connect handshake, Phoenix join,
 * frame read/write, close). They deliver events through {@link Listener} on an
 * internal thread; the client relocates every callback onto its poster.
 */
public interface MessageRealtimeTransport {

    interface Listener {
        /** The WebSocket upgraded and the channel join was sent. */
        void onTransportConnected();

        /** One text frame (raw JSON) received from the Realtime server. */
        void onTransportFrame(String frameJson);

        /** The socket dropped or was closed; {@code cause} may be null. */
        void onTransportClosed(Throwable cause);

        /** Permanent authorization failure — the connection must NOT be retried. */
        void onTransportUnauthorized(String reason);
    }

    void setListener(Listener listener);

    /**
     * Opens the connection and joins the channel. {@code accessToken} is the
     * current authenticated Creanger JWT; it is used as the Realtime
     * {@code apikey} parameter and the {@code Authorization} header so Row
     * Level Security scopes the channel to the user.
     */
    void connect(String accessToken);

    /**
     * Sends an ephemeral Phoenix broadcast event on the joined channel. The
     * event is NOT persisted to the database — it is delivered in real-time
     * to all other subscribers on the same channel and discarded. Used for
     * typing indicators and other ephemeral UI state.
     *
     * @param event   the broadcast event name (e.g. {@code "typing"})
     * @param payload event-specific payload (JSON-serializable)
     */
    void sendBroadcast(String event, String payloadJson) throws IOException;

    /** Closes the socket and tears down the reader thread. Idempotent. */
    void close();
}