package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.data.CreangerChatBridge;
import com.creanger.app.messenger.creanger.data.CreangerMessageUiModel;
import com.creanger.app.messenger.creanger.data.MessageRepository;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.model.AuthModels.DeviceInfo;
import com.creanger.app.messenger.creanger.model.AuthModels.Fingerprint;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment;
import com.creanger.app.messenger.creanger.model.MessageModels.MessagePage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Bridge tests: the {@link CreangerChatBridge} that drives the chat screen
 * over the authenticated data plane — newest-first display, {@code chat_seq}
 * pagination, optimistic send with {@code client_message_id} reconcile, retry
 * after failure, duplicate-send protection, account isolation, and typed error
 * surfacing. Runs against a scripted transport (raw PostgREST JSON), the same
 * way {@link MessageRepositoryTest} does.
 */
public class CreangerChatBridgeTest {

    private static final String OWNER = "uuid-1";

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

    private static CreangerAuthEngine authenticatedEngine(ScriptedTransport t, InMemoryStore store) {
        store.store(freshSession("acc-live", "ref", user(OWNER, "alice", "a@x.com")));
        return new CreangerAuthEngine(new SupabaseAuthClient(t), store);
    }

    private static CreangerAuthEngine engineWith(ScriptedTransport t, InMemoryStore store) {
        return new CreangerAuthEngine(new SupabaseAuthClient(t), store);
    }

    private static MessageRepository repo(ScriptedTransport t, CreangerAuthEngine engine) {
        return new MessageRepository(engine, new CreangerChatApiClient(t));
    }

    private static MessageRepository repoWithMedia(ScriptedTransport t, CreangerAuthEngine engine) {
        return new MessageRepository(engine, new CreangerChatApiClient(t),
                new com.creanger.app.messenger.creanger.api.CreangerMediaUploadClient(t));
    }

    private static CreangerChatBridge bridge(MessageRepository repository, TestListener listener) {
        return new CreangerChatBridge(repository, OWNER, listener);
    }

    private static final class TestListener implements CreangerChatBridge.Listener {
        final List<String> changedChats = new ArrayList<>();
        CreangerApiException apiError;
        Throwable ioError;
        boolean anyError;

        @Override
        public void onMessagesChanged(String chatId, List<CreangerMessageUiModel> messages) {
            changedChats.add(chatId);
        }

        @Override
        public void onError(String chatId, CreangerApiException apiError, Throwable ioError) {
            anyError = true;
            this.apiError = apiError;
            this.ioError = ioError;
        }
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

    // ---- loading + ordering ----

