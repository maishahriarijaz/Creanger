package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.data.CreangerChatBridge;
import com.creanger.app.messenger.creanger.data.CreangerChatController;
import com.creanger.app.messenger.creanger.data.CreangerChatController.Listener;
import com.creanger.app.messenger.creanger.data.CreangerChatController.Phase;
import com.creanger.app.messenger.creanger.data.CreangerMessageAsync;
import com.creanger.app.messenger.creanger.data.CreangerMessageUiModel;
import com.creanger.app.messenger.creanger.data.MessageRepository;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.model.MessageModels.MessagePage;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/**
 * {@link CreangerChatController} tests: entry/detection, initial load,
 * pagination, optimistic send + reconcile, failed-send + retry, and
 * close/isolation. Async runs on a synchronous executor + main poster so all
 * listener callbacks are deterministic on the test thread.
 */
public class CreangerChatControllerTest {

    private static final String OWNER = "uuid-1";
    private static final String CHAT = "chat-1";

    private static final class ScriptedTransport implements CreangerHttpTransport {
        final List<TransportResponse> responses = new ArrayList<>();
        volatile IOException failAll = null;

        @Override
        public TransportResponse execute(ApiRequest request) throws IOException {
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

    private static final class Recorder implements Listener {
        final List<List<CreangerMessageUiModel>> messageSnapshots = new ArrayList<>();
        final List<Phase> phases = new ArrayList<>();
        final List<String> clientIds = new ArrayList<>();
        final List<String> serverIds = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();

        @Override
        public void onMessagesChanged(CreangerChatController c, List<CreangerMessageUiModel> m) {
            messageSnapshots.add(new ArrayList<>(m));
            signal();
        }

        @Override
        public void onPhaseChanged(CreangerChatController c, Phase phase) {
            phases.add(phase);
            signal();
        }

        @Override
        public void onSendStatusChanged(CreangerChatController c, String clientMessageId, String status,
                                        String serverMessageId, Throwable error) {
            clientIds.add(clientMessageId);
            serverIds.add(serverMessageId);
            errors.add(error);
            signal();
        }

        Phase lastPhase() {
            return phases.isEmpty() ? null : phases.get(phases.size() - 1);
        }

        Throwable lastError() {
            return errors.isEmpty() ? null : errors.get(errors.size() - 1);
        }

        String lastServerId() {
            return serverIds.isEmpty() ? null : serverIds.get(serverIds.size() - 1);
        }

        private final Object lock = new Object();

        private void signal() {
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        /** Awaits any next Listener event (bounded). */
        void awaitEvent(int expectedEventCount) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            synchronized (lock) {
                while (events() < expectedEventCount && System.currentTimeMillis() < deadline) {
                    lock.wait(100);
                }
            }
        }

        /**
         * Awaits an exact, state-based condition (bounded). The event COUNTER is
         * not a reliable barrier — a single action (e.g. a failed send) can fire
         * several Listener callbacks, so tests await the observable state they
         * assert instead of an opaque event count.
         */
        void awaitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            synchronized (lock) {
                while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
                    lock.wait(100);
                }
            }
        }

        int events() {
            return phases.size() + serverIds.size();
        }
    }

    private static CreangerAuthEngine authenticatedEngine(ScriptedTransport t, InMemoryStore store) {
        store.store(new AuthSession("acc-live", "ref",
                new CreangerUser(OWNER, "alice", "a@x.com", true, null, null, null),
                System.currentTimeMillis()));
        return new CreangerAuthEngine(new SupabaseAuthClient(t), store);
    }

    private static MessageRepository repo(ScriptedTransport t, CreangerAuthEngine engine) {
        return new MessageRepository(engine, new CreangerChatApiClient(t));
    }

    private static CreangerMessageAsync async(ScriptedTransport t, InMemoryStore store) {
        return new CreangerMessageAsync(
                new CreangerChatBridge(repo(t, authenticatedEngine(t, store)), OWNER, null),
                synchronousExecutor(),
                runnable -> runnable.run());
    }

