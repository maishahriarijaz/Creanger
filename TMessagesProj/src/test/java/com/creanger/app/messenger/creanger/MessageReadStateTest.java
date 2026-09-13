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
import com.creanger.app.messenger.creanger.data.MessageRepository;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatusUpdate;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageType;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Delivery/read state tests (migration 026) on {@link MessageRepository}:
 *
 *  - monotonically advancing delivery/read statuses from Realtime events, with
 *    regressions and out-of-order events rejected (grey sent/delivered check →
 *    blue read check, never back)
 *  - the recipient-only {@code mark_message_status} RPC: applied on success,
 *    rejected authorization leaves the cache untouched
 *  - reconnect recovery via {@code get_message_statuses_since}, anchored on a
 *    per-chat {@code updated_at} watermark that survives consecutive reconnects
 *    and is reset on logout / account switch
 *  - the mapping {@code isUnread(out, status)} rule that drives Telegram's
 *    delivered/grey vs read/blue check tint for outbound rows only
 */
public class MessageReadStateTest {

    private static final String OWNER = "uuid-1";
    private static final String SENDER = "uuid-2"; // another chat member
    private static final String CHAT = "chat-1";

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

    private static CreangerAuthEngine authenticatedEngine(ScriptedTransport t, InMemoryStore store) {
        store.store(new AuthSession("acc-live", "ref",
                new CreangerUser(OWNER, "alice", "a@x.com", true, null, null, null),
                System.currentTimeMillis()));
        return new CreangerAuthEngine(new SupabaseAuthClient(t), store);
    }

    private static MessageRepository repo(ScriptedTransport t, InMemoryStore store) {
        return new MessageRepository(authenticatedEngine(t, store),
                new CreangerChatApiClient(t));
    }

    private static String msgRow(String id, long chatSeq, String content, String status, String updatedAt) {
        return "{\"id\":\"" + id + "\",\"chat_id\":\"" + CHAT + "\",\"sender_id\":\"" + SENDER + "\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"" + status + "\","
                + "\"client_message_id\":null,\"chat_seq\":" + chatSeq
                + ",\"created_at\":\"2026-08-15T08:01:00Z\",\"edited_at\":null,\"deleted_at\":null,"
                + "\"updated_at\":\"" + updatedAt + "\"}";
    }

    private static String statusRow(String messageId, String status, String updatedAt) {
        return "{\"message_id\":\"" + messageId + "\",\"status\":\"" + status
                + "\",\"updated_at\":\"" + updatedAt + "\"}";
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

    /** Seeds the cache with one incoming message and returns its cached row. */
    private static CreangerMessage seed(MessageRepository mr, ScriptedTransport t,
                                        String status, String updatedAt) throws Exception {
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi", status, updatedAt))));
        mr.refreshMessages(CHAT, 30);
        assertEquals(1, mr.getCachedMessages(CHAT).size());
        return mr.getCachedMessages(CHAT).get(0);
    }

    private static CreangerMessage message(String id, String status, String updatedAt) {
        return new CreangerMessage(id, CHAT, SENDER, MessageType.TEXT, "hi", status,
                null, 1L, "2026-08-15T08:01:00Z", null, null, updatedAt, false);
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

    // ---- realtime status apply (monotonic) ----

    @Test
    public void realtimeStatusAdvanceAppliesMonotonically() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, MessageStatus.SENT, "2026-08-15T08:01:00Z");

        assertTrue(mr.applyRealtimeStatus(CHAT, message("m-1", MessageStatus.DELIVERED, "2026-08-16T09:00:00Z")));
        assertEquals(MessageStatus.DELIVERED, mr.getCachedMessages(CHAT).get(0).status);

        assertTrue(mr.applyRealtimeStatus(CHAT, message("m-1", MessageStatus.READ, "2026-08-16T10:00:00Z")));
        assertEquals(MessageStatus.READ, mr.getCachedMessages(CHAT).get(0).status);
    }

