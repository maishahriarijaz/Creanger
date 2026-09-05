package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.data.CreangerMessageMapping;
import com.creanger.app.messenger.creanger.data.CreangerMessageUiModel;
import com.creanger.app.messenger.creanger.data.MessageRepository;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageType;
import com.creanger.app.messenger.creanger.realtime.RealtimeMessageParser;
import com.creanger.app.messenger.creanger.realtime.RealtimeMessageParser.Kind;
import com.creanger.app.messenger.creanger.realtime.RealtimeMessageParser.Result;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Replies / quoted messages on {@link MessageRepository} and the Creanger →
 * Telegram reply mapping.
 *
 * The backend already carries the reply relationship as
 * {@code messages.reply_to_message_id} (a FK to the same table, migration 004
 * + same-chat validation in 017) and {@code send_text_message} accepts it, so
 * this phase is entirely client/render-side. The Creanger UUID is the one and
 * only reply identity that ever crosses the wire — synthetic Telegram ids are
 * view-only and never used as backend identity.
 *
 * Covered scenarios:
 *  - reply send (RPC carries the authoritative UUID; confirmed row keeps it)
 *  - reply metadata persistence through REST lists and the UI projection
 *  - reply render mapping (banner resolvable only when the target is loaded)
 *  - realtime reply propagation (INSERT row carries the relationship)
 *  - reconnect recovery preserves reply relationships
 *  - deleted / missing target renders gracefully (no banner, no crash)
 *  - unauthorized reply (pending row kept for retry, reply preserved)
 *  - account isolation (per-owner caches, cleared on logout)
 *  - legacy Telegram path unchanged (plain sends stay null-reply)
 */
public class MessageReplyTest {

    private static final String OWNER = "uuid-1";
    private static final String OTHER_OWNER = "uuid-9";
    private static final String SENDER = "uuid-2"; // another chat member
    private static final String CHAT = "chat-1";
    private static final String TARGET_ID = "m-1";
    private static final String REPLY_ID = "m-2";

    private static final class ScriptedTransport implements CreangerHttpTransport {
        final List<TransportResponse> responses = new ArrayList<>();
        final List<ApiRequest> requests = new ArrayList<>();

        @Override
        public TransportResponse execute(ApiRequest request) throws IOException {
            requests.add(request);
            TransportResponse response = responses.remove(0);
            if (response == null) {
                throw new IOException("no canned response for " + request.method + " " + request.path);
            }
            return response;
        }

        TransportResponse json(int status, String body) {
            return new TransportResponse(status, body, null);
        }
    }

    private static final class InMemoryStore implements CreangerTokenStore {
        volatile AuthSession stored;

        @Override
        public AuthSession load() {
            return stored;
        }

        @Override
        public void store(AuthSession session) {
            this.stored = session;
        }

        @Override
        public void clear() {
            this.stored = null;
        }
    }

    private static CreangerAuthEngine engineFor(ScriptedTransport t, InMemoryStore store, String ownerId) {
        store.store(new AuthSession("acc-live", "ref",
                new CreangerUser(ownerId, "alice", "a@x.com", true, null, null, null),
                System.currentTimeMillis()));
        return new CreangerAuthEngine(new SupabaseAuthClient(t), store);
    }

    private static MessageRepository repo(ScriptedTransport t, InMemoryStore store) {
        return new MessageRepository(engineFor(t, store, OWNER), new CreangerChatApiClient(t));
    }

    private static MessageRepository repoFor(ScriptedTransport t, InMemoryStore store, String ownerId) {
        return new MessageRepository(engineFor(t, store, ownerId), new CreangerChatApiClient(t));
    }

    /** messages/list row (newest-first pages) including a reply relationship. */
    private static String row(String id, long chatSeq, String content, String replyTo) {
        return "{\"id\":\"" + id + "\",\"chat_id\":\"" + CHAT + "\",\"sender_id\":\"" + SENDER + "\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"sent\","
                + "\"client_message_id\":null,\"chat_seq\":" + chatSeq
                + ",\"reply_to_message_id\":" + quoteNullable(replyTo)
                + ",\"created_at\":\"2026-08-15T08:0" + chatSeq + ":00Z\",\"edited_at\":null,\"deleted_at\":null,"
                + "\"updated_at\":\"2026-08-15T08:0" + chatSeq + ":00Z\"}";
    }

