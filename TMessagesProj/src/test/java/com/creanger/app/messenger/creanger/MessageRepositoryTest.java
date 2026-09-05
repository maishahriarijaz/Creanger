package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.auth.AuthState;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.data.MessageRepository;
import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.model.AuthModels.DeviceInfo;
import com.creanger.app.messenger.creanger.model.AuthModels.Fingerprint;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment;
import com.creanger.app.messenger.creanger.model.MessageModels.MessagePage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageType;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Message foundation tests: RLS-scoped message listing with deterministic
 * {@code chat_seq} pagination, get_messages_since forward sync, idempotent send
 * with client_message_id (first write wins), optimistic pending state, the
 * minimal per-account cache, logout / account-switch isolation, and
 * malformed/unauthorized/network failures.
 *
 * These run against a scripted transport returning raw PostgREST JSON arrays
 * and RPC scalar replies; the real network path (JWT Bearer → PostgREST) is
 * covered by the data-plane E2E.
 */
public class MessageRepositoryTest {

    private static final class ScriptedTransport implements CreangerHttpTransport {
        final List<TransportResponse> responses = new ArrayList<>();
        final List<ApiRequest> requests = new ArrayList<>();
        volatile IOException failAll = null;

        @Override
        public TransportResponse execute(ApiRequest request) throws IOException {
            requests.add(request);
            if (failAll != null) {
                throw failAll;
            }
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

    private static AuthSession freshSession(String access, String refresh, CreangerUser user) {
        return new AuthSession(access, refresh, user, System.currentTimeMillis());
    }

    private static CreangerUser user(String id, String username, String email) {
        return new CreangerUser(id, username, email, true, null, null, null);
    }

    private static DeviceInfo device() {
        return new DeviceInfo("android", "Pixel 7", new Fingerprint("android", "en_US", "UTC"));
    }

    private static MessageRepository repo(ScriptedTransport t, InMemoryStore store, CreangerAuthEngine engine) {
        return new MessageRepository(engine, new CreangerChatApiClient(t));
    }

    private static CreangerAuthEngine engineWith(ScriptedTransport t, InMemoryStore store) {
        return new CreangerAuthEngine(new SupabaseAuthClient(t), store);
    }

    private static CreangerAuthEngine authenticatedEngine(ScriptedTransport t, InMemoryStore store) {
        store.store(freshSession("acc-live", "ref", user("uuid-1", "alice", "a@x.com")));
        return engineWith(t, store);
    }

    /** Single message row in the /rest/v1/messages wire shape. */
    private static String msgRow(String id, long chatSeq, String content, String clientMsgId, String createdAt) {
        return "{\"id\":\"" + id + "\",\"chat_id\":\"chat-1\",\"sender_id\":\"uuid-1\",\"message_type\":\"text\","
                + "\"content\":\"" + content + "\",\"status\":\"sent\",\"client_message_id\":"
                + (clientMsgId == null ? "null" : "\"" + clientMsgId + "\"")
                + ",\"chat_seq\":" + chatSeq + ",\"created_at\":\"" + createdAt
                + "\",\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"" + createdAt + "\"}";
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

    // ---- message listing (RLS-scoped, newest first) ----

    @Test
    public void refreshMessagesListsNewestFirstAndCaches() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        // limit 2 -> probes with limit 3; 3 rows => hasMore true, keep 2.
        t.responses.add(t.json(200, rowsJson(
                msgRow("m-3", 3, "third", "cm-3", "2026-08-15T08:03:00Z"),
                msgRow("m-2", 2, "second", "cm-2", "2026-08-15T08:02:00Z"),
                msgRow("m-1", 1, "first", "cm-1", "2026-08-15T08:01:00Z"))));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));

        MessagePage page = mr.refreshMessages("chat-1", 2);

