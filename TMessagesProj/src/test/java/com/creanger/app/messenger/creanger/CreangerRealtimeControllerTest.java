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
import com.creanger.app.messenger.creanger.data.CreangerChatController.RealtimeClientFactory;
import com.creanger.app.messenger.creanger.data.CreangerMessageAsync;
import com.creanger.app.messenger.creanger.data.CreangerMessageUiModel;
import com.creanger.app.messenger.creanger.data.MessageRepository;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.realtime.MessageRealtimeClient;
import com.creanger.app.messenger.creanger.realtime.MessageRealtimeTransport;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * End-to-end controller integration for the open Creanger chat:
 * opening subscribes Realtime, incoming INSERTs reach the controller/bridge,
 * own-send echoes reconcile with the optimistic PENDING row, realtime + REST
 * rows never duplicate, a reconnect runs the {@code get_messages_since} cursor
 * sync, recovered rows dedup against already-delivered realtime rows,
 * wrong-chat frames are dropped, logout unsubscribes, account switches isolate
 * realtime state, and repeated open/resume never creates a second
 * subscription. Every runner (async executor, poster, connect executor,
 * scheduler) is synchronous so all assertions are deterministic.
 */
public class CreangerRealtimeControllerTest {

    private static final String OWNER = "uuid-1";
    private static final String CHAT = "chat-1";
    private static final String TOKEN = "jwt-live";

    // ---- REST data-plane script ----

    private static final class HttpScriptedTransport implements CreangerHttpTransport {
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

    // ---- realtime script ----

    private static final class RealtimeScriptedTransport implements MessageRealtimeTransport {
        MessageRealtimeTransport.Listener listener;
        final List<String> tokens = new ArrayList<>();
        final List<String> broadcasts = new ArrayList<>();
        int connectCount;
        int closeCount;

        @Override
        public void setListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void connect(String accessToken) {
            connectCount++;
            tokens.add(accessToken);
        }

        @Override
        public void sendBroadcast(String event, String payloadJson) {
            broadcasts.add(event + "|" + payloadJson);
        }

        @Override
        public void close() {
            closeCount++;
        }

        void connected() {
            listener.onTransportConnected();
        }

        void frame(String json) {
            listener.onTransportFrame(json);
        }

        void disconnect(Throwable cause) {
            listener.onTransportClosed(cause);
        }
    }

    /** Aggregates one account's realtime wiring so tests can drive it. */
    private static final class Harness {
        final RealtimeScriptedTransport transport = new RealtimeScriptedTransport();
        final RecordingScheduler scheduler = new RecordingScheduler();

        final RealtimeClientFactory factory = listener ->
                new MessageRealtimeClient(transport, () -> TOKEN,
                        Runnable::run, scheduler, listener, new DirectExecutor());
    }

    private static final class RecordingScheduler implements MessageRealtimeClient.ReconnectScheduler {
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void schedule(long delayMillis, Runnable task) {
            delays.add(delayMillis);
            tasks.add(task);
        }

        void runAll() {
            for (Runnable task : new ArrayList<>(tasks)) {
                task.run();
            }
            tasks.clear();
        }
    }

    private static final class DirectExecutor extends AbstractExecutorService {
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

    // ---- controller listener recorder ----

    private static final class Recorder implements Listener {
        final List<List<CreangerMessageUiModel>> messageSnapshots = new ArrayList<>();
        final List<Phase> phases = new ArrayList<>();

        @Override
        public void onMessagesChanged(CreangerChatController c, List<CreangerMessageUiModel> m) {
            messageSnapshots.add(new ArrayList<>(m));
        }

        @Override
        public void onPhaseChanged(CreangerChatController c, Phase phase) {
            phases.add(phase);
        }

        @Override
        public void onSendStatusChanged(CreangerChatController c, String clientMessageId, String status,
                                        String serverMessageId, Throwable error) {
        }
    }

    // ---- builders ----

    private static CreangerAuthEngine authenticatedEngine(HttpScriptedTransport t, InMemoryStore store) {
        store.store(new AuthSession("acc-live", "ref",
                new CreangerUser(OWNER, "alice", "a@x.com", true, null, null, null),
                System.currentTimeMillis()));
        return new CreangerAuthEngine(new SupabaseAuthClient(t), store);
    }

