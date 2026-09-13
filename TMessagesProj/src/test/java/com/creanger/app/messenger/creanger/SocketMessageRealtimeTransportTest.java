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
}