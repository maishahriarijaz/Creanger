package com.creanger.app.messenger.creanger;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import com.creanger.app.messenger.creanger.realtime.RealtimeMessageParser;
import com.creanger.app.messenger.creanger.realtime.SocketMessageRealtimeTransport;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Transport subscription tests: the {@code phx_join} frame the
 * {@link SocketMessageRealtimeTransport} sends must subscribe the
 * {@code postgres_changes} channel to INSERT, UPDATE and DELETE on
 * {@code public.messages} (edits, soft-delete tombstones and hard deletes) and
 * on {@code public.message_reactions} (reaction adds/removes, migration 027).
 */
public class SocketMessageRealtimeTransportTest {

    /** (table, event) pairs the join must subscribe. */
    private static Set<String> subscribedPairs() throws JSONException {
        JSONObject join = new JSONObject(SocketMessageRealtimeTransport.buildJoinPayload());
        assertEquals("phx_join", join.optString("event"));
        assertEquals(RealtimeMessageParser.CHANNEL_TOPIC, join.optString("topic"));
        JSONObject payload = join.optJSONObject("payload");
        JSONObject config = payload != null ? payload.optJSONObject("config") : null;
        org.json.JSONArray changes = config != null ? config.optJSONArray("postgres_changes") : null;
        assertNotNull("join must configure postgres_changes", changes);

        Set<String> pairs = new HashSet<>();
        for (int i = 0; i < changes.length(); i++) {
            JSONObject c = changes.optJSONObject(i);
            assertEquals(RealtimeMessageParser.SCHEMA_PUBLIC, c.optString("schema"));
            pairs.add(c.optString("table") + "|" + c.optString("event"));
        }
        return pairs;
    }

    private static boolean subscribed(String table, String event) throws JSONException {
        return subscribedPairs().contains(table + "|" + event);
    }

    @Test
    public void joinSubscribesToInsertOnMessages() throws Exception {
        assertTrue(subscribed(RealtimeMessageParser.TABLE_MESSAGES, RealtimeMessageParser.EVENT_JOIN_INSERT));
    }

    @Test
    public void joinSubscribesToUpdateOnMessages() throws Exception {
        // UPDATE carries both edits and soft-delete tombstones (migration 025).
        assertTrue(subscribed(RealtimeMessageParser.TABLE_MESSAGES, RealtimeMessageParser.EVENT_JOIN_UPDATE));
    }

    @Test
    public void joinSubscribesToDeleteOnMessages() throws Exception {
        assertTrue(subscribed(RealtimeMessageParser.TABLE_MESSAGES, RealtimeMessageParser.EVENT_JOIN_DELETE));
    }

    @Test
    public void joinSubscribesToReactionInserts() throws Exception {
        assertTrue(subscribed(RealtimeMessageParser.TABLE_MESSAGE_REACTIONS, RealtimeMessageParser.EVENT_JOIN_INSERT));
    }

    @Test
    public void joinSubscribesToReactionUpdates() throws Exception {
        assertTrue(subscribed(RealtimeMessageParser.TABLE_MESSAGE_REACTIONS, RealtimeMessageParser.EVENT_JOIN_UPDATE));
    }

    @Test
    public void joinSubscribesToReactionDeletes() throws Exception {
        // remove_reaction (migration 027) is a DELETE on the reactions table.
        assertTrue(subscribed(RealtimeMessageParser.TABLE_MESSAGE_REACTIONS, RealtimeMessageParser.EVENT_JOIN_DELETE));
    }

    @Test
    public void joinSubscribesEachTableExactlyOncePerEvent() throws Exception {
        // exactly {messages, message_reactions} x {INSERT, UPDATE, DELETE}.
        assertEquals(6, subscribedPairs().size());
    }

    @Test
    public void closeBeforeConnectIsSafeAndIdempotent() throws Exception {
        SocketMessageRealtimeTransport t = new SocketMessageRealtimeTransport(
                "ws://127.0.0.1:9/realtime/v1/websocket?vsn=1.0.0");
        t.close();
        t.close();
        // Closed transport drops broadcasts silently instead of throwing.
        t.sendBroadcast("typing", "{}");
    }

    /**
     * Regression: leaving a Creanger chat calls close() on the main thread
     * (ChatActivity.onFragmentDestroy). A TLS socket close can flush/block,
     * which used to throw NetworkOnMainThreadException and FATAL-crash the
     * app. close() must therefore return promptly with state updated
     * synchronously while the socket itself is released in the background.
     */
    @Test(timeout = 30000)
    public void closeReturnsPromptlyOnLiveConnection() throws Exception {
        final java.net.ServerSocket server = new java.net.ServerSocket(0);
        final int port = server.getLocalPort();
        Thread accepter = new Thread(() -> {
            try {
                java.net.Socket s = server.accept();
                java.io.InputStream in = s.getInputStream();
                // Consume the HTTP Upgrade request head.
                int state = 0;
                while (state < 4) {
                    int b = in.read();
                    if (b == -1) {
                        s.close();
                        return;
                    }
                    if (b == '\r' || b == '\n') {
                        state++;
                    } else {
                        state = 0;
                    }
                }
                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: test\r\n\r\n";
                s.getOutputStream().write(response.getBytes("UTF-8"));
                s.getOutputStream().flush();
                // Hold the connection open: the reader blocks in read().
                Thread.sleep(15000);
                s.close();
            } catch (Exception ignored) {
            }
        });
        accepter.setDaemon(true);
        accepter.start();
        try {
            SocketMessageRealtimeTransport t = new SocketMessageRealtimeTransport(
                    "ws://127.0.0.1:" + port + "/realtime/v1/websocket?vsn=1.0.0");
            t.connect("test-token");
            long startMs = System.currentTimeMillis();
            t.close();
            long elapsedMs = System.currentTimeMillis() - startMs;
            // Synchronous state flip + background socket release: must not
            // block on network I/O (loopback close is fast, but the contract
            // is prompt return regardless of socket behavior).
            assertTrue("close() blocked " + elapsedMs + "ms", elapsedMs < 5000);
            t.close();
            t.sendBroadcast("typing", "{}");
        } finally {
            try {
                server.close();
            } catch (Exception ignored) {
            }
        }
    }
}