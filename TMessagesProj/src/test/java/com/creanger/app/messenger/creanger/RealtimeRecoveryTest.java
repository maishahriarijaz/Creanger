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
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageType;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Realtime ↔ repository/bridge integration: new rows ingested over Realtime
 * render through the existing display pipeline (dedup by id and
 * client_message_id against REST pages and send confirms), and the
 * {@code get_messages_since} cursor sync recovers messages missed while
 * Realtime was down. Also covers account/logout isolation and typed
 * authorization failures.
 */
public class RealtimeRecoveryTest {

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

    private static CreangerUser user(String id) {
        return new CreangerUser(id, "u", "u@x.com", true, null, null, null);
    }

    private static CreangerAuthEngine engine(ScriptedTransport t, InMemoryStore store, String ownerId) {
        store.store(new AuthSession("acc-live", "ref", user(ownerId), System.currentTimeMillis()));
        return new CreangerAuthEngine(new SupabaseAuthClient(t), store);
    }

    private static MessageRepository repo(ScriptedTransport t, CreangerAuthEngine engine) {
        return new MessageRepository(engine, new CreangerChatApiClient(t));
    }

    private static CreangerChatBridge bridge(MessageRepository repository) {
        return new CreangerChatBridge(repository, OWNER, null);
    }

    private static final class ListenerCapture implements CreangerChatBridge.Listener {
        int changedCount;
        boolean errored;

        @Override
        public void onMessagesChanged(String chatId, List<CreangerMessageUiModel> messages) {
            changedCount++;
        }

        @Override
        public void onError(String chatId, CreangerApiException apiError, Throwable ioError) {
            errored = true;
        }
    }

    private static CreangerMessage row(String id, String chatId, long seq, String sender,
                                       String clientMsgId, String createdAt) {
        return new CreangerMessage(id, chatId, sender, MessageType.TEXT, "msg-" + seq,
                MessageStatus.SENT, clientMsgId, seq, createdAt, null, null, createdAt, false);
    }

    private static String refreshRow(String id, long seq, String content) {
        return "{\"id\":\"" + id + "\",\"chat_id\":\"chat-1\",\"sender_id\":\"uuid-other\",\"message_type\":\"text\","
                + "\"content\":\"" + content + "\",\"status\":\"sent\",\"client_message_id\":null,"
                + "\"chat_seq\":" + seq + ",\"created_at\":\"2026-08-16T10:00:00Z\","
                + "\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"2026-08-16T10:00:00Z\"}";
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

    // ---- realtime ingest + render ----

    @Test
    public void realtimeInsertNewRowAppearsAndNotifiesOnce() {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        ListenerCapture listener = new ListenerCapture();
        CreangerChatBridge bridge = new CreangerChatBridge(
                repo(t, engine(t, store, OWNER)), OWNER, listener);

        boolean inserted = bridge.applyRealtime("chat-1", row("m-6", "chat-1", 6, "uuid-other", null, "t"));
        assertTrue(inserted);
        assertEquals(1, listener.changedCount);

        List<CreangerMessageUiModel> msgs = bridge.getMessages("chat-1");
        assertEquals(1, msgs.size());
        assertEquals("m-6", msgs.get(0).id);
        assertFalse(msgs.get(0).out); // from another user → incoming
        assertEquals("msg-6", msgs.get(0).content);
    }

    // ---- cross-path dedup: realtime vs REST ----

    @Test
    public void realtimeRowThenRefreshListsNoDuplicate() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        MessageRepository repository = repo(t, engine(t, store, OWNER));
        CreangerChatBridge bridge = bridge(repository);

        assertTrue(bridge.applyRealtime("chat-1", row("m-6", "chat-1", 6, "uuid-other", null, "t")));
        // Realtime delivered the row first; a later refresh re-lists the same row.
        t.responses.add(t.json(200, rowsJson(
                refreshRow("m-7", 7, "newer"),
                refreshRow("m-6", 6, "msg-6"))));
        bridge.refreshMessages("chat-1", 30);

        List<CreangerMessageUiModel> msgs = bridge.getMessages("chat-1");
        assertEquals(2, msgs.size());
        assertEquals("m-7", msgs.get(0).id);
        assertEquals("m-6", msgs.get(1).id); // no duplicate of the realtime row
    }

    @Test
    public void refreshRowThenRealtimeIsDuplicateAndNotRendered() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        MessageRepository repository = repo(t, engine(t, store, OWNER));
        CreangerChatBridge bridge = bridge(repository);

        t.responses.add(t.json(200, rowsJson(refreshRow("m-6", 6, "msg-6"))));
        bridge.refreshMessages("chat-1", 30);
        assertEquals(1, bridge.getMessages("chat-1").size());

