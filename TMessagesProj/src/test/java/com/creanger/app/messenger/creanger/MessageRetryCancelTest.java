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
import com.creanger.app.messenger.creanger.data.CreangerMessageAsync;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Retry / Cancel lifecycle tests on the Creanger data layer:
 *
 *  - a failed local send is retried with the SAME {@code client_message_id}
 *    (never a freshly generated id, never a synthetic Telegram id)
 *  - a failed retry keeps the row FAILED (still retryable); a successful retry
 *    reconciles to the server-confirmed row
 *  - duplicate retries for the same message while one is in flight are
 *    coalesced into a single idempotent RPC
 *  - cancelling a PENDING send removes only that local row (no server RPC,
 *    no effect on other messages/chats, confirmed server rows untouched, and
 *    account isolation via the per-owner cache key)
 *  - the retry/cancel paths never route through Telegram/MTProto helpers:
 *    retry emits only the Creanger {@code send_text_message} RPC and cancel
 *    emits no network request at all
 */
public class MessageRetryCancelTest {

    private static final String OWNER = "uuid-1";
    private static final String CHAT = "chat-1";

    private static final class ScriptedTransport implements CreangerHttpTransport {
        final List<TransportResponse> responses = new ArrayList<>();
        final List<ApiRequest> requests = new ArrayList<>();
        volatile IOException failAll = null;
        volatile CountDownLatch holdRequests = null;

        @Override
        public TransportResponse execute(ApiRequest request) throws IOException {
            requests.add(request);
            if (holdRequests != null) {
                try {
                    holdRequests.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
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

    private static CreangerAuthEngine authenticatedEngine(ScriptedTransport t, InMemoryStore store, String owner) {
        store.store(new AuthSession("acc-live", "ref",
                new CreangerUser(owner, "alice", "a@x.com", true, null, null, null),
                System.currentTimeMillis()));
        return new CreangerAuthEngine(new SupabaseAuthClient(t), store);
    }

    private static MessageRepository repo(ScriptedTransport t, InMemoryStore store) {
        return repo(t, store, OWNER);
    }

    private static MessageRepository repo(ScriptedTransport t, InMemoryStore store, String owner) {
        return new MessageRepository(authenticatedEngine(t, store, owner), new CreangerChatApiClient(t));
    }

    private static CreangerMessageAsync async(ScriptedTransport t, InMemoryStore store) {
        return new CreangerMessageAsync(
                new CreangerChatBridge(repo(t, store), OWNER, null),
                synchronousExecutor(),
                runnable -> runnable.run()); // synchronous main poster
    }

    private static ExecutorService synchronousExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }

    /** A callback that captures success to an AtomicReference and fails the test
     *  on error unless told not to. */
    private static <T> CreangerMessageAsync.Callback<T> capture(
            AtomicReference<T> out, AtomicReference<Throwable> errOut) {
        return new CreangerMessageAsync.Callback<T>() {
            @Override
            public void onSuccess(T result) {
                out.set(result);
                synchronized (out) {
                    out.notifyAll();
                }
            }

            @Override
            public void onError(CreangerApiException error, Throwable ioError) {
                errOut.set(error != null ? error : ioError);
                synchronized (out) {
                    out.notifyAll();
                }
            }
        };
    }

    /** Waits until the callback fired or the timeout elapses. */
    private static void await(AtomicReference<?> signal) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        synchronized (signal) {
            while (signal.get() == null && System.currentTimeMillis() < deadline) {
                signal.wait(100);
            }
        }
    }

    /**
     * Drives a FAILED local row: {@code async.sendTextMessage} fails on the
     * transport, leaving a local FAILED copy with the same client id, exactly
     * as a real offline send does.
     */
    private static CreangerMessageAsync failedSend(ScriptedTransport t, InMemoryStore store,
                                                   String clientMessageId) throws Exception {
        CreangerMessageAsync async = async(t, store);
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        t.failAll = new IOException("no route to host");
        async.sendTextMessage(CHAT, clientMessageId, "hello", capture(out, err));
        await(err);
        assertNull(out.get());
        assertNotNull(err.get());
        List<CreangerMessageUiModel> rows = async.getMessages(CHAT);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).isFailed());
        assertEquals(clientMessageId, rows.get(0).clientMessageId);
        return async;
    }

    // ---- failed retry: success, failure, id preservation ----

    @Test
    public void failedRetrySucceedsAndConfirms() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        CreangerMessageAsync async = failedSend(t, store, "client-retry");

        t.failAll = null;
        t.responses.add(t.json(200, "\"server-msg-9\""));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        async.retrySend(CHAT, "client-retry", "hello", capture(out, err));
        await(out);