    @Test
    public void realtimeStatusRegressionIsNoOp() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, MessageStatus.READ, "2026-08-15T08:01:00Z");

        assertFalse(mr.applyRealtimeStatus(CHAT, message("m-1", MessageStatus.DELIVERED, "2026-08-16T09:00:00Z")));
        assertFalse(mr.applyRealtimeStatus(CHAT, message("m-1", MessageStatus.SENT, "2026-08-16T09:05:00Z")));
        assertEquals(MessageStatus.READ, mr.getCachedMessages(CHAT).get(0).status);
    }

    @Test
    public void realtimeStatusForUnknownMessageIsIgnored() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, MessageStatus.SENT, "2026-08-15T08:01:00Z");

        assertFalse(mr.applyRealtimeStatus(CHAT, message("m-999", MessageStatus.READ, "2026-08-16T10:00:00Z")));
        assertEquals(1, mr.getCachedMessages(CHAT).size());
        assertEquals(MessageStatus.SENT, mr.getCachedMessages(CHAT).get(0).status);
    }

    // ---- mark_message_status RPC ----

    @Test
    public void markMessageStatusCallsRpcAndAppliesLocally() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, MessageStatus.SENT, "2026-08-15T08:01:00Z");

        t.responses.add(t.json(200, "\"m-1\""));
        String confirmed = mr.markMessageStatus(CHAT, "m-1", MessageStatus.READ);
        assertEquals("m-1", confirmed);
        assertEquals(MessageStatus.READ, mr.getCachedMessages(CHAT).get(0).status);

        List<ApiRequest> rpcs = requestsFor(t.requests, "/rest/v1/rpc/mark_message_status");
        assertEquals(1, rpcs.size());
        assertTrue(rpcs.get(0).jsonBody.contains("\"p_message_id\":\"m-1\""));
        assertTrue(rpcs.get(0).jsonBody.contains("\"p_status\":\"read\""));
    }

    @Test
    public void markMessageStatusUnauthorizedLeavesCacheUnchanged() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, MessageStatus.SENT, "2026-08-15T08:01:00Z");

        // Author marking own message / non-member: PostgREST 403 → typed error.
        t.responses.add(t.json(403, ""));
        try {
            mr.markMessageStatus(CHAT, "m-1", MessageStatus.READ);
            fail("expected CreangerApiException for unauthorized status mark");
        } catch (CreangerApiException e) {
            assertEquals(403, e.statusCode);
        }
        assertEquals(MessageStatus.SENT, mr.getCachedMessages(CHAT).get(0).status);
    }

    // ---- reconnect recovery via get_message_statuses_since ----

    @Test
    public void recoverMessageStatusesAppliesAndResumesFromWatermark() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, MessageStatus.SENT, "2026-08-15T08:01:00Z");

        t.responses.add(t.json(200, rowsJson(statusRow("m-1", "read", "2026-08-16T12:00:00Z"))));
        List<MessageStatusUpdate> updates = mr.recoverMessageStatuses(CHAT, null, 100);
        assertEquals(1, updates.size());
        assertEquals("m-1", updates.get(0).messageId);
        assertEquals(MessageStatus.READ, mr.getCachedMessages(CHAT).get(0).status);

        // Consecutive recovery resumes from the applied watermark.
        t.responses.add(t.json(200, "[]"));
        assertTrue(mr.recoverMessageStatuses(CHAT, null, 100).isEmpty());

        List<ApiRequest> rpcs = requestsFor(t.requests, "/rest/v1/rpc/get_message_statuses_since");
        assertEquals(2, rpcs.size());
        assertTrue("second recovery must resume from the watermark",
                rpcs.get(1).jsonBody.contains("\"p_after_updated_at\":\"2026-08-16T12:00:00Z\""));
    }

    @Test
    public void recoverMessageStatusesIgnoresRowsOutsideCachedWindow() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, MessageStatus.SENT, "2026-08-15T08:01:00Z");

        // Only m-2 advanced while offline; it is not in the loaded window, so the
        // recovery must not fabricate a row — it converges on the next refresh.
        t.responses.add(t.json(200, rowsJson(statusRow("m-2", "read", "2026-08-16T12:00:00Z"))));
        List<MessageStatusUpdate> updates = mr.recoverMessageStatuses(CHAT, null, 100);
        assertEquals(1, updates.size());
        List<CreangerMessage> cached = mr.getCachedMessages(CHAT);
        assertEquals(1, cached.size());
        assertEquals("m-1", cached.get(0).id);
        assertEquals(MessageStatus.SENT, cached.get(0).status);
    }

    @Test
    public void clearCachedMessagesResetsCacheAndWatermark() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, MessageStatus.SENT, "2026-08-15T08:01:00Z");

        t.responses.add(t.json(200, rowsJson(statusRow("m-1", "read", "2026-08-16T12:00:00Z"))));
        mr.recoverMessageStatuses(CHAT, null, 100);

        mr.clearCachedMessages();
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());

        t.responses.add(t.json(200, "[]"));
        mr.recoverMessageStatuses(CHAT, null, 100);
        List<ApiRequest> rpcs = requestsFor(t.requests, "/rest/v1/rpc/get_message_statuses_since");
        assertEquals(2, rpcs.size());
        assertTrue("fresh session must recover from the beginning",
                rpcs.get(1).jsonBody.contains("\"p_after_updated_at\":null"));
    }

    // ---- rank / mapping rules ----

    @Test
    public void statusRankIsMonotonic() {
        assertEquals(0, MessageStatus.rank(MessageStatus.PENDING));
        assertEquals(1, MessageStatus.rank(MessageStatus.SENT));
        assertEquals(2, MessageStatus.rank(MessageStatus.DELIVERED));
        assertEquals(3, MessageStatus.rank(MessageStatus.READ));
        assertEquals(0, MessageStatus.rank(MessageStatus.FAILED));
        assertEquals(0, MessageStatus.rank(null));
    }

    @Test
    public void mappingIsUnreadDrivesCheckTintOnlyForOutbound() {
        CreangerMessageMapping mapping = new CreangerMessageMapping(OWNER);
        // Outbound not-yet-read → grey msgOutCheck (unread stays true).
        assertTrue(mapping.isUnread(MessageStatus.SENT, true));
        assertTrue(mapping.isUnread(MessageStatus.DELIVERED, true));
        assertTrue(mapping.isUnread(MessageStatus.PENDING, true));
        // Outbound read → blue msgOutCheckRead.
        assertFalse(mapping.isUnread(MessageStatus.READ, true));
        // Inbound messages never show a check regardless of status.
        assertFalse(mapping.isUnread(MessageStatus.SENT, false));
        assertFalse(mapping.isUnread(MessageStatus.READ, false));
    }
}