    /** get_messages_since RPC row (id keyed as message_id). */
    private static String sinceRow(String id, long chatSeq, String content, String replyTo) {
        return row(id, chatSeq, content, replyTo).replace("\"id\":", "\"message_id\":");
    }

    private static String quoteNullable(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String rowsJson(String... rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(rows[i]);
        }
        return sb.append(']').toString();
    }

    private static List<ApiRequest> requestsFor(List<ApiRequest> requests, String path) {
        List<ApiRequest> out = new ArrayList<>();
        for (ApiRequest r : requests) {
            if (path.equals(r.path)) {
                out.add(r);
            }
        }
        return out;
    }

    private static CreangerMessageUiModel ui(CreangerMessage m) {
        return CreangerMessageUiModel.from(m, OWNER);
    }

    // ---- 1. reply send ----

    @Test
    public void replySendCarriesAuthoritativeUuidToRpcAndConfirm() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);

        t.responses.add(t.json(200, "\"" + REPLY_ID + "\""));
        String confirmed = mr.sendTextMessage(CHAT, "cm-1", "hello", TARGET_ID);

        assertEquals(REPLY_ID, confirmed);
        List<ApiRequest> rpcs = requestsFor(t.requests, "/rest/v1/rpc/send_text_message");
        assertEquals(1, rpcs.size());
        // The wire identity is the authoritative Creanger UUID — never a
        // synthetic Telegram Long id.
        assertTrue("RPC must carry the replied-to Creanger UUID",
                rpcs.get(0).jsonBody.contains("\"p_reply_to_message_id\":\"m-1\""));
        assertFalse("body must not embed a synthetic integer id",
                rpcs.get(0).jsonBody.contains("\"p_reply_to_message_id\":-"));

        List<CreangerMessage> cached = mr.getCachedMessages(CHAT);
        assertEquals(1, cached.size());
        assertEquals(REPLY_ID, cached.get(0).id);
        assertEquals(TARGET_ID, cached.get(0).replyToMessageId);
    }

    // ---- 2. reply metadata persistence ----

    @Test
    public void replyMetadataPersistsThroughRestListAndUiProjection() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);

        // Newest first: the reply on top, its target below.
        t.responses.add(t.json(200, rowsJson(
                row(REPLY_ID, 2, "reply text", TARGET_ID),
                row(TARGET_ID, 1, "original", null))));
        mr.refreshMessages(CHAT, 30);

        List<CreangerMessage> cached = mr.getCachedMessages(CHAT);
        assertEquals(2, cached.size());

        CreangerMessage reply = cached.get(0);
        assertEquals(REPLY_ID, reply.id);
        assertEquals(TARGET_ID, reply.replyToMessageId); // relationship preserved
        assertEquals(null, cached.get(1).replyToMessageId); // the target itself is not a reply

        // The display projection carries it exactly (same authoritative UUID).
        CreangerMessageUiModel ui = ui(reply);
        assertEquals(REPLY_ID, ui.id);
        assertEquals(TARGET_ID, ui.replyToMessageId);
    }

    // ---- 3. reply render mapping ----

    @Test
    public void replyRenderMappingResolvesByUuidAndHandlesMissingTarget() {
        CreangerMessageMapping mapping = new CreangerMessageMapping(OWNER);
        CreangerMessage target = new CreangerMessage(TARGET_ID, CHAT, SENDER, MessageType.TEXT, "original",
                MessageStatus.SENT, null, 1L, null, null, null, null, false);
        CreangerMessage reply = new CreangerMessage(REPLY_ID, CHAT, ownerFor(SENDER), MessageType.TEXT, "reply",
                MessageStatus.SENT, null, 2L, null, null, null, null, TARGET_ID, false);
        List<CreangerMessageUiModel> loaded = new ArrayList<>();
        loaded.add(ui(reply));
        loaded.add(ui(target));

        // Target present → banner can render, routed through the authoritative
        // UUID (stable synthetic view id, identity never guesses a Telegram id).
        assertTrue(mapping.canRenderReply(loaded, TARGET_ID));
        assertEquals(mapping.getSyntheticId(TARGET_ID), mapping.getSyntheticId(TARGET_ID));

        // A null reply (plain message) can never render a banner.
        assertFalse(mapping.canRenderReply(loaded, null));

        // Reply display-name resolution: own message → owner display name (or
        // "You"), foreign sender → a neutral label (never the UUID).
        assertEquals("Alice", CreangerMessageMapping.replyDisplayName(OWNER, OWNER, "Alice"));
        assertEquals("You", CreangerMessageMapping.replyDisplayName(OWNER, OWNER, null));
        assertEquals(CreangerMessageMapping.UNKNOWN_REPLY_SENDER,
                CreangerMessageMapping.replyDisplayName(SENDER, OWNER, null));
        assertFalse("banner author must not leak the Creanger UUID",
                CreangerMessageMapping.replyDisplayName(SENDER, OWNER, null).contains(SENDER));
    }

    /** Sender id that is "out" for the OWNER in a one-to-one sense. */
    private static String ownerFor(String senderId) {
        return senderId;
    }

    // ---- 4. realtime reply propagation ----

    @Test
    public void realtimeReplyInsertCarriesRelationshipAndIngests() throws Exception {
        // The realtime INSERT row carries reply_to_message_id; the parser maps
        // it onto the model verbatim (authoritative UUID).
        Result result = RealtimeMessageParser.parse(CHAT,
                modernReplyInsert(REPLY_ID, CHAT, 7, "reply text", TARGET_ID));
        assertEquals(Kind.MESSAGE_INSERT, result.kind);
        assertNotNull(result.message);
        assertEquals(REPLY_ID, result.message.id);
        assertEquals(TARGET_ID, result.message.replyToMessageId);

        // The repository ingests it (dedup merge) and keeps the relationship.
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        assertTrue(mr.ingestRealtime(CHAT, result.message));
        assertEquals(1, mr.getCachedMessages(CHAT).size());
        assertEquals(TARGET_ID, mr.getCachedMessages(CHAT).get(0).replyToMessageId);
    }

    private static String modernReplyInsert(String id, String chatId, long chatSeq,
                                            String content, String replyTo) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"type\":\"postgres_changes\",\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"INSERT\","
                + "\"new\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"" + SENDER + "\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"sent\","
                + "\"client_message_id\":null,\"chat_seq\":" + chatSeq
                + ",\"created_at\":\"2026-08-16T10:00:00Z\","
                + "\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"2026-08-16T10:00:00Z\","
                + "\"is_system_message\":false,\"metadata\":{},\"topic_id\":null,"
                + "\"reply_to_message_id\":" + quoteNullable(replyTo) + ",\"scheduled_at\":null}"
                + "}}}";
    }

    // ---- 5. reconnect recovery ----

    @Test
    public void reconnectRecoveryPreservesReplyRelationships() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);

        // get_messages_since recovery: reply + target rows (oldest first).
        t.responses.add(t.json(200, rowsJson(
                sinceRow(TARGET_ID, 1, "original", null),
                sinceRow(REPLY_ID, 2, "reply text", TARGET_ID))));
        List<CreangerMessage> recovered = mr.syncSince(CHAT, 0, 100);

        assertEquals(2, recovered.size());
        assertEquals(REPLY_ID, recovered.get(1).id);
        assertEquals(TARGET_ID, recovered.get(1).replyToMessageId);
        assertEquals(TARGET_ID, mr.getCachedMessages(CHAT).get(0).replyToMessageId);
    }

    // ---- 6. deleted / missing target ----

    @Test
    public void deletedTargetRemovesBannerButPreservesRelationship() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        t.responses.add(t.json(200, rowsJson(
                row(REPLY_ID, 2, "reply text", TARGET_ID),
                row(TARGET_ID, 1, "original", null))));
        mr.refreshMessages(CHAT, 30);
        assertEquals(2, mr.getCachedMessages(CHAT).size());

        // The target is deleted for everyone: Realtime tombstone removes it.
        assertTrue(mr.applyRealtimeDelete(CHAT, TARGET_ID));
        List<CreangerMessage> cached = mr.getCachedMessages(CHAT);
        assertEquals(1, cached.size());
        assertEquals(REPLY_ID, cached.get(0).id);
        // The reply's relationship is immutable in the model — the backend row
        // still points at the (soft-deleted) UUID.
        assertEquals(TARGET_ID, cached.get(0).replyToMessageId);

        // Render mapping: no loaded target → no banner (graceful, no crash).
        CreangerMessageMapping mapping = new CreangerMessageMapping(OWNER);
        List<CreangerMessageUiModel> loaded = new ArrayList<>();
        for (CreangerMessage m : cached) {
            loaded.add(ui(m));
        }
        assertFalse(mapping.canRenderReply(loaded, TARGET_ID));
    }

    // ---- 7. unauthorized reply ----

    @Test
    public void unauthorizedReplyKeepsPendingRowWithReplyForRetry() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);

        // Non-member / reply-to another chat / RLS rejection → 403.
        t.responses.add(t.json(403, ""));
        try {
            mr.sendTextMessage(CHAT, "cm-1", "hello", TARGET_ID);
            fail("expected CreangerApiException for unauthorized reply send");
        } catch (CreangerApiException e) {
            assertEquals(403, e.statusCode);
        }

        // The optimistic pending copy stays visible with its reply preserved.
        List<CreangerMessage> cached = mr.getCachedMessages(CHAT);
        assertEquals(1, cached.size());
        assertTrue(cached.get(0).isLocal);
        assertEquals(TARGET_ID, cached.get(0).replyToMessageId);

        // A retry with the same client id re-sends with the reply preserved.
        t.responses.add(t.json(200, "\"" + REPLY_ID + "\""));
        mr.sendTextMessage(CHAT, "cm-1", "hello");
        List<ApiRequest> rpcs = requestsFor(t.requests, "/rest/v1/rpc/send_text_message");
        assertEquals(2, rpcs.size());
        assertTrue("retry must keep the same reply UUID",
                rpcs.get(1).jsonBody.contains("\"p_reply_to_message_id\":\"m-1\""));
        assertEquals(REPLY_ID, mr.getCachedMessages(CHAT).get(0).id);
        assertEquals(TARGET_ID, mr.getCachedMessages(CHAT).get(0).replyToMessageId);
    }

    // ---- 8. account isolation ----

    @Test
    public void repliesAreIsolatedPerAccount() throws Exception {
        InMemoryStore storeA = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mrA = repo(t, storeA);
        t.responses.add(t.json(200, "\"" + REPLY_ID + "\""));
        mrA.sendTextMessage(CHAT, "cm-1", "hello", TARGET_ID);
        assertEquals(1, mrA.getCachedMessages(CHAT).size());
        assertEquals(TARGET_ID, mrA.getCachedMessages(CHAT).get(0).replyToMessageId);

        // A different account on a fresh repository/engine never sees it.
        InMemoryStore storeB = new InMemoryStore();
        MessageRepository mrB = repoFor(t, storeB, OTHER_OWNER);
        assertTrue("account B must not see account A's reply cache",
                mrB.getCachedMessages(CHAT).isEmpty());

        // Local logout wipes the per-account cache (and the reply state with it).
        mrA.clearCachedMessages();
        assertTrue(mrA.getCachedMessages(CHAT).isEmpty());
    }

    // ---- 9. legacy / plain path unchanged ----

    @Test
    public void plainSendsAndNoReplyShapesRemainUnchanged() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);

        // Plain send: no reply → RPC carries a null reply target, same as the
        // pre-reply wire contract (legacy path untouched).
        t.responses.add(t.json(200, "\"" + REPLY_ID + "\""));
        mr.sendTextMessage(CHAT, "cm-1", "hello");
        List<ApiRequest> rpcs = requestsFor(t.requests, "/rest/v1/rpc/send_text_message");
        assertEquals(1, rpcs.size());
        assertTrue(rpcs.get(0).jsonBody.contains("\"p_reply_to_message_id\":null"));
        assertEquals(null, mr.getCachedMessages(CHAT).get(0).replyToMessageId);

        // Messages without a reply can never render a banner.
        CreangerMessage plain = new CreangerMessage(TARGET_ID, CHAT, SENDER, MessageType.TEXT, "x",
                MessageStatus.SENT, null, 1L, null, null, null, null, false);
        CreangerMessageMapping mapping = new CreangerMessageMapping(OWNER);
        List<CreangerMessageUiModel> loaded = new ArrayList<>();
        loaded.add(ui(plain));
        assertFalse(mapping.canRenderReply(loaded, plain.replyToMessageId));

        // get_messages_since rows without a reply stay null in recovery too.
        t.responses.add(t.json(200, rowsJson(sinceRow(TARGET_ID, 1, "x", null))));
        List<CreangerMessage> recovered = mr.syncSince(CHAT, 0, 100);
        assertEquals(TARGET_ID, recovered.get(0).id);
        assertEquals(null, recovered.get(0).replyToMessageId);
    }
}