    @Test
    public void refreshLoadsNewestFirstAndNotifies() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, rowsJson(
                msgRow("m-2", 2, "second", "cm-2", "2026-08-15T08:02:00Z"),
                msgRow("m-1", 1, "first", "cm-1", "2026-08-15T08:01:00Z"))));
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repo(t, authenticatedEngine(t, store)), listener);

        MessagePage page = bridge.refreshMessages("chat-1", 30);

        assertFalse(page.hasMore);
        List<CreangerMessageUiModel> msgs = bridge.getMessages("chat-1");
        assertEquals(2, msgs.size());
        assertEquals("m-2", msgs.get(0).id); // newest first, chat_seq DESC
        assertEquals("m-1", msgs.get(1).id);
        assertTrue(msgs.get(0).out);
        assertEquals("second", msgs.get(0).content);
        assertEquals("chat-1", listener.changedChats.get(0));
        assertEquals(1, listener.changedChats.size());
    }

    @Test
    public void loadOlderAppendsOlderRowsWithCursor() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        // Newest page: probe limit+1 returns 3 rows for limit 2 => hasMore, cursor = oldest kept.
        t.responses.add(t.json(200, rowsJson(
                msgRow("m-4", 4, "d", "cm-4", "2026-08-15T08:04:00Z"),
                msgRow("m-3", 3, "c", "cm-3", "2026-08-15T08:03:00Z"),
                msgRow("m-2", 2, "b", "cm-2", "2026-08-15T08:02:00Z"))));
        // Older page: chat_seq.lt.3.
        t.responses.add(t.json(200, rowsJson(
                msgRow("m-2", 2, "b", "cm-2", "2026-08-15T08:02:00Z"),
                msgRow("m-1", 1, "a", "cm-1", "2026-08-15T08:01:00Z"))));
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repo(t, authenticatedEngine(t, store)), listener);

        MessagePage first = bridge.refreshMessages("chat-1", 2);
        assertTrue(first.hasMore);
        assertEquals(3L, first.nextOlderSeq.longValue());
        assertEquals(2, bridge.getMessages("chat-1").size());

        MessagePage older = bridge.loadOlderMessages("chat-1", first.nextOlderSeq, 2);
        assertEquals(2, older.messages.size());
        assertFalse(older.hasMore);
        // All four appended, still newest-first.
        List<CreangerMessageUiModel> msgs = bridge.getMessages("chat-1");
        assertEquals(4, msgs.size());
        assertEquals("m-4", msgs.get(0).id);
        assertEquals("m-3", msgs.get(1).id);
        assertEquals("m-2", msgs.get(2).id);
        assertEquals("m-1", msgs.get(3).id);
        assertEquals("lt.3", t.requests.get(1).query.get("chat_seq"));
        assertEquals(2, listener.changedChats.size());
    }

    // ---- optimistic send + reconcile ----

    @Test
    public void sendInsertsPendingThenConfirmsOnAck() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "\"server-msg-1\""));
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repo(t, authenticatedEngine(t, store)), listener);

        // Optimistic row is immediately visible, before the RPC resolves.
        bridge.insertPendingRow("chat-1", "client-abc", "hello");
        CreangerMessageUiModel pending = bridge.getMessages("chat-1").get(0);
        assertTrue(pending.isPending());
        assertTrue(pending.out);
        assertEquals("client-abc", pending.clientMessageId);
        assertEquals("hello", pending.content);

        // Confirm after the idempotent send returns the server id.
        String serverId = bridge.sendTextMessage("chat-1", "client-abc", "hello");
        assertEquals("server-msg-1", serverId);
        CreangerMessageUiModel confirmed = bridge.getMessages("chat-1").get(0);
        assertEquals("server-msg-1", confirmed.id);
        assertFalse(confirmed.isLocal);
        assertFalse(confirmed.isPending());
        assertEquals(1, bridge.getMessages("chat-1").size());
        // RPC carried the payload.
        assertEquals("/rest/v1/rpc/send_text_message", t.requests.get(0).path);
        assertTrue(t.requests.get(0).jsonBody.contains("\"p_client_message_id\":\"client-abc\""));
    }

    @Test
    public void sendFailureKeepsLocalRowAndSurfacesError() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repo(t, authenticatedEngine(t, store)), listener);

        bridge.insertPendingRow("chat-1", "client-abc", "hello");
        t.failAll = new IOException("no route to host");
        try {
            bridge.sendTextMessage("chat-1", "client-abc", "hello");
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("no route to host", e.getMessage());
        }

        // Pending row survives; listener observed the error.
        assertTrue(listener.anyError);
        assertTrue(listener.ioError instanceof IOException);
        assertEquals(1, bridge.getMessages("chat-1").size());
        assertTrue(bridge.getMessages("chat-1").get(0).isLocal);

        // Failure can be surfaced to the UI as FAILED (kept for retry).
        bridge.markFailed("chat-1", "client-abc");
        assertTrue(bridge.getMessages("chat-1").get(0).isFailed());
    }

    @Test
    public void retryReusesSameClientIdAndConfirms() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repo(t, authenticatedEngine(t, store)), listener);

        bridge.insertPendingRow("chat-1", "client-retry", "hello");
        t.failAll = new IOException("offline");
        try {
            bridge.sendTextMessage("chat-1", "client-retry", "hello");
            fail("expected first attempt to fail");
        } catch (IOException expected) {
            // drop
        }

        // Retry with the SAME client_message_id reuses the optimistic copy.
        t.failAll = null;
        t.responses.add(t.json(200, "\"server-msg-9\""));
        String serverId = bridge.sendTextMessage("chat-1", "client-retry", "hello");
        assertEquals("server-msg-9", serverId);

        List<CreangerMessageUiModel> msgs = bridge.getMessages("chat-1");
        assertEquals(1, msgs.size());
        assertEquals("server-msg-9", msgs.get(0).id);
        assertFalse(msgs.get(0).isPending());
        assertEquals("hello", msgs.get(0).content);
    }

    @Test
    public void duplicateClientIdDoesNotDuplicateRows() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "\"server-msg-1\""));
        t.responses.add(t.json(200, "\"server-msg-1\""));
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repo(t, authenticatedEngine(t, store)), listener);

        String first = bridge.sendTextMessage("chat-1", "client-dup", "hello");
        String second = bridge.sendTextMessage("chat-1", "client-dup", "hello");

        assertEquals(first, second);
        List<CreangerMessageUiModel> msgs = bridge.getMessages("chat-1");
        assertEquals(1, msgs.size());
        assertEquals("server-msg-1", msgs.get(0).id);
        assertEquals(2, t.requests.size()); // both reached the idempotent RPC
    }

    // ---- isolation + errors ----

    @Test
    public void clearWipesAllDisplayState() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "a", null, "2026-08-15T08:01:00Z"))));
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repo(t, authenticatedEngine(t, store)), listener);
        bridge.refreshMessages("chat-1", 30);
        assertEquals(1, bridge.getMessages("chat-1").size());
        bridge.insertPendingRow("chat-1", "client-x", "pending");

        bridge.clear();

        assertEquals(0, bridge.getMessages("chat-1").size());
        assertEquals(0, bridge.getMessages("chat-other").size());
    }

    @Test
    public void unauthorizedRefreshSurfacesTypedApiError() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(401, "{\"message\":\"JWT\"}"));
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repo(t, authenticatedEngine(t, store)), listener);

        try {
            bridge.refreshMessages("chat-1", 30);
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertTrue(e.is(com.creanger.app.messenger.creanger.model.ApiError.UNAUTHORIZED));
        }
    }

    // ---- account switch isolation ----

    @Test
    public void accountSwitchDoesNotLeakMessages() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        // Account A holds a chat's messages.
        store.store(freshSession("acc-A", "ref-A", user("uuid-A", "alice", "a@x.com")));
        t.responses.add(t.json(200, rowsJson(msgRow("m-A1", 1, "mine", null, "2026-08-15T08:01:00Z"))));
        CreangerAuthEngine engineA = engineWith(t, store);
        MessageRepository repoA = repo(t, engineA);
        TestListener listenerA = new TestListener();
        CreangerChatBridge bridgeA = new CreangerChatBridge(repoA, engineA.currentUser().id, listenerA);
        bridgeA.refreshMessages("chat-1", 30);
        assertEquals(1, bridgeA.getMessages("chat-1").size());

        // Account B logs in through the same store/engine.
        t.responses.add(t.json(200,
                "{\"access_token\":\"acc-B\",\"refresh_token\":\"ref-B\","
                        + "\"user\":{\"id\":\"uuid-B\",\"email\":\"b@x.com\",\"user_metadata\":{\"username\":\"bob\"}}}"));
        engineA.loginWithPassword("b@x.com", "123456");

        // Keyboard cache is keyed by owning user; B has no messages.
        CreangerChatBridge bridgeB = new CreangerChatBridge(repoA, engineA.currentUser().id, listenerA);
        assertEquals(0, bridgeB.getMessages("chat-1").size());
        assertTrue(bridgeB.isAuthenticated());
    }

    // ---- media messages (migration 029) ----

    private static MediaAttachment mediaAtt(String mediaId, String storageKey, String mime,
                                            int width, int height, Integer duration) {
        return new MediaAttachment(null, null, mediaId, 0, null, "cloudinary", storageKey,
                "https://pub.example/" + storageKey, null, mime, 12345, "abc123",
                width, height, duration, null, null, null, null, null, "2026-08-15T08:01:00Z");
    }

    /** FLAT messages row — RPC/realtime projections carry no attachment expand. */
    private static String mediaMsgRowFlat(String id, long chatSeq, String caption) {
        return "{\"id\":\"" + id + "\",\"chat_id\":\"chat-1\",\"sender_id\":\"uuid-1\",\"message_type\":\"video\","
                + "\"content\":\"" + caption + "\",\"status\":\"sent\",\"client_message_id\":null,"
                + "\"chat_seq\":" + chatSeq + ",\"created_at\":\"2026-08-15T08:07:00Z\","
                + "\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"2026-08-15T08:07:00Z\"}";
    }

    /** message_attachments row with media embedded (getAttachments wire shape). */
    private static String attachmentRow(String messageId) {
        return "{\"id\":\"att-1\",\"message_id\":\"" + messageId + "\",\"position\":0,\"caption\":null,"
                + "\"media\":{\"id\":\"media-1\",\"owner_id\":\"uuid-1\",\"storage_provider\":\"imagebb\","
                + "\"storage_key\":\"ib/x\",\"public_url\":null,\"delivery_url\":\"https://dl.example/x\","
                + "\"mime_type\":\"video/mp4\",\"size_bytes\":999,\"checksum\":null,\"width\":1920,"
                + "\"height\":1080,\"duration\":15000,\"thumbnail_media_id\":null,"
                + "\"thumbnail_url\":\"https://img.example/v_poster.jpg\","
                + "\"created_at\":\"2026-08-15T08:02:00Z\",\"deleted_at\":null}}";
    }

    @Test
    public void mediaPendingInsertShowsTypeAndAttachmentsBeforeSend() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repo(t, authenticatedEngine(t, store)), listener);

        bridge.insertPendingRow("chat-1", "client-media", "image", "nice shot",
                java.util.Collections.singletonList(mediaAtt("media-1", "k/img", "image/png", 100, 200, null)));

        CreangerMessageUiModel pending = bridge.getMessages("chat-1").get(0);
        assertTrue(pending.isPending());
        assertTrue(pending.out);
        assertEquals("image", pending.messageType);
        assertTrue(pending.isMedia());
        assertEquals("nice shot", pending.content);
        assertEquals(1, pending.attachments.size());
        assertEquals("k/img", pending.attachments.get(0).storageKey);
        assertEquals(Integer.valueOf(200), pending.attachments.get(0).height);
    }

    @Test
    public void sendMediaMessageConfirmsPreservingTypeAndAttachments() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "\"server-media-1\""));
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repo(t, authenticatedEngine(t, store)), listener);

        bridge.insertPendingRow("chat-1", "client-media", "image", "nice shot",
                java.util.Collections.singletonList(mediaAtt("media-1", "k/img", "image/png", 100, 200, null)));
        MediaAttachment sendAtt = new MediaAttachment(null, null, "media-1", 0, null, "cloudinary",
                "k/img", "https://pub.example/k/img", null, "image/png", 12345, "abc123",
                100, 200, null, null, null,
                "https://img.example/photo_thumb.jpg", null, null, "2026-08-15T08:01:00Z");
        String serverId = bridge.sendMediaMessage("chat-1", "client-media", "image", "nice shot",
                null, java.util.Collections.singletonList(sendAtt));

        assertEquals("server-media-1", serverId);
        assertEquals("/rest/v1/rpc/send_media_message", t.requests.get(0).path);
        assertTrue(t.requests.get(0).jsonBody.contains("\"p_message_type\":\"image\""));
        // The thumbnail_url column must ride on the send payload (Phase 5B
        // end-to-end: it is how the receiver learns the poster source).
        assertTrue("send payload must carry thumbnail_url for thumbnail-bearing attachments",
                t.requests.get(0).jsonBody.contains("\"thumbnail_url\":\"https://img.example/photo_thumb.jpg\""));
        List<CreangerMessageUiModel> msgs = bridge.getMessages("chat-1");
        assertEquals(1, msgs.size());
        CreangerMessageUiModel confirmed = msgs.get(0);
        assertEquals("server-media-1", confirmed.id);
        assertFalse(confirmed.isLocal);
        assertFalse(confirmed.isPending());
        assertEquals("image", confirmed.messageType);
        assertEquals("nice shot", confirmed.content);
        assertEquals(1, confirmed.attachments.size());
        assertEquals("k/img", confirmed.attachments.get(0).storageKey);
    }

    @Test
    public void sendMediaFailureKeepsMediaRowAndNotifies() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repo(t, authenticatedEngine(t, store)), listener);

        bridge.insertPendingRow("chat-1", "client-media", "voice", null,
                java.util.Collections.singletonList(mediaAtt("media-v", "k/v", "audio/ogg", 0, 0, 4000)));
        t.failAll = new IOException("offline");
        try {
            bridge.sendMediaMessage("chat-1", "client-media", "voice", null, null,
                    java.util.Collections.singletonList(mediaAtt("media-v", "k/v", "audio/ogg", 0, 0, 4000)));
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("offline", e.getMessage());
        }

        assertTrue(listener.anyError);
        CreangerMessageUiModel row = bridge.getMessages("chat-1").get(0);
        assertEquals("voice", row.messageType);
        assertEquals(1, row.attachments.size());
        assertEquals(Integer.valueOf(4000), row.attachments.get(0).duration);

        bridge.markFailed("chat-1", "client-media");
        CreangerMessageUiModel failed = bridge.getMessages("chat-1").get(0);
        assertTrue(failed.isFailed());
        assertEquals("voice", failed.messageType); // setStatus preserves projections
        assertEquals(1, failed.attachments.size());
    }

    @Test
    public void sendVideoMessageCarriesMetadataThroughRpcPayload() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        // 1) upload-video envelope: provider-neutral metadata incl. width/height/durationMs
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"vid/abc\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/video/upload/v1/vid/abc.mp4\","
                        + "\"mimeType\":\"video/mp4\",\"sizeBytes\":1048576,\"deliveryUrl\":null,"
                        + "\"thumbnailUrl\":\"https://res.cloudinary.com/x/video/upload/w_320/v1/vid/abc.jpg\","
                        + "\"previewUrl\":null,\"width\":1080,\"height\":1920,\"durationMs\":15000}}"));
        // 2) idempotent send_media_message RPC ack
        t.responses.add(t.json(200, "\"server-vid-1\""));
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repoWithMedia(t, authenticatedEngine(t, store)), listener);

        String serverId = bridge.sendVideoMessage("chat-1", "client-vid", "clip.mp4",
                new byte[]{1, 2, 3, 4, 5}, "video/mp4", "clip caption", null);

        assertEquals("server-vid-1", serverId);
        assertEquals("/v1/media/upload-video", t.requests.get(0).path);
        assertEquals("/rest/v1/rpc/send_media_message", t.requests.get(1).path);
        String payload = t.requests.get(1).jsonBody;
        // Nothing may be silently dropped at the RPC boundary.
        assertTrue("payload must carry width", payload.contains("\"width\":1080"));
        assertTrue("payload must carry height", payload.contains("\"height\":1920"));
        assertTrue("payload must carry duration (ms)", payload.contains("\"duration\":15000"));
        assertTrue("payload must carry mime_type", payload.contains("\"mime_type\":\"video/mp4\""));
        assertTrue("payload must carry size_bytes", payload.contains("\"size_bytes\":1048576"));
        assertTrue("payload must carry public_url",
                payload.contains("\"public_url\":\"https://res.cloudinary.com/x/video/upload/v1/vid/abc.mp4\""));
        assertTrue("payload must carry thumbnail_url",
                payload.contains("\"thumbnail_url\":\"https://res.cloudinary.com/x/video/upload/w_320/v1/vid/abc.jpg\""));

        // The confirmed row preserves the same metadata for the sender's render.
        CreangerMessageUiModel confirmed = bridge.getMessages("chat-1").get(0);
        assertEquals("server-vid-1", confirmed.id);
        assertFalse(confirmed.isPending());
        MediaAttachment a = confirmed.attachments.get(0);
        assertEquals(Integer.valueOf(1080), a.width);
        assertEquals(Integer.valueOf(1920), a.height);
        assertEquals(Integer.valueOf(15000), a.duration);
        assertEquals("https://res.cloudinary.com/x/video/upload/v1/vid/abc.mp4", a.publicUrl);
        assertEquals("https://res.cloudinary.com/x/video/upload/w_320/v1/vid/abc.jpg", a.thumbnailUrl);
    }

    @Test
    public void sendVoiceMessageCarriesVoiceTypeAndDurationThroughRpcPayload() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        // 1) upload-audio envelope: provider-neutral metadata incl. durationMs
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"voice/abc\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/video/upload/v1/voice/abc.m4a\","
                        + "\"mimeType\":\"audio/mp4\",\"sizeBytes\":2048,\"deliveryUrl\":null,"
                        + "\"thumbnailUrl\":null,\"previewUrl\":null,\"width\":0,\"height\":0,\"durationMs\":7100}}"));
        // 2) idempotent send_media_message RPC ack
        t.responses.add(t.json(200, "\"server-voice-1\""));
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repoWithMedia(t, authenticatedEngine(t, store)), listener);

        String serverId = bridge.sendVoiceMessage("chat-1", "client-voice", "voice.m4a",
                new byte[]{1, 2, 3, 4}, "audio/mp4", null, null);

        assertEquals("server-voice-1", serverId);
        assertEquals("/v1/media/upload-audio", t.requests.get(0).path);
        assertEquals("/rest/v1/rpc/send_media_message", t.requests.get(1).path);
        String payload = t.requests.get(1).jsonBody;
        assertTrue("payload must carry voice message type", payload.contains("\"p_message_type\":\"voice\""));
        assertTrue("payload must carry audio mime", payload.contains("\"mime_type\":\"audio/mp4\""));
        assertTrue("payload must carry duration (ms)", payload.contains("\"duration\":7100"));

        CreangerMessageUiModel confirmed = bridge.getMessages("chat-1").get(0);
        assertEquals("server-voice-1", confirmed.id);
        assertEquals("voice", confirmed.messageType);
        assertEquals("https://res.cloudinary.com/x/video/upload/v1/voice/abc.m4a",
                confirmed.attachments.get(0).publicUrl);
        assertEquals(Integer.valueOf(7100), confirmed.attachments.get(0).duration);
    }

    @Test
    public void sendDocumentMessageCarriesFileNameKeyAndMimeThroughRpcPayload() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        // 1) upload-document envelope: the sanitized display name survives as the
        //    Cloudinary public_id -> storageKey (adapter renders docs from it).
        t.responses.add(t.json(200,
                "{\"success\":true,\"data\":{\"storageProvider\":\"cloudinary\",\"storageKey\":\"invoice.pdf\","
                        + "\"publicUrl\":\"https://res.cloudinary.com/x/raw/upload/v1/invoice.pdf\","
                        + "\"mimeType\":\"application/pdf\",\"sizeBytes\":65536,\"deliveryUrl\":null}}"));
        // 2) idempotent send_media_message RPC ack
        t.responses.add(t.json(200, "\"server-doc-1\""));
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repoWithMedia(t, authenticatedEngine(t, store)), listener);

        String serverId = bridge.sendDocumentMessage("chat-1", "client-doc", "invoice.pdf",
                new byte[]{1, 2, 3}, "application/pdf", "contract", null);

        assertEquals("server-doc-1", serverId);
        assertEquals("/v1/media/upload-document", t.requests.get(0).path);
        assertEquals("/rest/v1/rpc/send_media_message", t.requests.get(1).path);
        String payload = t.requests.get(1).jsonBody;
        assertTrue("payload must carry document message type", payload.contains("\"p_message_type\":\"document\""));
        assertTrue("payload must carry storage key (display name)",
                payload.contains("\"storage_key\":\"invoice.pdf\""));
        assertTrue("payload must carry doc mime", payload.contains("\"mime_type\":\"application/pdf\""));
        assertTrue("payload must carry size_bytes", payload.contains("\"size_bytes\":65536"));
        assertTrue("payload must carry public_url",
                payload.contains("\"public_url\":\"https://res.cloudinary.com/x/raw/upload/v1/invoice.pdf\""));
        // Caption survives on the RPC payload.
        assertTrue("payload must carry caption", payload.contains("\"p_caption\":\"contract\""));

        CreangerMessageUiModel confirmed = bridge.getMessages("chat-1").get(0);
        assertEquals("server-doc-1", confirmed.id);
        assertEquals("document", confirmed.messageType);
        assertEquals("contract", confirmed.content);
        assertEquals("invoice.pdf", confirmed.attachments.get(0).storageKey);
        assertEquals("application/pdf", confirmed.attachments.get(0).mimeType);
    }

    @Test
    public void loadAttachmentsFillsFlatMediaRowsAndNotifies() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, rowsJson(mediaMsgRowFlat("m-vid", 7, "vid caption"))));
        t.responses.add(t.json(200, rowsJson(attachmentRow("m-vid"))));
        TestListener listener = new TestListener();
        CreangerChatBridge bridge = bridge(repo(t, authenticatedEngine(t, store)), listener);
        bridge.refreshMessages("chat-1", 30);
        assertTrue(bridge.getMessages("chat-1").get(0).attachments.isEmpty());
        int changedBefore = listener.changedChats.size();

        // Post-load snapshot (page load + Realtime reconnect path).
        List<String> ids = new ArrayList<>();
        ids.add("m-vid");
        bridge.loadAttachments("chat-1", ids);

        assertEquals("/rest/v1/message_attachments", t.requests.get(1).path);
        CreangerMessageUiModel filled = bridge.getMessages("chat-1").get(0);
        assertEquals("video", filled.messageType);
        assertEquals("vid caption", filled.content);
        assertEquals(1, filled.attachments.size());
        MediaAttachment a = filled.attachments.get(0);
        assertEquals("ib/x", a.storageKey);
        assertEquals("https://dl.example/x", a.deliveryUrl);
        // size_bytes and mime_type are echoed through projection + parse.
        assertEquals(999L, a.sizeBytes);
        assertEquals("video/mp4", a.mimeType);
        assertEquals(Integer.valueOf(1920), a.width);
        assertEquals(Integer.valueOf(1080), a.height);
        assertEquals(Integer.valueOf(15000), a.duration);
        // thumbnail_url must survive the projection + parse chain (Phase 5B).
        assertEquals("https://img.example/v_poster.jpg", a.thumbnailUrl);
        assertEquals(changedBefore + 1, listener.changedChats.size());
    }
}