        assertNull(err.get());
        assertEquals("server-msg-9", out.get());
        List<CreangerMessageUiModel> rows = async.getMessages(CHAT);
        assertEquals(1, rows.size());
        assertEquals("server-msg-9", rows.get(0).id);
        assertFalse(rows.get(0).isPending());
        assertFalse(rows.get(0).isFailed());
        assertFalse(rows.get(0).isLocal);
    }

    @Test
    public void failedRetryStaysFailedOnFailure() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        CreangerMessageAsync async = failedSend(t, store, "client-fail");

        // The retry itself fails (still offline).
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        async.retrySend(CHAT, "client-fail", "hello", capture(out, err));
        await(err);

        assertNull(out.get());
        assertNotNull(err.get());
        List<CreangerMessageUiModel> rows = async.getMessages(CHAT);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).isFailed());
        assertEquals("client-fail", rows.get(0).clientMessageId);
        assertTrue(rows.get(0).isLocal);
    }

    @Test
    public void retryPreservesClientMessageIdAndSendsNoSyntheticId() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        CreangerMessageAsync async = failedSend(t, store, "client-preserve");

        t.failAll = null;
        t.responses.add(t.json(200, "\"server-preserve\""));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        int before = t.requests.size();
        async.retrySend(CHAT, "client-preserve", "hello", capture(out, err));
        await(out);

        assertNull(err.get());
        assertEquals("server-preserve", out.get());
        // Only ONE request fired, to the Creanger send RPC.
        assertEquals(before + 1, t.requests.size());
        ApiRequest rpc = t.requests.get(before);
        assertEquals("/rest/v1/rpc/send_text_message", rpc.path);
        assertEquals("POST", rpc.method);
        // The SAME client_message_id is reused — never regenerated.
        assertTrue(rpc.jsonBody.contains("\"p_client_message_id\":\"client-preserve\""));
        // No synthetic Telegram id, no sender/user id ever travels to the backend.
        assertFalse(rpc.jsonBody.contains("\"p_message_id\""));
        assertFalse(rpc.jsonBody.contains("sender"));
        assertFalse(rpc.jsonBody.contains(OWNER));
        // Identity preserved: the confirmed row keeps the client_message_id.
        CreangerMessageUiModel confirmed = async.getMessages(CHAT).get(0);
        assertEquals("server-preserve", confirmed.id);
        assertEquals("client-preserve", confirmed.clientMessageId);
    }

    // ---- duplicate retry protection ----

    @Test
    public void duplicateRetryIsCoalescedToSingleRpc() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        CreangerMessageAsync async = failedSend(t, store, "client-dup");

        t.failAll = null;
        t.responses.add(t.json(200, "\"server-dup\""));
        // Hold the first retry's request so the second retry is issued while the
        // first is provably in flight.
        t.holdRequests = new CountDownLatch(1);
        AtomicReference<String> out1 = new AtomicReference<>();
        AtomicReference<Throwable> err1 = new AtomicReference<>();
        AtomicReference<String> out2 = new AtomicReference<>();
        AtomicReference<Throwable> err2 = new AtomicReference<>();
        int before = t.requests.size();
        async.retrySend(CHAT, "client-dup", "hello", capture(out1, err1));
        async.retrySend(CHAT, "client-dup", "hello", capture(out2, err2)); // dropped
        t.holdRequests.countDown();
        await(out1);

        assertNull(err1.get());
        assertEquals("server-dup", out1.get());
        // The duplicate retry was coalesced: its callback never fired and only
        // one send RPC was issued.
        assertNull(out2.get());
        assertNull(err2.get());
        assertEquals(before + 1, t.requests.size());
        assertEquals(1, async.getMessages(CHAT).size());
    }

    // ---- pending cancellation ----

    @Test
    public void cancelRemovesPendingRowLocallyWithoutRpc() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        CreangerChatBridge bridge = new CreangerChatBridge(repo(t, store), OWNER, null);
        CreangerMessageAsync async = new CreangerMessageAsync(bridge, synchronousExecutor(), runnable -> runnable.run());

        bridge.insertPendingRow(CHAT, "client-cancel", "hi");
        List<CreangerMessageUiModel> pending = async.getMessages(CHAT);
        assertEquals(1, pending.size());
        assertTrue(pending.get(0).isPending());
        assertEquals("client-cancel", pending.get(0).clientMessageId);

        AtomicReference<Boolean> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        async.cancelPending(CHAT, "client-cancel", capture(out, err));
        await(out);

        assertNull(err.get());
        assertTrue(out.get());
        assertTrue(async.getMessages(CHAT).isEmpty());
        // Cancel is local-only: no network request at all.
        assertTrue(t.requests.isEmpty());
    }

    @Test
    public void cancelOnlyAffectsTargetRowAndChat() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        CreangerChatBridge bridge = new CreangerChatBridge(repo(t, store), OWNER, null);

        // Two pending rows in chat-1 (different client ids) + one in chat-2.
        bridge.insertPendingRow(CHAT, "client-a", "a");
        bridge.insertPendingRow(CHAT, "client-b", "b");
        bridge.insertPendingRow("chat-2", "client-a2", "a2");
        assertEquals(2, bridge.getMessages(CHAT).size());

        assertTrue(bridge.cancelPending(CHAT, "client-a"));

        // The sibling pending row and the other chat are untouched.
        List<CreangerMessageUiModel> remaining = bridge.getMessages(CHAT);
        assertEquals(1, remaining.size());
        assertEquals("client-b", remaining.get(0).clientMessageId);
        assertEquals(1, bridge.getMessages("chat-2").size());
        assertEquals("client-a2", bridge.getMessages("chat-2").get(0).clientMessageId);
    }

    @Test
    public void cancelIgnoresConfirmedRowsAndUnknownIds() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        CreangerChatBridge bridge = new CreangerChatBridge(repo(t, store), OWNER, null);

        // Confirmed server row (seeded via a REST page) with a client id.
        String row = "{\"id\":\"m-1\",\"chat_id\":\"" + CHAT + "\",\"sender_id\":\"" + OWNER + "\","
                + "\"message_type\":\"text\",\"content\":\"confirmed\",\"status\":\"sent\","
                + "\"client_message_id\":\"client-confirmed\",\"chat_seq\":1,"
                + "\"created_at\":\"2026-08-15T08:01:00Z\",\"edited_at\":null,\"deleted_at\":null,"
                + "\"updated_at\":\"2026-08-15T08:01:00Z\"}";
        t.responses.add(t.json(200, "[" + row + "]"));
        bridge.refreshMessages(CHAT, 30);

        // A confirmed server row is never removed by cancel (no local pending copy).
        assertFalse(bridge.cancelPending(CHAT, "client-confirmed"));
        assertEquals(1, bridge.getMessages(CHAT).size());
        assertEquals("m-1", bridge.getMessages(CHAT).get(0).id);

        // Unknown ids are a no-op.
        assertFalse(bridge.cancelPending(CHAT, "client-ghost"));
        assertEquals(1, bridge.getMessages(CHAT).size());
    }

    @Test
    public void cancelIsAccountIsolated() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore storeA = new InMemoryStore();
        InMemoryStore storeB = new InMemoryStore();
        MessageRepository repoA = repo(t, storeA, "uuid-A");
        MessageRepository repoB = repo(t, storeB, "uuid-B");

        // Both accounts hold a pending copy of the SAME client id in the SAME
        // chat (the RPC failing keeps the local pending row in the cache).
        t.failAll = new IOException("offline");
        try {
            repoA.sendTextMessage(CHAT, "client-shared", "hi");
        } catch (IOException expected) {
        }
        try {
            repoB.sendTextMessage(CHAT, "client-shared", "hi");
        } catch (IOException expected) {
        }
        assertEquals(1, repoA.getCachedMessages(CHAT).size());
        assertEquals(1, repoB.getCachedMessages(CHAT).size());

        // Cancelling on account A must not touch account B's cache.
        assertTrue(repoA.cancelPending(CHAT, "client-shared"));
        assertTrue(repoA.getCachedMessages(CHAT).isEmpty());
        List<CreangerMessage> accountB = repoB.getCachedMessages(CHAT);
        assertEquals(1, accountB.size());
        assertTrue(accountB.get(0).isLocal);
        assertEquals("client-shared", accountB.get(0).clientMessageId);
    }

    // ---- legacy Telegram path untouched ----

    @Test
    public void retryAndCancelNeverTouchTelegramPath() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();

        // Retry: only the Creanger send RPC is emitted, with the original
        // client id and chat id — nothing resembling an MTProto send, no
        // synthetic numeric message id, no sender/user id.
        CreangerMessageAsync async = failedSend(t, store, "client-legacy");
        t.failAll = null;
        t.responses.add(t.json(200, "\"server-legacy\""));
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        async.retrySend(CHAT, "client-legacy", "hello", capture(out, err));
        await(out);

        assertNull(err.get());
        // Exactly two send RPCs total: the original failed send + the retry.
        assertEquals(2, t.requests.size());
        assertEquals("/rest/v1/rpc/send_text_message", t.requests.get(0).path);
        assertEquals("/rest/v1/rpc/send_text_message", t.requests.get(1).path);
        assertFalse(t.requests.get(1).jsonBody.contains("\"message_id\""));
        assertFalse(t.requests.get(1).jsonBody.contains("sender"));
        assertFalse(t.requests.get(1).jsonBody.contains(OWNER));

        // Cancel: purely local, issues NO request, so it can never be routed
        // through Telegram's cancelSendingMessage/MTProto helpers.
        CreangerChatBridge bridge = new CreangerChatBridge(repo(t, store), OWNER, null);
        bridge.insertPendingRow(CHAT, "client-cancel-legacy", "hi");
        int before = t.requests.size();
        assertTrue(bridge.cancelPending(CHAT, "client-cancel-legacy"));
        assertEquals(before, t.requests.size());
        assertTrue(bridge.getMessages(CHAT).isEmpty());
    }
}