    private static MessageRepository repo(HttpScriptedTransport t, CreangerAuthEngine engine) {
        return new MessageRepository(engine, new CreangerChatApiClient(t));
    }

    private static CreangerMessageAsync async(HttpScriptedTransport t, InMemoryStore store) {
        return new CreangerMessageAsync(
                new CreangerChatBridge(repo(t, authenticatedEngine(t, store)), OWNER, null),
                new DirectExecutor(),
                runnable -> runnable.run());
    }

    private static CreangerChatController controller(HttpScriptedTransport http, InMemoryStore store,
                                                     Harness realtime, Recorder rec) {
        return new CreangerChatController(async(http, store), realtime.factory, CHAT, rec);
    }

    // ---- wire helpers ----

    private static String rowJson(String id, long chatSeq, String content) {
        return "{\"id\":\"" + id + "\",\"chat_id\":\"" + CHAT + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"sent\","
                + "\"client_message_id\":null,\"chat_seq\":" + chatSeq
                + ",\"created_at\":\"2026-08-16T10:00:00Z\",\"edited_at\":null,\"deleted_at\":null,"
                + "\"updated_at\":\"2026-08-16T10:00:00Z\"}";
    }

    private static String rpcRow(String id, long chatSeq, String content) {
        return "{\"message_id\":\"" + id + "\",\"chat_seq\":" + chatSeq + ",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"created_at\":\"2026-08-16T10:0" + chatSeq
                + ":00Z\",\"edited_at\":null,\"deleted_at\":null,\"updated_at\":\"2026-08-16T10:0" + chatSeq + ":00Z\"}";
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

    private static String rowsJson(List<String> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(rows.get(i));
        }
        return sb.append(']').toString();
    }

    /** A change-recovery row keyed by {@code message_id}, full projection. */
    private static String changeRow(String id, long chatSeq, String content, String editedAt,
                                    String deletedAt, String updatedAt, String status) {
        String edited = editedAt != null ? "\"" + editedAt + "\"" : "null";
        String deleted = deletedAt != null ? "\"" + deletedAt + "\"" : "null";
        return "{\"message_id\":\"" + id + "\",\"chat_id\":\"" + CHAT + "\",\"chat_seq\":" + chatSeq + ","
                + "\"sender_id\":\"uuid-other\",\"message_type\":\"text\",\"content\":\"" + content + "\","
                + "\"status\":\"" + status + "\",\"client_message_id\":null,\"reply_to_message_id\":null,"
                + "\"created_at\":\"2026-08-16T10:00:00Z\",\"edited_at\":" + edited
                + ",\"deleted_at\":" + deleted + ",\"updated_at\":\"" + updatedAt + "\"}";
    }

    /** A status-recovery row from {@code get_message_statuses_since}. */
    private static String statusRow(String messageId, String status, String updatedAt) {
        return "{\"message_id\":\"" + messageId + "\",\"status\":\"" + status
                + "\",\"updated_at\":\"" + updatedAt + "\"}";
    }

    /** Modern Supabase Realtime INSERT frame for a message row. */
    private static String insert(String id, String chatId, long seq, String content,
                                 String clientMsgId, String sender) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"INSERT\","
                + "\"new\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"" + sender + "\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"sent\","
                + "\"client_message_id\":" + (clientMsgId == null ? "null" : "\"" + clientMsgId + "\"")
                + ",\"chat_seq\":" + seq + ",\"created_at\":\"2026-08-16T10:00:00Z\",\"edited_at\":null,"
                + "\"deleted_at\":null,\"updated_at\":\"2026-08-16T10:00:00Z\"}}}}";
    }

    /** Modern Supabase Realtime UPDATE frame carrying an edit (deleted_at null). */
    private static String updateEdit(String id, String chatId, String content) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"UPDATE\","
                + "\"new\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"sent\","
                + "\"client_message_id\":null,\"chat_seq\":5,\"created_at\":\"2026-08-16T10:00:00Z\","
                + "\"edited_at\":\"2026-08-16T11:00:00Z\",\"deleted_at\":null,"
                + "\"updated_at\":\"2026-08-16T11:00:00Z\"}}}}";
    }