        // The same row arrives over Realtime afterwards.
        boolean inserted = bridge.applyRealtime("chat-1", row("m-6", "chat-1", 6, "uuid-other", null, "t"));
        assertFalse("already rendered through refresh", inserted);
        assertEquals(1, bridge.getMessages("chat-1").size());
    }

    @Test
    public void realtimeEchoOfOwnSendDoesNotDuplicate() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        MessageRepository repository = repo(t, engine(t, store, OWNER));
        CreangerChatBridge bridge = bridge(repository);

        // Optimistic own send, then the send RPC confirms with a server row.
        t.responses.add(t.json(200, "\"server-1\""));
        bridge.insertPendingRow("chat-1", "cm-abc", "hello");
        bridge.sendTextMessage("chat-1", "cm-abc", "hello");
        assertEquals(1, bridge.getMessages("chat-1").size());

        // Realtime echoes the confirmed server row for my own send.
        CreangerMessage echo = new CreangerMessage("server-1", "chat-1", OWNER, MessageType.TEXT,
                "hello", MessageStatus.SENT, "cm-abc", 9L, "t", null, null, "t", false);
        boolean inserted = bridge.applyRealtime("chat-1", echo);

        assertFalse(inserted);
        assertEquals(1, bridge.getMessages("chat-1").size());
    }

    @Test
    public void realtimeRowBeforeConfirmReplacesPendingCopy() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        MessageRepository repository = repo(t, engine(t, store, OWNER));
        CreangerChatBridge bridge = bridge(repository);

        bridge.insertPendingRow("chat-1", "cm-abc", "hello");
        // Realtime outruns the RPC response: server row for my own send arrives first.
        CreangerMessage realtime = new CreangerMessage("server-1", "chat-1", OWNER, MessageType.TEXT,
                "hello", MessageStatus.SENT, "cm-abc", 9L, "t", null, null, "t", false);
        boolean inserted = bridge.applyRealtime("chat-1", realtime);

        assertTrue("pending copy should be replaced", inserted);
        List<CreangerMessageUiModel> msgs = bridge.getMessages("chat-1");
        assertEquals(1, msgs.size());
        assertEquals("server-1", msgs.get(0).id);
        assertFalse(msgs.get(0).isLocal);
    }

    // ---- reconnect cursor recovery ----

    @Test
    public void recoverSinceFillsRowsMissedWhileOffline() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        MessageRepository repository = repo(t, engine(t, store, OWNER));
        CreangerChatBridge bridge = bridge(repository);

        // Last realtime row the client saw while connected was seq 5.
        bridge.applyRealtime("chat-1", row("m-5", "chat-1", 5, "uuid-other", null, "t"));
        // get_messages_since returns rows 6..7 (ascending) that arrived while offline.
        t.responses.add(t.json(200, rowsJson(
                "{\"message_id\":\"m-6\",\"chat_seq\":6,\"sender_id\":\"uuid-other\",\"message_type\":\"text\","
                        + "\"content\":\"six\",\"created_at\":\"2026-08-16T10:06:00Z\",\"edited_at\":null,"
                        + "\"deleted_at\":null,\"updated_at\":\"2026-08-16T10:06:00Z\"}",
                "{\"message_id\":\"m-7\",\"chat_seq\":7,\"sender_id\":\"uuid-other\",\"message_type\":\"text\","
                        + "\"content\":\"seven\",\"created_at\":\"2026-08-16T10:07:00Z\",\"edited_at\":null,"
                        + "\"deleted_at\":null,\"updated_at\":\"2026-08-16T10:07:00Z\"}")));

        List<CreangerMessage> recovered = bridge.recoverSince("chat-1", 5, 100);

        assertEquals(2, recovered.size());
        assertEquals(6L, recovered.get(0).chatSeq.longValue()); // ascending
        assertEquals(7L, recovered.get(1).chatSeq.longValue());
        assertEquals("/rest/v1/rpc/get_messages_since", t.requests.get(0).path);

        List<CreangerMessageUiModel> msgs = bridge.getMessages("chat-1");
        assertEquals(3, msgs.size());
        assertEquals("m-7", msgs.get(0).id); // newest first on screen
        assertEquals("m-5", msgs.get(2).id);
    }

    @Test
    public void recoverSinceDedupesAgainstAlreadyDeliveredRealtimeRows() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        MessageRepository repository = repo(t, engine(t, store, OWNER));
        CreangerChatBridge bridge = bridge(repository);

        bridge.applyRealtime("chat-1", row("m-5", "chat-1", 5, "uuid-other", null, "t"));
        bridge.applyRealtime("chat-1", row("m-6", "chat-1", 6, "uuid-other", null, "t")); // delivered on the wire
        // Cursor sync re-fetches both 6 and 7 (6 was boxed but already seen).
        t.responses.add(t.json(200, rowsJson(
                "{\"message_id\":\"m-6\",\"chat_seq\":6,\"sender_id\":\"uuid-other\",\"message_type\":\"text\",\"content\":\"six\",\"created_at\":\"t\",\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"t\"}",
                "{\"message_id\":\"m-7\",\"chat_seq\":7,\"sender_id\":\"uuid-other\",\"message_type\":\"text\",\"content\":\"seven\",\"created_at\":\"t\",\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"t\"}")));

        bridge.recoverSince("chat-1", 5, 100);

        List<CreangerMessageUiModel> msgs = bridge.getMessages("chat-1");
        assertEquals(3, msgs.size()); // 5,6,7 — no duplicate of 6
        assertEquals("m-7", msgs.get(0).id);
        assertEquals("m-6", msgs.get(1).id);
        assertEquals("m-5", msgs.get(2).id);
    }

    private static final class DirectExec extends java.util.concurrent.AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return new ArrayList<>();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    @Test
    public void asyncRecoverSincePostsRecoveredMessages() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        MessageRepository repository = repo(t, engine(t, store, OWNER));
        com.creanger.app.messenger.creanger.data.CreangerMessageAsync async =
                new com.creanger.app.messenger.creanger.data.CreangerMessageAsync(
                        bridge(repository), new DirectExec(), Runnable::run);

        t.responses.add(t.json(200, rowsJson(
                "{\"message_id\":\"m-9\",\"chat_seq\":9,\"sender_id\":\"uuid-other\",\"message_type\":\"text\",\"content\":\"nine\",\"created_at\":\"t\",\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"t\"}")));
        AtomicReference<List<CreangerMessage>> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        async.recoverSince("chat-1", 8, 100, new com.creanger.app.messenger.creanger.data.CreangerMessageAsync.Callback<List<CreangerMessage>>() {
            @Override
            public void onSuccess(List<CreangerMessage> result) {
                out.set(result);
            }

            @Override
            public void onError(CreangerApiException error, Throwable ioError) {
                err.set(error != null ? error : ioError);
            }
        });

        assertNull(err.get());
        assertNotNull(out.get());
        assertEquals(1, out.get().size());
        assertEquals("m-9", out.get().get(0).id);
        assertEquals("m-9", async.getMessages("chat-1").get(0).id);
    }

    // ---- isolation + auth ----

    @Test
    public void realtimeRowsNeverLeakAcrossAccounts() {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore storeA = new InMemoryStore();
        InMemoryStore storeB = new InMemoryStore();

        MessageRepository repoA = repo(t, engine(t, storeA, "uuid-1"));
        repoA.ingestRealtime("chat-1", row("m-1", "chat-1", 1, "uuid-other", null, "t"));
        assertEquals(1, repoA.getCachedMessages("chat-1").size());

        // Account B is a different owner entirely (no shared token store).
        MessageRepository repoB = repo(t, engine(t, storeB, "uuid-2"));
        assertTrue(repoB.getCachedMessages("chat-1").isEmpty());
        assertEquals(0, repoB.getCachedMessages("chat-1").size());
    }

    @Test
    public void clearCachedMessagesDropsRealtimeRowsOnLogout() {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        MessageRepository repository = repo(t, engine(t, store, OWNER));

        repository.ingestRealtime("chat-1", row("m-1", "chat-1", 1, "uuid-other", null, "t"));
        assertEquals(1, repository.getCachedMessages("chat-1").size());

        repository.clearCachedMessages();
        assertTrue(repository.getCachedMessages("chat-1").isEmpty());
    }

    @Test
    public void unauthorizedRecoverSinceThrowsTypedApiErrorAndNotifies() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        ListenerCapture listener = new ListenerCapture();
        MessageRepository repository = repo(t, engine(t, store, OWNER));
        CreangerChatBridge bridge = new CreangerChatBridge(repository, OWNER, listener);

        t.responses.add(t.json(401, "{\"message\":\"not authorized\",\"code\":\"UNAUTHORIZED\"}"));
        try {
            bridge.recoverSince("chat-1", 5, 100);
            fail("expected unauthorized");
        } catch (CreangerApiException e) {
            assertTrue(e.is(com.creanger.app.messenger.creanger.model.ApiError.UNAUTHORIZED));
        }
        assertTrue(listener.errored);
    }
}