        assertEquals(2, page.messages.size());
        assertEquals("m-3", page.messages.get(0).id); // newest first
        assertEquals("m-2", page.messages.get(1).id);
        assertEquals("third", page.messages.get(0).content);
        assertEquals(MessageType.TEXT, page.messages.get(0).messageType);
        assertEquals(MessageStatus.SENT, page.messages.get(0).status);
        assertEquals(2L, page.nextOlderSeq.longValue()); // oldest kept (m-2) = cursor
        assertTrue(page.hasMore);
        assertEquals(1, t.requests.size());
        assertEquals("/rest/v1/messages", t.requests.get(0).path);
        assertEquals("3", t.requests.get(0).query.get("limit")); // limit+1 probe
        assertEquals("chat_seq.desc", t.requests.get(0).query.get("order"));
        assertEquals("eq.chat-1", t.requests.get(0).query.get("chat_id"));
        // Cache serves without network.
        assertEquals(2, mr.getCachedMessages("chat-1").size());
        assertTrue(mr.getCachedMessages("chat-1").get(0).id.equals("m-3"));
    }

    @Test
    public void refreshMessagesEmptyChatReturnsEmptyPage() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "[]"));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));

        MessagePage page = mr.refreshMessages("chat-1", 30);

        assertTrue(page.messages.isEmpty());
        assertNull(page.nextOlderSeq);
        assertFalse(page.hasMore);
        assertTrue(mr.getCachedMessages("chat-1").isEmpty());
    }

    // ---- pagination cursor ----

    @Test
    public void loadOlderMessagesUsesChatSeqCursor() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        // First page (newest 2 of 4): probe limit+1=3 returns m-4,m-3,m-2.
        t.responses.add(t.json(200, rowsJson(
                msgRow("m-4", 4, "d", "cm-4", "2026-08-15T08:04:00Z"),
                msgRow("m-3", 3, "c", "cm-3", "2026-08-15T08:03:00Z"),
                msgRow("m-2", 2, "b", "cm-2", "2026-08-15T08:02:00Z"))));
        // Older page: chat_seq < 3 (oldest kept = m-3).
        t.responses.add(t.json(200, rowsJson(
                msgRow("m-2", 2, "b", "cm-2", "2026-08-15T08:02:00Z"),
                msgRow("m-1", 1, "a", "cm-1", "2026-08-15T08:01:00Z"))));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));

        MessagePage first = mr.refreshMessages("chat-1", 2);
        assertEquals(3L, first.nextOlderSeq.longValue());
        assertEquals("m-4", first.messages.get(0).id);

        MessagePage older = mr.loadOlderMessages("chat-1", first.nextOlderSeq, 2);
        assertEquals(2, older.messages.size());
        assertEquals("m-2", older.messages.get(0).id);
        assertEquals("m-1", older.messages.get(1).id);
        // The chat_seq.lt cursor was applied.
        assertEquals("lt.3", t.requests.get(1).query.get("chat_seq"));
        // Cache now holds all four, newest-first.
        List<CreangerMessage> cached = mr.getCachedMessages("chat-1");
        assertEquals("m-4", cached.get(0).id);
        assertEquals("m-1", cached.get(3).id);
    }

    @Test
    public void loadOlderMessagesAtEndOfHistoryHasMoreFalse() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "a", "cm-1", "2026-08-15T08:01:00Z"))));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));

        MessagePage page = mr.loadOlderMessages("chat-1", 1, 30);

        assertEquals(1, page.messages.size());
        assertEquals("m-1", page.messages.get(0).id);
        assertNull(page.nextOlderSeq);
        assertFalse(page.hasMore);
    }

    // ---- get_messages_since (forward sync RPC) ----

    @Test
    public void getMessagesSinceParsesRpcRowsAscending() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, rowsJson(
                "{\"message_id\":\"m-1\",\"chat_seq\":1,\"sender_id\":\"uuid-1\",\"message_type\":\"text\","
                        + "\"content\":\"one\",\"created_at\":\"2026-08-15T08:01:00Z\",\"edited_at\":null,"
                        + "\"deleted_at\":null,\"updated_at\":\"2026-08-15T08:01:00Z\"}",
                "{\"message_id\":\"m-2\",\"chat_seq\":2,\"sender_id\":\"uuid-1\",\"message_type\":\"text\","
                        + "\"content\":\"two\",\"created_at\":\"2026-08-15T08:02:00Z\",\"edited_at\":null,"
                        + "\"deleted_at\":null,\"updated_at\":\"2026-08-15T08:02:00Z\"}")));
        CreangerChatApiClient api = new CreangerChatApiClient(t);
        String access = "acc-live";

        List<CreangerMessage> msgs = api.getMessagesSince(access, "chat-1", 0, 50);

        assertEquals(2, msgs.size());
        CreangerMessage m1 = msgs.get(0);
        assertEquals("m-1", m1.id); // RPC maps message_id to id
        assertEquals(1L, m1.chatSeq.longValue());
        assertEquals("one", m1.content);
        assertEquals(MessageType.TEXT, m1.messageType);
        assertEquals("/rest/v1/rpc/get_messages_since", t.requests.get(0).path);
        assertEquals("POST", t.requests.get(0).method);
        assertTrue(t.requests.get(0).jsonBody.contains("\"p_chat_id\":\"chat-1\""));
    }

    // ---- send text with optimistic local state ----

    @Test
    public void sendTextMessageEntersPendingThenConfirms() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "\"server-msg-1\""));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));

        // With a synchronous transport the pending copy is entered and confirmed
        // within the one call; the failure path (pending survives for retry) is
        // covered separately. Here we verify the confirmed end state and that
        // the idempotent RPC carried the client payload.
        String id = mr.sendTextMessage("chat-1", "client-abc", "hello");

        assertEquals("server-msg-1", id);
        // The RPC body carried the payload.
        assertEquals("/rest/v1/rpc/send_text_message", t.requests.get(0).path);
        assertTrue(t.requests.get(0).jsonBody.contains("\"p_content\":\"hello\""));
        assertTrue(t.requests.get(0).jsonBody.contains("\"p_client_message_id\":\"client-abc\""));
        // After ack, the confirmed copy holds the server id, sent status, original content.
        List<CreangerMessage> cached = mr.getCachedMessages("chat-1");
        assertEquals(1, cached.size());
        CreangerMessage confirmed = cached.get(0);
        assertEquals("server-msg-1", confirmed.id);
        assertFalse(confirmed.isLocal);
        assertEquals(MessageStatus.SENT, confirmed.status);
        assertEquals("hello", confirmed.content);
        assertEquals("client-abc", confirmed.clientMessageId);
    }

    @Test
    public void sendTextMessageIdempotentRetryReturnsSameId() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "\"server-msg-1\""));
        t.responses.add(t.json(200, "\"server-msg-1\""));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));

        String first = mr.sendTextMessage("chat-1", "client-abc", "hello");

        // Duplicate retry (same client_message_id + same content).
        String second = mr.sendTextMessage("chat-1", "client-abc", "hello");

        assertEquals(first, second);
        // No duplicate rows in the cache.
        List<CreangerMessage> cached = mr.getCachedMessages("chat-1");
        assertEquals(1, cached.size());
        assertEquals("server-msg-1", cached.get(0).id);
        assertEquals(2, t.requests.size()); // both attempts hit the idempotent RPC
    }

    @Test
    public void sendTextMessageSameClientIdDifferentContentKeepsFirstWrite() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "\"server-msg-1\""));
        t.responses.add(t.json(200, "\"server-msg-1\""));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));

        mr.sendTextMessage("chat-1", "client-abc", "original");
        // A retry with a DIFFERENT content under the same client id: first write
        // wins (migration 022), the stored message keeps the original content.
        String id = mr.sendTextMessage("chat-1", "client-abc", "different");

        assertEquals("server-msg-1", id);
        List<CreangerMessage> cached = mr.getCachedMessages("chat-1");
        assertEquals("one message row", 1, cached.size());
        assertEquals("original", cached.get(0).content);
        assertEquals("client-abc", cached.get(0).clientMessageId);
    }

    // ---- deterministic ordering ----

    @Test
    public void cacheOrdersByChatSeqDescWithPendingOnTop() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, rowsJson(
                msgRow("m-2", 2, "second", null, "2026-08-15T08:02:00Z"),
                msgRow("m-1", 1, "first", null, "2026-08-15T08:01:00Z"))));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));
        mr.refreshMessages("chat-1", 2);

        // Send a new message; the RPC fails so the optimistic pending copy stays
        // resident. It must sort to the TOP, above the confirmed server rows.
        t.failAll = new IOException("offline");
        try {
            mr.sendTextMessage("chat-1", "cm-new", "pending one");
            fail("expected IOException so the pending copy survives for the ordering check");
        } catch (IOException expected) {
            // Expected: network unavailable, optimistic copy stays local.
        }

        List<CreangerMessage> cached = mr.getCachedMessages("chat-1");
        assertEquals(3, cached.size());
        assertEquals("pending one", cached.get(0).content);
        assertTrue(cached.get(0).isLocal);
        assertEquals("m-2", cached.get(1).id);
        assertEquals("m-1", cached.get(2).id);
    }

    // ---- unauthorized / private-chat access ----

    @Test
    public void unauthorizedMessageFetchSurfacesTypedError() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(401, "{\"message\":\"JWT\"}"));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));

        try {
            mr.refreshMessages("chat-private", 30);
            fail("expected CreangerApiException for 401");
        } catch (CreangerApiException e) {
            assertTrue(e.is(ApiError.UNAUTHORIZED));
        }
    }

    @Test
    public void privateChatSendsNothingForNonMember() throws Exception {
        // RLS: a non-member asking for a private chat's messages gets [] (200),
        // never an error and never rows.
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "[]"));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));

        MessagePage page = mr.refreshMessages("chat-private-b", 30);

        assertTrue(page.messages.isEmpty());
        assertNull(page.nextOlderSeq);
        assertFalse(page.hasMore);
    }

    // ---- malformed / network failures ----

    @Test
    public void malformedMessageBodyThrowsTypedError() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "not-json-at-all"));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));

        try {
            mr.refreshMessages("chat-1", 30);
            fail("expected CreangerApiException for malformed body");
        } catch (CreangerApiException e) {
            assertTrue(e.is(ApiError.INTERNAL_ERROR));
        }
    }

    @Test
    public void networkFailureThrowsIoAndPendingRemainsForRetry() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));

        t.failAll = new IOException("no route to host");
        try {
            mr.sendTextMessage("chat-1", "client-abc", "hello");
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("no route to host", e.getMessage());
        }
        // The optimistic pending copy survives so the send can be retried.
        List<CreangerMessage> cached = mr.getCachedMessages("chat-1");
        assertEquals(1, cached.size());
        assertTrue(cached.get(0).isLocal);
        assertEquals(MessageStatus.PENDING, cached.get(0).status);
        assertEquals("client-abc", cached.get(0).clientMessageId);

        // Retry with the same client_message_id reuses the pending copy.
        t.failAll = null;
        t.responses.add(t.json(200, "\"server-msg-1\""));
        assertEquals("server-msg-1", mr.sendTextMessage("chat-1", "client-abc", "hello"));
        assertEquals(1, mr.getCachedMessages("chat-1").size());
        assertFalse(mr.getCachedMessages("chat-1").get(0).isLocal);
    }

    // ---- logout / account switch isolation ----

    @Test
    public void clearWipesMessageCacheAndSession() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "a", null, "2026-08-15T08:01:00Z"))));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));
        mr.refreshMessages("chat-1", 30);
        assertEquals(AuthState.AUTHENTICATED, mr.getState());

        mr.clear();

        assertEquals(AuthState.UNAUTHENTICATED, mr.getState());
        assertFalse(mr.isAuthenticated());
        assertTrue(mr.getCachedMessages("chat-1").isEmpty());
        assertNull(store.stored);
    }

    @Test
    public void accountSwitchDoesNotLeakPreviousMessages() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        // Account A fetches a chat's messages. The engine must be created AFTER
        // the session is stored (it reads the store at construction time).
        store.store(freshSession("acc-A", "ref-A", user("uuid-A", "alice", "a@x.com")));
        t.responses.add(t.json(200, rowsJson(msgRow("m-A1", 1, "mine", null, "2026-08-15T08:01:00Z"))));
        CreangerAuthEngine engine = engineWith(t, store);
        MessageRepository mr = repo(t, store, engine);
        assertEquals(1, mr.refreshMessages("chat-1", 30).messages.size());

        // Account B logs in (login/verify response carries user B).
        t.responses.add(t.json(200,
                "{\"access_token\":\"acc-B\",\"refresh_token\":\"ref-B\","
                        + "\"user\":{\"id\":\"uuid-B\",\"email\":\"b@x.com\",\"user_metadata\":{\"username\":\"bob\"}}}"));
        engine.loginWithPassword("b@x.com", "123456");

        // Previously-cached messages must not leak to account B.
        assertTrue(mr.getCachedMessages("chat-1").isEmpty());
        assertEquals("uuid-B", engine.currentUser().id);
    }

    // ---- media messages ----

    private static MediaAttachment mediaAtt(String mediaId, String storageKey, String mime,
                                            int width, int height, Integer duration) {
        return new MediaAttachment(null, null, mediaId, 0, null, "cloudinary", storageKey,
                "https://pub.example/" + storageKey, null, mime, 12345, "abc123",
                width, height, duration, null, null, null, null, null, "2026-08-15T08:01:00Z");
    }

    /** A messages row whose embedded message_attachments expand carries one media row. */
    private static String mediaMsgRow(String id, long chatSeq, String caption, String clientMsgId, String createdAt) {
        return "{\"id\":\"" + id + "\",\"chat_id\":\"chat-1\",\"sender_id\":\"uuid-1\",\"message_type\":\"image\","
                + "\"content\":\"" + caption + "\",\"status\":\"sent\",\"client_message_id\":"
                + (clientMsgId == null ? "null" : "\"" + clientMsgId + "\"")
                + ",\"chat_seq\":" + chatSeq + ",\"created_at\":\"" + createdAt
                + "\",\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"" + createdAt + "\","
                + "\"message_attachments\":[{\"id\":\"att-1\",\"position\":0,\"caption\":null,"
                + "\"media\":{\"id\":\"media-1\",\"owner_id\":\"uuid-1\",\"storage_provider\":\"cloudinary\","
                + "\"storage_key\":\"img/x\",\"public_url\":\"https://pub.example/img/x\",\"delivery_url\":null,"
                + "\"mime_type\":\"image/png\",\"size_bytes\":12345,\"checksum\":\"abc123\",\"width\":800,"
                + "\"height\":600,\"duration\":null,\"thumbnail_media_id\":null,"
                + "\"created_at\":\"2026-08-15T08:01:00Z\",\"deleted_at\":null}}]}";
    }

    /** A flat RPC/table row WITHOUT any expand — the payload realtime/RPC projections deliver. */
    private static String mediaMsgRowFlat(String id, long chatSeq, String caption, String createdAt) {
        return "{\"id\":\"" + id + "\",\"chat_id\":\"chat-1\",\"sender_id\":\"uuid-1\",\"message_type\":\"video\","
                + "\"content\":\"" + caption + "\",\"status\":\"sent\",\"client_message_id\":null,"
                + "\"chat_seq\":" + chatSeq + ",\"created_at\":\"" + createdAt
                + "\",\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"" + createdAt + "\"}";
    }

    /** One message_attachments row with its media row embedded (getAttachments wire shape). */
    private static String attachmentRow(String messageId) {
        return "{\"id\":\"att-1\",\"message_id\":\"" + messageId + "\",\"position\":0,\"caption\":null,"
                + "\"media\":{\"id\":\"media-1\",\"owner_id\":\"uuid-1\",\"storage_provider\":\"imagebb\","
                + "\"storage_key\":\"ib/x\",\"public_url\":null,\"delivery_url\":\"https://dl.example/x\","
                + "\"mime_type\":\"video/mp4\",\"size_bytes\":999,\"checksum\":null,\"width\":1920,"
                + "\"height\":1080,\"duration\":15000,\"thumbnail_media_id\":null,"
                + "\"created_at\":\"2026-08-15T08:02:00Z\",\"deleted_at\":null}}";
    }

    @Test
    public void refreshMessagesParsesEmbeddedAttachments() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, rowsJson(
                mediaMsgRow("m-media", 5, "caption here", "cm-media", "2026-08-15T08:05:00Z"))));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));

        mr.refreshMessages("chat-1", 30);

        CreangerMessage cached = mr.getCachedMessages("chat-1").get(0);
        assertEquals(MessageType.IMAGE, cached.messageType);
        assertEquals("caption here", cached.content);
        assertTrue(cached.isMedia());
        assertEquals(1, cached.attachments.size());
        MediaAttachment a = cached.attachments.get(0);
        assertEquals("cloudinary", a.storageProvider);
        assertEquals("img/x", a.storageKey);
        assertEquals("image/png", a.mimeType);
        assertEquals(Integer.valueOf(800), a.width);
        assertEquals(Integer.valueOf(600), a.height);
        assertEquals("media-1", a.mediaId);
    }

    @Test
    public void sendMediaMessageConfirmsPreservingTypeAndAttachments() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "\"server-media-1\""));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));
        List<MediaAttachment> atts = java.util.Collections.singletonList(
                mediaAtt("media-new", "k/img-1", "image/png", 100, 200, null));

        String id = mr.sendMediaMessage("chat-1", "cm-media-1", MessageType.IMAGE, "nice shot",
                null, atts);

        assertEquals("server-media-1", id);
        assertEquals("/rest/v1/rpc/send_media_message", t.requests.get(0).path);
        assertTrue(t.requests.get(0).jsonBody.contains("\"p_message_type\":\"image\""));
        assertTrue(t.requests.get(0).jsonBody.contains("\"p_caption\":\"nice shot\""));
        assertTrue(t.requests.get(0).jsonBody.contains("\"storage_key\":\"k/img-1\""));
        assertTrue(t.requests.get(0).jsonBody.contains("\"p_client_message_id\":\"cm-media-1\""));
        CreangerMessage confirmed = mr.getCachedMessages("chat-1").get(0);
        assertEquals("server-media-1", confirmed.id);
        assertFalse(confirmed.isLocal);
        assertEquals(MessageType.IMAGE, confirmed.messageType);
        assertEquals(MessageStatus.SENT, confirmed.status);
        assertEquals("nice shot", confirmed.content);
        assertEquals(1, confirmed.attachments.size());
        assertEquals("k/img-1", confirmed.attachments.get(0).storageKey);
        assertEquals(Integer.valueOf(200), confirmed.attachments.get(0).height);
    }

    @Test
    public void sendMediaMessageIdempotentRetryKeepsFirstWrite() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "\"server-media-1\""));
        t.responses.add(t.json(200, "\"server-media-1\""));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));
        List<MediaAttachment> atts = java.util.Collections.singletonList(
                mediaAtt("media-new", "k/img-1", "image/png", 100, 200, null));
        List<MediaAttachment> other = java.util.Collections.singletonList(
                mediaAtt("media-other", "k/img-X", "image/jpeg", 10, 10, null));

        String first = mr.sendMediaMessage("chat-1", "cm-media-1", MessageType.IMAGE, "first",
                null, atts);
        // Retry with same client id but a DIFFERENT caption + attachment: the
        // first write wins (migration 022 + 029 semantics).
        String second = mr.sendMediaMessage("chat-1", "cm-media-1", MessageType.VIDEO, "second",
                null, other);

        assertEquals(first, second);
        List<CreangerMessage> cached = mr.getCachedMessages("chat-1");
        assertEquals(1, cached.size());
        assertEquals(MessageType.IMAGE, cached.get(0).messageType); // first write's type
        assertEquals("first", cached.get(0).content);
        assertEquals("k/img-1", cached.get(0).attachments.get(0).storageKey);
        assertEquals(2, t.requests.size());
    }

    @Test
    public void sendMediaMessageFailureKeepsPendingCopyWithAttachments() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));
        t.failAll = new IOException("offline-media");

        try {
            mr.sendMediaMessage("chat-1", "cm-media-1", MessageType.VOICE, null, null,
                    java.util.Collections.singletonList(mediaAtt("m1", "k/v1", "audio/ogg", 0, 0, 4000)));
            fail("expected IOException");
        } catch (IOException expected) {
            // optimistic copy survives for retry
        }

        List<CreangerMessage> cached = mr.getCachedMessages("chat-1");
        assertEquals(1, cached.size());
        assertTrue(cached.get(0).isLocal);
        assertEquals(MessageStatus.PENDING, cached.get(0).status);
        assertEquals(MessageType.VOICE, cached.get(0).messageType);
        assertEquals(1, cached.get(0).attachments.size());
        assertEquals(Integer.valueOf(4000), cached.get(0).attachments.get(0).duration);
    }

    @Test
    public void recoverAttachmentsFillsRpcRowsAndPrunesStale() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        // Page load delivers a FLAT video row (no expand — RPC/realtime path).
        t.responses.add(t.json(200, rowsJson(mediaMsgRowFlat("m-vid", 7, "vid caption",
                "2026-08-15T08:07:00Z"))));
        t.responses.add(t.json(200, rowsJson(attachmentRow("m-vid"))));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));
        mr.refreshMessages("chat-1", 30);
        assertTrue(mr.getCachedMessages("chat-1").get(0).attachments.isEmpty());

        // Reconnect/page-load snapshot fills the media metadata.
        List<String> ids = new ArrayList<>();
        ids.add("m-vid");
        mr.recoverAttachments("chat-1", ids);

        assertEquals("/rest/v1/message_attachments", t.requests.get(1).path);
        assertEquals("in.(m-vid)", t.requests.get(1).query.get("message_id"));
        CreangerMessage filled = mr.getCachedMessages("chat-1").get(0);
        assertEquals(MessageType.VIDEO, filled.messageType);
        assertEquals("vid caption", filled.content);
        assertEquals(1, filled.attachments.size());
        MediaAttachment a = filled.attachments.get(0);
        assertEquals("m-vid", a.messageId);
        assertEquals("ib/x", a.storageKey);
        assertEquals("video/mp4", a.mimeType);
        assertEquals(Integer.valueOf(1920), a.width);
        assertEquals(Integer.valueOf(15000), a.duration);

        // A snapshot with no rows prunes stale metadata (attachment was removed).
        mr.attachMedia("chat-1", new ArrayList<>());
        assertTrue(mr.getCachedMessages("chat-1").get(0).attachments.isEmpty());
    }

    @Test
    public void realtimeEchoOfOwnImageSendKeepsHeldAttachments() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        // The confirmed image send row is cached WITH attachment metadata
        // (from the send_media_message confirm path).
        t.responses.add(t.json(200, rowsJson(
                mediaMsgRow("m-img", 9, "cap", "cm-img", "2026-08-15T08:09:00Z"))));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));
        mr.refreshMessages("chat-1", 30);
        assertEquals(1, mr.getCachedMessages("chat-1").get(0).attachments.size());

        // The realtime echo of the same row carries NO attachment payload
        // (the 020 publication excludes media/message_attachments): the held
        // metadata must survive so the just-sent bubble never flashes blank.
        CreangerMessage echo = new CreangerMessage(
                "m-img", "chat-1", "uuid-1", MessageType.IMAGE, "cap", MessageStatus.SENT,
                "cm-img", 9L, "2026-08-15T08:09:00Z", null, null, "2026-08-15T08:09:00Z",
                null, false);
        mr.ingestRealtime("chat-1", echo);

        CreangerMessage after = mr.getCachedMessages("chat-1").get(0);
        assertEquals(1, after.attachments.size());
        assertEquals("https://pub.example/img/x", after.attachments.get(0).publicUrl);
    }

    @Test
    public void clearWipesMediaMessages() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, rowsJson(mediaMsgRow("m-img", 3, "cap", null, "2026-08-15T08:03:00Z"))));
        MessageRepository mr = repo(t, store, authenticatedEngine(t, store));
        mr.refreshMessages("chat-1", 30);
        assertEquals(1, mr.getCachedMessages("chat-1").get(0).attachments.size());

        mr.clear();

        assertTrue(mr.getCachedMessages("chat-1").isEmpty());
    }
}