    /** Modern Supabase Realtime UPDATE frame whose new.deleted_at is set (soft-delete tombstone). */
    private static String tombstone(String id, String chatId) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"UPDATE\","
                + "\"new\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"previous\",\"status\":\"sent\","
                + "\"client_message_id\":null,\"chat_seq\":5,\"created_at\":\"2026-08-16T10:00:00Z\","
                + "\"edited_at\":null,\"deleted_at\":\"2026-08-16T12:00:00Z\","
                + "\"updated_at\":\"2026-08-16T12:00:00Z\"}}}}";
    }

    /** Modern Supabase Realtime DELETE frame (hard delete; only old is present). */
    private static String hardDelete(String id, String chatId) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"DELETE\","
                + "\"old\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"old\",\"chat_seq\":5,"
                + "\"created_at\":\"2026-08-16T10:00:00Z\"}}}}";
    }

    // ---- 1. open → subscription starts ----

    @Test
    public void openingChatStartsRealtimeSubscription() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();

        assertEquals(Phase.EMPTY, c.getPhase());
        assertEquals(1, realtime.transport.connectCount);
        assertEquals(TOKEN, realtime.transport.tokens.get(0));
        assertTrue(realtime.scheduler.delays.isEmpty()); // no reconnect storm at open
    }

    // ---- 2. realtime INSERT reaches controller/bridge ----

    @Test
    public void realtimeIncomingMessageReachesController() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected();
        realtime.transport.frame(insert("m-1", CHAT, 1, "hello", null, "uuid-other"));

        assertEquals(2, rec.messageSnapshots.size()); // initial page + realtime insert
        assertEquals(1, c.getMessages().size());
        CreangerMessageUiModel row = c.getMessages().get(0);
        assertEquals("m-1", row.id);
        assertEquals("hello", row.content);
        assertEquals(Long.valueOf(1L), row.chatSeq);
        assertFalse(row.out); // someone else's message
    }

    // ---- 3. own-send echo reconciles with the optimistic row ----

    @Test
    public void ownSendEchoReconcilesWithOptimisticRow() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected();

        http.responses.add(http.json(200, "\"server-1\""));
        String cmid = c.sendText("hi");
        assertEquals(1, c.getMessages().size()); // pending → confirmed by the send RPC

        // Realtime echoes the server row of the user's own send.
        realtime.transport.frame(insert("server-1", CHAT, 9, "hi", cmid, OWNER));

        assertEquals(1, c.getMessages().size()); // the echo does NOT add a copy
        CreangerMessageUiModel row = c.getMessages().get(0);
        assertEquals("server-1", row.id);
        assertEquals(cmid, row.clientMessageId); // client_message_id preserved
        assertEquals("hi", row.content);
        assertTrue(row.out); // it is the user's own row
        assertFalse(row.isLocal);
    }

    // ---- 4. realtime + REST duplicates collapse ----

    @Test
    public void realtimeRowAlreadyOnScreenFromRestDoesNotDuplicate() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, rowsJson(rowJson("m-5", 5, "five"), rowJson("m-4", 4, "four"))));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected();
        assertEquals(2, c.getMessages().size());

        int snapshots = rec.messageSnapshots.size();
        // Realtime re-delivers a row the REST page already rendered.
        realtime.transport.frame(insert("m-4", CHAT, 4, "four", null, "uuid-other"));

        assertEquals(2, c.getMessages().size());
        assertEquals(snapshots, rec.messageSnapshots.size()); // no re-render for the duplicate
    }

    // ---- 5. reconnect triggers cursor recovery ----

    @Test
    public void reconnectRunsCursorRecovery() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected();
        realtime.transport.frame(insert("m-5", CHAT, 5, "five", null, "uuid-other"));

        http.responses.add(http.json(200, rowsJson(rpcRow("m-6", 6, "six"), rpcRow("m-7", 7, "seven"))));
        realtime.transport.disconnect(new IOException("socket closed"));
        realtime.scheduler.runAll(); // reconnect attempt
        realtime.transport.connected(); // restored → onReconnected(lastKnownSeq=5)

        // The reconnect runs all three cursor recovery RPCs, in order.
        List<ApiRequest> requests = http.requests;
        int n = requests.size();
        assertEquals("/rest/v1/rpc/get_messages_since", requests.get(n - 3).path);
        assertEquals("/rest/v1/rpc/get_message_statuses_since", requests.get(n - 2).path);
        assertEquals("/rest/v1/rpc/get_message_changes_since", requests.get(n - 1).path);
        // The insert cursor resumes exactly where Realtime last stopped.
        assertTrue(requests.get(n - 3).jsonBody.contains("\"p_after_seq\":5"));

        assertEquals(3, c.getMessages().size());
        assertEquals("m-7", c.getMessages().get(0).id);
        assertEquals("m-6", c.getMessages().get(1).id);
        assertEquals("m-5", c.getMessages().get(2).id);
    }

    // ---- 5b. first connect runs full recovery (closes REST→WS window) ----

    @Test
    public void firstConnectRunsFullRecoveryClosingRestToWsWindow() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        // The REST page already rendered the newest row the client knows (seq 5).
        http.responses.add(http.json(200, rowsJson(rowJson("m-5", 5, "five"))));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        assertEquals(1, c.getMessages().size());

        // While the WS join lagged: m-6/m-7 were inserted. Script the two other
        // passes to [] — the point is the recovery itself runs on FIRST connect.
        http.responses.add(http.json(200, rowsJson(rpcRow("m-6", 6, "six"), rpcRow("m-7", 7, "seven"))));
        http.responses.add(http.json(200, "[]")); // get_message_statuses_since
        http.responses.add(http.json(200, "[]")); // get_message_changes_since
        realtime.transport.connected(); // first connect → onConnected recovery

        assertEquals(3, c.getMessages().size()); // m-6, m-7 recovered
        assertEquals("m-7", c.getMessages().get(0).id);
        assertEquals("m-5", c.getMessages().get(2).id);

        // All three recovery RPCs fired on the FIRST connect (not just reconnects).
        List<ApiRequest> requests = http.requests;
        int n = requests.size();
        assertEquals("/rest/v1/rpc/get_messages_since", requests.get(n - 3).path);
        assertEquals("/rest/v1/rpc/get_message_statuses_since", requests.get(n - 2).path);
        assertEquals("/rest/v1/rpc/get_message_changes_since", requests.get(n - 1).path);
        // The insert cursor is anchored at the loaded page's newest seq, so the
        // REST → WS race window is closed.
        assertTrue(requests.get(n - 3).jsonBody.contains("\"p_after_seq\":5"));
    }

    @Test
    public void firstConnectRecoveryErrorsAreSilentBestEffort() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        // No canned responses for the recovery RPCs: they fail like a transient
        // network blip. The screen must stay on its current phase (never a
        // spurious AUTH/NETWORK error) and later realtime rows keep rendering.
        realtime.transport.connected();
        realtime.transport.frame(insert("m-1", CHAT, 1, "hello", null, "uuid-other"));

        assertEquals(1, c.getMessages().size());
        assertTrue(rec.phases.isEmpty() || !rec.phases.contains(Phase.AUTH_ERROR));
        assertTrue(rec.phases.isEmpty() || !rec.phases.contains(Phase.NETWORK_ERROR));
    }

    // ---- 5c. reconnect recovery covers statuses + edits/deletes ----

    @Test
    public void reconnectRecoversStatusesAndEditDeleteChanges() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected(); // first connect (no scripts → silent)
        realtime.transport.frame(insert("m-5", CHAT, 5, "original", null, "uuid-other"));
        assertEquals(1, c.getMessages().size());

        // While away: m-5 was read AND edited server-side; m-6 was read and then
        // soft-deleted. The statuses pass applies the read; the changes pass
        // carries the full row (status included) so the edit swap keeps it, and
        // m-6's tombstone drops the row.
        http.responses.add(http.json(200, "[]")); // since: no new INSERTs
        http.responses.add(http.json(200, rowsJson(statusRow("m-5", "read", "2026-08-16T09:00:00Z"))));
        http.responses.add(http.json(200, rowsJson(
                changeRow("m-5", 5, "edited-server", "2026-08-16T11:00:00Z", null,
                        "2026-08-16T11:00:00Z", "read"),
                changeRow("m-6", 6, "to-delete", null, "2026-08-16T12:00:00Z",
                        "2026-08-16T12:00:00Z", "sent"))));

        realtime.transport.disconnect(new IOException("drop"));
        realtime.scheduler.runAll();
        realtime.transport.connected(); // restored → onReconnected(lastKnownSeq=6)

        List<CreangerMessageUiModel> rows = c.getMessages();
        assertEquals(1, rows.size()); // only m-5 survives
        assertEquals("m-5", rows.get(0).id);
        assertEquals("edited-server", rows.get(0).content); // edit converged
        assertEquals(MessageStatus.READ, rows.get(0).status); // read state kept
    }

    // ---- 5d. recovery paginates until a short page ----

    @Test
    public void recoveryPaginatesUntilShortPage() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected(); // first connect (no scripts → silent)
        realtime.transport.frame(insert("m-5", CHAT, 5, "five", null, "uuid-other"));

        // Offline backlog of 102 INSERTs: page 1 is exactly full (100), page 2
        // short. The pass must continue past the first page, not stall on it.
        List<String> page1 = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            page1.add(rpcRow("m-" + (6 + i), 6 + i, "msg-" + (6 + i)));
        }
        http.responses.add(http.json(200, rowsJson(page1)));
        http.responses.add(http.json(200, rowsJson(rpcRow("m-106", 106, "msg-106"), rpcRow("m-107", 107, "msg-107"))));
        http.responses.add(http.json(200, "[]")); // statuses
        http.responses.add(http.json(200, "[]")); // changes

        realtime.transport.disconnect(new IOException("drop"));
        realtime.scheduler.runAll();
        realtime.transport.connected();

        assertEquals(103, c.getMessages().size()); // m-5 + 102 recovered

        // The insert cursor advanced past the first full page before page two.
        List<ApiRequest> since = new ArrayList<>();
        for (ApiRequest r : http.requests) {
            if ("/rest/v1/rpc/get_messages_since".equals(r.path)) {
                since.add(r);
            }
        }
        assertTrue("a full page must be followed by a continuation", since.size() >= 2);
        assertTrue("continuation cursor must be the last full page's newest seq",
                since.get(since.size() - 1).jsonBody.contains("\"p_after_seq\":105"));
    }

    // ---- 6. recovered + realtime rows dedup ----

    @Test
    public void recoveredRowsDedupAgainstRealtimeDelivered() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected();
        realtime.transport.frame(insert("m-5", CHAT, 5, "five", null, "uuid-other"));
        realtime.transport.frame(insert("m-6", CHAT, 6, "six", null, "uuid-other"));
        assertEquals(2, c.getMessages().size());

        // Recovery re-fetches m-6 (already delivered over Realtime) plus new m-7.
        http.responses.add(http.json(200, rowsJson(rpcRow("m-6", 6, "six"), rpcRow("m-7", 7, "seven"))));
        realtime.transport.disconnect(new IOException("drop"));
        realtime.scheduler.runAll();
        realtime.transport.connected();

        assertEquals(3, c.getMessages().size());
        assertEquals("m-7", c.getMessages().get(0).id);
        assertEquals("m-6", c.getMessages().get(1).id);
        assertEquals("m-5", c.getMessages().get(2).id);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (CreangerMessageUiModel m : c.getMessages()) {
            ids.add(m.id);
        }
        assertEquals(3, ids.size()); // m-6 present exactly once
    }

    // ---- 7. wrong-chat event ignored ----

    @Test
    public void wrongChatFrameIsIgnored() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected();
        int snapshots = rec.messageSnapshots.size();

        realtime.transport.frame(insert("m-9", "chat-OTHER", 9, "nope", null, "uuid-other"));

        assertEquals(0, c.getMessages().size());
        assertEquals(snapshots, rec.messageSnapshots.size());
    }

    // ---- 7b. realtime edit propagation ----

    @Test
    public void realtimeEditPropagatesToDisplayAndDuplicateEditIsCollapsed() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected();
        realtime.transport.frame(insert("m-5", CHAT, 5, "original", null, "uuid-other"));
        assertEquals(1, c.getMessages().size());
        assertEquals("original", c.getMessages().get(0).content);
        int snapshots = rec.messageSnapshots.size();

        // A realtime edit updates the displayed row in place.
        realtime.transport.frame(updateEdit("m-5", CHAT, "edited-v1"));
        assertEquals(1, c.getMessages().size());
        assertEquals("edited-v1", c.getMessages().get(0).content);
        assertEquals(snapshots + 1, rec.messageSnapshots.size());

        // The exact same edit re-delivered must not re-render the display.
        realtime.transport.frame(updateEdit("m-5", CHAT, "edited-v1"));
        assertEquals(snapshots + 1, rec.messageSnapshots.size());

        // A genuine second edit still propagates.
        realtime.transport.frame(updateEdit("m-5", CHAT, "edited-v2"));
        assertEquals("edited-v2", c.getMessages().get(0).content);
        assertEquals(snapshots + 2, rec.messageSnapshots.size());
    }

    // ---- 7c. realtime soft + hard delete propagation ----

    @Test
    public void realtimeSoftDeleteTombstoneRemovesRowOnce() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected();
        realtime.transport.frame(insert("m-5", CHAT, 5, "bye", null, "uuid-other"));
        assertEquals(1, c.getMessages().size());
        int snapshots = rec.messageSnapshots.size();

        realtime.transport.frame(tombstone("m-5", CHAT));
        assertTrue(c.getMessages().isEmpty());
        assertEquals(snapshots + 1, rec.messageSnapshots.size());

        // Re-delivered tombstone: no further render, row stays gone.
        realtime.transport.frame(tombstone("m-5", CHAT));
        assertTrue(c.getMessages().isEmpty());
        assertEquals(snapshots + 1, rec.messageSnapshots.size());
    }

    @Test
    public void realtimeHardDeleteRemovesRowOnce() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected();
        realtime.transport.frame(insert("m-6", CHAT, 6, "gone", null, "uuid-other"));
        assertEquals(1, c.getMessages().size());
        int snapshots = rec.messageSnapshots.size();

        realtime.transport.frame(hardDelete("m-6", CHAT));
        assertTrue(c.getMessages().isEmpty());
        assertEquals(snapshots + 1, rec.messageSnapshots.size());

        realtime.transport.frame(hardDelete("m-6", CHAT)); // Realtime re-delivery
        assertTrue(c.getMessages().isEmpty());
        assertEquals(snapshots + 1, rec.messageSnapshots.size());
    }

    @Test
    public void wrongChatEditAndDeleteAreIgnored() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, rowsJson(rowJson("m-1", 1, "keep"))));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected();
        int snapshots = rec.messageSnapshots.size();
        assertEquals(1, c.getMessages().size());

        realtime.transport.frame(updateEdit("m-1", "chat-OTHER", "hijacked"));
        realtime.transport.frame(tombstone("m-1", "chat-OTHER"));
        realtime.transport.frame(hardDelete("m-1", "chat-OTHER"));

        assertEquals("keep", c.getMessages().get(0).content);
        assertEquals(1, c.getMessages().size());
        assertEquals(snapshots, rec.messageSnapshots.size());
    }

    // ---- 7d. realtime edit/delete recovery after reconnect ----

    @Test
    public void reconnectAfterEditAndDeleteConvergesDisplay() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected();
        realtime.transport.frame(insert("m-5", CHAT, 5, "original", null, "uuid-other"));
        realtime.transport.frame(insert("m-6", CHAT, 6, "to-delete", null, "uuid-other"));
        assertEquals(2, c.getMessages().size());

        // While away: m-5 was edited, m-6 soft-deleted server-side, and m-7
        // arrived. get_messages_since returns all three; recovery must converge
        // the cache: m-5 converges to the edit, m-6's tombstone drops it, and
        // m-7 is brand new.
        String softDeleted = rpcRow("m-6", 6, "to-delete")
                .replace("\"deleted_at\":null", "\"deleted_at\":\"2026-08-16T12:00:00Z\"");
        http.responses.add(http.json(200, rowsJson(
                rpcRow("m-5", 5, "edited-server"),
                softDeleted,
                rpcRow("m-7", 7, "brand-new"))));

        realtime.transport.disconnect(new IOException("drop"));
        realtime.scheduler.runAll();
        realtime.transport.connected();

        List<CreangerMessageUiModel> rows = c.getMessages();
        assertEquals(2, rows.size());
        assertEquals("brand-new", rows.get(0).content); // m-7, newest
        assertEquals("m-7", rows.get(0).id);
        assertEquals("edited-server", rows.get(1).content); // m-5 converges
        assertEquals("m-5", rows.get(1).id);
        // m-6 is gone: the recovery tombstone dropped it.
        for (CreangerMessageUiModel m : rows) {
            assertFalse("m-6".equals(m.id));
        }
    }

    // ---- 8. logout unsubscribes ----

    @Test
    public void closeUnsubscribesRealtimeAndClears() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        realtime.transport.connected();
        realtime.transport.frame(insert("m-1", CHAT, 1, "hello", null, "uuid-other"));
        assertEquals(1, c.getMessages().size());

        c.close();

        // subscribe() already tears down its own socket during the first
        // subscribe; close() tears it down again — the point is that closing
        // the controller unsubscribes Realtime and nothing reconnects.
        assertEquals(2, realtime.transport.closeCount);
        assertTrue(c.getMessages().isEmpty());
        assertEquals(Phase.LOADING, c.getPhase());
        assertEquals(1, realtime.transport.connectCount); // no reconnect after close
    }

    // ---- 9. account switch isolates realtime state ----

    @Test
    public void accountSwitchIsolatesRealtimeAndCache() {
        // Account A: open chat, receive one realtime row.
        HttpScriptedTransport httpA = new HttpScriptedTransport();
        InMemoryStore storeA = new InMemoryStore();
        httpA.responses.add(httpA.json(200, "[]"));
        Harness realtimeA = new Harness();
        Recorder recA = new Recorder();
        CreangerChatController cA = controller(httpA, storeA, realtimeA, recA);
        cA.refresh();
        realtimeA.transport.connected();
        realtimeA.transport.frame(insert("m-5", CHAT, 5, "five", null, "uuid-other"));
        assertEquals(1, cA.getMessages().size());

        // Switch accounts: A is logged out (unsubscribed), B opens the same chat fresh.
        cA.close();
        HttpScriptedTransport httpB = new HttpScriptedTransport();
        InMemoryStore storeB = new InMemoryStore();
        httpB.responses.add(httpB.json(200, "[]"));
        Harness realtimeB = new Harness();
        Recorder recB = new Recorder();
        CreangerChatController cB = controller(httpB, storeB, realtimeB, recB);
        cB.refresh();

        assertTrue(cB.getMessages().isEmpty()); // A's rows never leak into B
        assertEquals(2, realtimeA.transport.closeCount); // A's socket closed on switch (subscribe + close)

        realtimeB.transport.connected();
        realtimeB.transport.frame(insert("m-5", CHAT, 5, "five", null, "uuid-other"));
        assertEquals(1, cB.getMessages().size()); // same frame is NEW for B (fresh dedup state)
    }

    // ---- 10. repeated open/resume keeps a single subscription ----

    @Test
    public void repeatedOpenDoesNotCreateDuplicateSubscriptions() {
        HttpScriptedTransport http = new HttpScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        http.responses.add(http.json(200, "[]"));
        Harness realtime = new Harness();
        Recorder rec = new Recorder();
        CreangerChatController c = controller(http, store, realtime, rec);

        c.refresh();
        assertEquals(1, realtime.transport.connectCount);
        assertEquals(1, realtime.transport.closeCount); // subscribe()'s own initial teardown

        // Activity recreated/resumed → refresh again; subscription must be idempotent.
        http.responses.add(http.json(200, "[]"));
        c.refresh();

        assertEquals(1, realtime.transport.connectCount);
        assertEquals(1, realtime.transport.closeCount); // already-active subscription is not re-subscribed
        assertTrue(realtime.scheduler.delays.isEmpty());
    }
}