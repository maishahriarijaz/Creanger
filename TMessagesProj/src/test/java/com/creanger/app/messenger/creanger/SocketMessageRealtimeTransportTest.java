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

    @Test
    public void accessTokenPayloadAuthorizesChannelWithJwt() throws Exception {
        JSONObject frame = new JSONObject(
                SocketMessageRealtimeTransport.buildAccessTokenPayload("user-jwt-123", "7"));
        assertEquals(RealtimeMessageParser.CHANNEL_TOPIC, frame.optString("topic"));
        assertEquals("access_token", frame.optString("event"));
        assertEquals("user-jwt-123", frame.optJSONObject("payload").optString("access_token"));
        assertEquals("7", frame.optString("ref"));
    }

    @Test
    public void heartbeatPayloadTargetsPhoenixTopic() throws Exception {
        JSONObject frame = new JSONObject(
                SocketMessageRealtimeTransport.buildHeartbeatPayload("3"));
        assertEquals("phoenix", frame.optString("topic"));
        assertEquals("heartbeat", frame.optString("event"));
        assertEquals("3", frame.optString("ref"));
    }

    /**
     * Wire proof: after the HTTP upgrade the client must push the channel
     * join AND the user-JWT {@code access_token} frame. Without the latter
     * the server runs the subscription as anon and rejects every row with
     * {@code Error 401: Unauthorized} — the "nothing is realtime" failure.
     */
    @Test(timeout = 30000)
    public void connectPushesJoinAndAccessTokenOnWire() throws Exception {
        final java.net.ServerSocket server = new java.net.ServerSocket(0);
        final int port = server.getLocalPort();
        final java.util.List<String> frames = new java.util.ArrayList<>();
        Thread accepter = new Thread(() -> {
            try {
                java.net.Socket s = server.accept();
                s.setSoTimeout(10000);
                java.io.InputStream in = s.getInputStream();
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
                // Decode masked client text frames until both expected
                // frames arrive (join + access_token).
                java.io.DataInputStream din = new java.io.DataInputStream(in);
                long deadline = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < deadline && frames.size() < 2) {
                    int b0;
                    try {
                        b0 = din.readUnsignedByte();
                    } catch (java.io.IOException timeout) {
                        break;
                    }
                    int b1 = din.readUnsignedByte();
                    int len = b1 & 0x7F;
                    if (len == 126) {
                        len = din.readUnsignedShort();
                    } else if (len == 127) {
                        len = (int) din.readLong();
                    }
                    byte[] mask = new byte[4];
                    din.readFully(mask);
                    byte[] payload = new byte[len];
                    din.readFully(payload);
                    for (int i = 0; i < len; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                    if ((b0 & 0x0F) == 0x1) {
                        frames.add(new String(payload, "UTF-8"));
                    }
                }
                s.close();
            } catch (Exception ignored) {
            }
        });
        accepter.setDaemon(true);
        accepter.start();
        SocketMessageRealtimeTransport t = new SocketMessageRealtimeTransport(
                "ws://127.0.0.1:" + port + "/realtime/v1/websocket?vsn=1.0.0");
        try {
            t.connect("user-jwt-123");
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline && frames.size() < 2) {
                Thread.sleep(50);
            }
        } finally {
            t.close();
            try {
                server.close();
            } catch (Exception ignored) {
            }
        }
        assertEquals("expected join + access_token frames, got: " + frames, 2, frames.size());
        JSONObject join = new JSONObject(frames.get(0));
        assertEquals("phx_join", join.optString("event"));
        JSONObject auth = new JSONObject(frames.get(1));
        assertEquals("access_token", auth.optString("event"));
        assertEquals(RealtimeMessageParser.CHANNEL_TOPIC, auth.optString("topic"));
        assertEquals("user-jwt-123", auth.optJSONObject("payload").optString("access_token"));
    }
}