package com.creanger.app.messenger.creanger.realtime;

import androidx.annotation.Nullable;

/**
 * Consumer of realtime connection-quality samples — the contract behind the
 * chat-screen latency badge. Two independent producers feed it:
 *
 *  - {@link MessageRealtimeClient} delivers smoothed <b>E2E</b> samples
 *    (server row timestamp → frame received) on the poster thread;
 *  - {@link SocketMessageRealtimeTransport.RealtimeLatencySink} delivers raw
 *    <b>RTT</b> samples (heartbeat round trips) on the reader thread — the
 *    client folds them into the same meter and re-emits them on the poster.
 *
 * Implementations must be cheap (a text/color swap) because samples arrive
 * on UI threads.
 */
public interface RealtimeLatencySink {

    /**
     * @param chatId the subscribed chat the sample belongs to
     * @param kind   which series produced the sample (E2E or RTT)
     * @param ms     the latency in milliseconds (already smoothed, &ge; 0)
     */
    void onLatencySample(@Nullable String chatId, RealtimeLatency.Kind kind, long ms);
}