    private static ExecutorService synchronousExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r);
            th.setDaemon(true);
            return th;
        });
    }

    private static String msgRow(String id, long chatSeq, String content, String createdAt) {
        return "{\"id\":\"" + id + "\",\"chat_id\":\"" + CHAT + "\",\"sender_id\":\"uuid-1\",\"message_type\":\"text\","
                + "\"content\":\"" + content + "\",\"status\":\"sent\",\"client_message_id\":null,"
                + "\"chat_seq\":" + chatSeq + ",\"created_at\":\"" + createdAt
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

    // ---- entry / detection ----

    @Test
    public void detectionYieldsCreangerOnlyWhenIdPresentAndEnabled() {
        assertTrue(CreangerChatDetection.isCreangerChat("chat-1", true));
        assertFalse(CreangerChatDetection.isCreangerChat("chat-1", false));
        assertFalse(CreangerChatDetection.isCreangerChat(null, true));
        assertFalse(CreangerChatDetection.isCreangerChat("", true));
        assertTrue(CreangerChatDetection.isLegacyTelegramChat(null, true));
        assertTrue(CreangerChatDetection.isLegacyTelegramChat("chat-1", false));
        assertFalse(CreangerChatDetection.isLegacyTelegramChat("chat-1", true));
    }

    // ---- initial load ----

    @Test
    public void refreshLoadsNewestFirstAndReachesReady() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, rowsJson(
                msgRow("m-2", 2, "second", "2026-08-15T08:02:00Z"),
                msgRow("m-1", 1, "first", "2026-08-15T08:01:00Z"))));
        Recorder rec = new Recorder();
        CreangerChatController c = new CreangerChatController(async(t, store), CHAT, rec);

        c.refresh();
        rec.awaitEvent(2); // LOADING phase + page

        assertEquals(Phase.READY, c.getPhase());
        assertEquals(Phase.READY, rec.lastPhase());
        assertEquals(2, c.getMessages().size());
        assertEquals("m-2", c.getMessages().get(0).id);
        assertEquals("m-1", c.getMessages().get(1).id);
        assertEquals(false, c.hasMore());
    }

    @Test
    public void refreshOnEmptyChatReachesEmpty() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, "[]"));
        Recorder rec = new Recorder();
        CreangerChatController c = new CreangerChatController(async(t, store), CHAT, rec);

        c.refresh();
        rec.awaitEvent(2);

        assertEquals(Phase.EMPTY, c.getPhase());
        assertEquals(Phase.EMPTY, rec.lastPhase());
        assertTrue(c.getMessages().isEmpty());
    }

    // ---- pagination ----

    @Test
    public void loadOlderUsesCursorAndAppends() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        // newest page: 31 rows (DEFAULT_PAGE_SIZE + 1 probe) => hasMore, cursor set
        String[] newest = new String[31];
        for (int i = 0; i < 31; i++) {
            long seq = 100 - i;
            newest[i] = msgRow("m-" + seq, seq, "row-" + seq, "2026-08-15T08:00:00Z");
        }
        t.responses.add(t.json(200, rowsJson(newest)));
        Recorder rec = new Recorder();
        CreangerChatController c = new CreangerChatController(async(t, store), CHAT, rec);

        c.refresh();
        rec.awaitEvent(2);
        assertTrue(c.hasMore());
        assertNotNull(c.getNextOlderSeq());
        assertEquals(30, c.getMessages().size());

        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "first", "2026-08-15T08:00:00Z"))));
        int before = rec.events();
        c.loadOlder();
        rec.awaitEvent(before + 1); // phase transition READY

        assertEquals(31, c.getMessages().size());
        assertEquals("m-1", c.getMessages().get(30).id);
        assertFalse(c.hasMore());
    }

    // ---- optimistic send + reconcile ----

    @Test
    public void sendInsertsPendingThenConfirms() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, "\"server-1\""));
        Recorder rec = new Recorder();
        CreangerChatController c = new CreangerChatController(async(t, store), CHAT, rec);

        String cmid = c.sendText("hello world");

        assertNotNull(cmid);
        // optimistic: pending row visible before the RPC completes
        assertEquals(1, c.getMessages().size());
        assertTrue(c.getMessages().get(0).isPending());

        rec.awaitUntil(() -> {
            List<CreangerMessageUiModel> m = c.getMessages();
            return m.size() == 1 && !m.get(0).isPending() && m.get(0).id != null
                    && m.get(0).id.equals("server-1");
        });
        assertEquals("server-1", rec.lastServerId());

        CreangerMessageUiModel row = c.getMessages().get(0);
        assertFalse(row.isPending());
        assertEquals("server-1", row.id);
        assertFalse(row.isLocal);
    }

    @Test
    public void sendFailureFlagsFailedAndNotifiesNetworkError() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.failAll = new IOException("offline");
        Recorder rec = new Recorder();
        CreangerChatController c = new CreangerChatController(async(t, store), CHAT, rec);

        c.sendText("msg");
        rec.awaitUntil(() -> {
            List<CreangerMessageUiModel> m = c.getMessages();
            return m.size() == 1 && m.get(0).isFailed() && rec.lastPhase() == Phase.NETWORK_ERROR;
        });

        assertTrue(c.getMessages().get(0).isFailed());
        assertTrue(c.getMessages().get(0).isLocal);
        assertNotNull(rec.lastError());
        assertEquals(Phase.NETWORK_ERROR, rec.lastPhase());
    }

    // ---- retry ----

    @Test
    public void retryReusesClientIdAndConfirms() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.failAll = new IOException("offline");
        Recorder rec = new Recorder();
        CreangerChatController c = new CreangerChatController(async(t, store), CHAT, rec);

        String cmid = c.sendText("msg");
        rec.awaitUntil(() -> {
            List<CreangerMessageUiModel> m = c.getMessages();
            return m.size() == 1 && m.get(0).isFailed();
        });
        assertTrue(c.getMessages().get(0).isFailed());

        t.failAll = null;
        t.responses.add(t.json(200, "\"server-9\""));
        c.retrySend(cmid);
        rec.awaitUntil(() -> {
            List<CreangerMessageUiModel> m = c.getMessages();
            return m.size() == 1 && m.get(0).id != null && m.get(0).id.equals("server-9")
                    && !m.get(0).isFailed();
        });

        CreangerMessageUiModel row = c.getMessages().get(0);
        assertFalse(row.isFailed());
        assertFalse(row.isPending());
        assertEquals("server-9", row.id);
    }

    // ---- close / isolation ----

    @Test
    public void closeClearsStateAndReturnsToLoading() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "first", "2026-08-15T08:01:00Z"))));
        Recorder rec = new Recorder();
        CreangerChatController c = new CreangerChatController(async(t, store), CHAT, rec);

        c.refresh();
        rec.awaitEvent(2);
        assertEquals(Phase.READY, c.getPhase());
        assertFalse(c.getMessages().isEmpty());

        c.close();

        assertTrue(c.getMessages().isEmpty());
        assertEquals(Phase.LOADING, c.getPhase());
    }

    // ---- optimistic edit + reconcile ----

    @Test
    public void editMessageOptimisticThenConfirms() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "original", "2026-08-15T08:01:00Z"))));
        Recorder rec = new Recorder();
        CreangerChatController c = new CreangerChatController(async(t, store), CHAT, rec);

        c.refresh();
        rec.awaitEvent(2);
        assertEquals("original", c.getMessages().get(0).content);

        t.responses.add(t.json(200, "\"m-1\""));
        c.editMessage("m-1", "edited");

        // optimistic: the edit is visible before the RPC resolves
        assertEquals("edited", c.getMessages().get(0).content);
        // the author-only RPC was invoked (single confirm pending)
        assertEquals("m-1", c.getMessages().get(0).id);
    }

    @Test
    public void editMessageRollsBackOnUnauthorized() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "original", "2026-08-15T08:01:00Z"))));
        Recorder rec = new Recorder();
        CreangerChatController c = new CreangerChatController(async(t, store), CHAT, rec);

        c.refresh();
        rec.awaitEvent(2);
        int before = rec.events();

        // The RPC will reject (not the author) — scripted BEFORE the edit so the
        // background task deterministically receives the 400.
        t.responses.add(t.json(400,
                "{\"code\":\"P0001\",\"message\":\"only the message author may edit this message\"}"));
        c.editMessage("m-1", "hijacked");
        assertEquals("hijacked", c.getMessages().get(0).content); // optimistic

        rec.awaitEvent(before + 2); // rollback notify + auth-error phase
        assertEquals("original", c.getMessages().get(0).content); // rolled back
        assertEquals(Phase.AUTH_ERROR, rec.lastPhase());
    }

    @Test
    public void deleteMessageOptimisticThenConfirms() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "bye", "2026-08-15T08:01:00Z"))));
        Recorder rec = new Recorder();
        CreangerChatController c = new CreangerChatController(async(t, store), CHAT, rec);

        c.refresh();
        rec.awaitEvent(2);
        assertEquals(1, c.getMessages().size());

        t.responses.add(t.json(200, "\"m-1\""));
        c.deleteMessage("m-1");
        assertTrue(c.getMessages().isEmpty()); // optimistic removal (soft delete)
    }

    @Test
    public void deleteMessageRollsBackOnFailure() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "own", "2026-08-15T08:01:00Z"))));
        Recorder rec = new Recorder();
        CreangerChatController c = new CreangerChatController(async(t, store), CHAT, rec);

        c.refresh();
        rec.awaitEvent(2);
        int before = rec.events();

        t.failAll = new IOException("offline");
        c.deleteMessage("m-1");
        assertTrue(c.getMessages().isEmpty()); // optimistic removal

        rec.awaitEvent(before + 2); // rollback notify + network-error phase
        assertEquals(1, c.getMessages().size()); // restored
        assertEquals("own", c.getMessages().get(0).content);
        assertEquals(Phase.NETWORK_ERROR, rec.lastPhase());
    }
}
