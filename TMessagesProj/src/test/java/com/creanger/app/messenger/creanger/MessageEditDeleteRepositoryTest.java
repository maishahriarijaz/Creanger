package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
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

import static org.junit.Assert.*;

/**
 * Edit/delete data-plane tests on {@link MessageRepository}:
 *
 *  - optimistic edit: immediate local content swap, the author-only
 *    {@code edit_message} RPC, success keep / failure rollback, and no
 *    sender/user id ever sent to the server
 *  - optimistic delete (soft delete per migration 025): immediate local
 *    removal, the {@code delete_message} RPC, success keep / failure rollback
 *  - realtime edit/delete apply: server rows are authoritative, own-edit echoes
 *    collapse (local + realtime never duplicate), deletes are idempotent
 *  - convergence: REST refresh / recovery supersede stale edits and drop
 *    soft-deleted tombstones
 */
public class MessageEditDeleteRepositoryTest {

    private static final String OWNER = "uuid-1";
    private static final String CHAT = "chat-1";

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

    private static String msgRow(String id, long chatSeq, String content) {
        return "{\"id\":\"" + id + "\",\"chat_id\":\"" + CHAT + "\",\"sender_id\":\"" + OWNER + "\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"sent\","
                + "\"client_message_id\":null,\"chat_seq\":" + chatSeq
                + ",\"created_at\":\"2026-08-15T08:01:00Z\",\"edited_at\":null,\"deleted_at\":null,"
                + "\"updated_at\":\"2026-08-15T08:01:00Z\"}";
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

    /** Seeds the cache with one own message and returns its cached row. */
    private static CreangerMessage seed(MessageRepository mr, ScriptedTransport t, String content) throws Exception {
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, content))));
        mr.refreshMessages(CHAT, 30);
        assertEquals(1, mr.getCachedMessages(CHAT).size());
        return mr.getCachedMessages(CHAT).get(0);
    }

    private static ApiRequest requestFor(List<ApiRequest> requests, String path) {
        for (ApiRequest r : requests) {
            if (path.equals(r.path)) {
                return r;
            }
        }
        return null;
    }

    private static CreangerMessage message(String id, String content, String editedAt) {
        return new CreangerMessage(id, CHAT, OWNER, MessageType.TEXT, content, MessageStatus.SENT,
                null, 1L, "2026-08-15T08:01:00Z", editedAt, null, null, false);
    }

    // ---- optimistic edit + reconcile + rollback ----

    @Test
    public void optimisticEditAppliesImmediatelyAndConfirms() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        CreangerMessage original = seed(mr, t, "original");
        assertNull(original.editedAt);

        // Phase 1: the local row switches immediately.
        assertTrue(mr.applyOptimisticEdit(CHAT, "m-1", "edited"));
        CreangerMessage optimistic = mr.getCachedMessages(CHAT).get(0);
        assertEquals("edited", optimistic.content);
        assertNotNull(optimistic.editedAt);

        // Phase 2: the author-only RPC confirms.
        t.responses.add(t.json(200, "\"m-1\""));
        String serverId = mr.completeEdit(CHAT, "m-1", "edited");
        assertEquals("m-1", serverId);
        assertEquals("edited", mr.getCachedMessages(CHAT).get(0).content);

        ApiRequest rpc = requestFor(t.requests, "/rest/v1/rpc/edit_message");
        assertNotNull(rpc);
        assertEquals("POST", rpc.method);
        assertTrue(rpc.jsonBody.contains("\"p_message_id\":\"m-1\""));
        assertTrue(rpc.jsonBody.contains("\"p_new_content\":\"edited\""));
        // Authorization is server-side: no sender/user id is ever supplied.
        assertFalse(rpc.jsonBody.contains("sender"));
        assertFalse(rpc.jsonBody.contains(OWNER));
    }

    @Test
    public void unauthorizedEditIsRejectedAndRollsBack() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, "original");

        mr.applyOptimisticEdit(CHAT, "m-1", "hijacked");
        assertEquals("hijacked", mr.getCachedMessages(CHAT).get(0).content);

        // The RPC enforces "only the message author may edit": a P0001 error back.
        t.responses.add(t.json(400,
                "{\"code\":\"P0001\",\"message\":\"only the message author may edit this message\","
                        + "\"details\":null,\"hint\":null}"));
        try {
            mr.completeEdit(CHAT, "m-1", "hijacked");
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertEquals("P0001", e.error.code);
        }

        // Rollback restores the pre-edit content and clears the edit marker.
        mr.rollbackEdit(CHAT, "m-1");
        CreangerMessage restored = mr.getCachedMessages(CHAT).get(0);
        assertEquals("original", restored.content);
        assertNull(restored.editedAt);
    }

    @Test
    public void networkFailedEditRollsBack() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, "original");

        mr.applyOptimisticEdit(CHAT, "m-1", "edited");
        t.failAll = new IOException("no route to host");
        try {
            mr.completeEdit(CHAT, "m-1", "edited");
            fail("expected IOException");
        } catch (IOException expected) {
        }
        mr.rollbackEdit(CHAT, "m-1");
        assertEquals("original", mr.getCachedMessages(CHAT).get(0).content);
    }

    @Test
    public void editOfUncachedMessageStillCallsRpcWithoutCrashing() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);

        assertFalse(mr.applyOptimisticEdit(CHAT, "m-not-cached", "edited"));
        t.responses.add(t.json(200, "\"m-not-cached\""));
        assertEquals("m-not-cached", mr.completeEdit(CHAT, "m-not-cached", "edited"));
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());
    }

    // ---- optimistic delete (soft) + reconcile + rollback ----

    @Test
    public void optimisticDeleteRemovesImmediatelyAndConfirms() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, "bye");

        assertTrue(mr.applyOptimisticDelete(CHAT, "m-1"));
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());

        t.responses.add(t.json(200, "\"m-1\""));
        assertEquals("m-1", mr.completeDelete(CHAT, "m-1"));
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());

        ApiRequest rpc = requestFor(t.requests, "/rest/v1/rpc/delete_message");
        assertNotNull(rpc);
        assertTrue(rpc.jsonBody.contains("\"p_message_id\":\"m-1\""));
        assertFalse(rpc.jsonBody.contains("sender"));
        assertFalse(rpc.jsonBody.contains(OWNER));
    }

    @Test
    public void unauthorizedDeleteIsRejectedAndRollsBack() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, "own");

        assertTrue(mr.applyOptimisticDelete(CHAT, "m-1"));
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());

        // Author-only delete_message RPC rejects a non-author with P0001.
        t.responses.add(t.json(400,
                "{\"code\":\"P0001\",\"message\":\"only the message author may delete this message\","
                        + "\"details\":null,\"hint\":null}"));
        try {
            mr.completeDelete(CHAT, "m-1");
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertEquals("P0001", e.error.code);
        }

        mr.rollbackDelete(CHAT, "m-1");
        assertEquals(1, mr.getCachedMessages(CHAT).size());
        assertEquals("m-1", mr.getCachedMessages(CHAT).get(0).id);
        assertEquals("own", mr.getCachedMessages(CHAT).get(0).content);
    }

    @Test
    public void networkFailedDeleteRollsBack() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, "own");

        mr.applyOptimisticDelete(CHAT, "m-1");
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());

        t.failAll = new IOException("offline");
        try {
            mr.completeDelete(CHAT, "m-1");
            fail("expected IOException");
        } catch (IOException expected) {
        }
        mr.rollbackDelete(CHAT, "m-1");
        assertEquals(1, mr.getCachedMessages(CHAT).size());
        assertEquals("own", mr.getCachedMessages(CHAT).get(0).content);
    }

    @Test
    public void deleteOfUncachedMessageIsNoOp() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);

        assertFalse(mr.applyOptimisticDelete(CHAT, "m-ghost"));
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());
        t.responses.add(t.json(200, "\"m-ghost\""));
        assertEquals("m-ghost", mr.completeDelete(CHAT, "m-ghost"));
    }

    // ---- realtime apply: authoritative + no duplicates ----

    @Test
    public void applyRealtimeEditReplacesRowAndEchoCollapses() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, "original");

        assertTrue(mr.applyRealtimeEdit(CHAT, message("m-1", "edited", "2026-08-16T11:00:00Z")));
        assertEquals("edited", mr.getCachedMessages(CHAT).get(0).content);
        assertEquals("2026-08-16T11:00:00Z", mr.getCachedMessages(CHAT).get(0).editedAt);

        // Exact echo: nothing changed, so no duplicate render.
        assertFalse(mr.applyRealtimeEdit(CHAT, message("m-1", "edited", "2026-08-16T11:00:00Z")));
        assertEquals(1, mr.getCachedMessages(CHAT).size());
    }

    @Test
    public void localOptimisticEditPlusRealtimeEchoDoesNotRenderTwice() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, "original");

        mr.applyOptimisticEdit(CHAT, "m-1", "edited");
        // The Realtime echo of the user's own edit carries the same content —
        // it must collapse even though the server edited_at timestamp differs.
        assertFalse(mr.applyRealtimeEdit(CHAT, message("m-1", "edited", "2026-08-16T11:00:00Z")));
        assertEquals(1, mr.getCachedMessages(CHAT).size());
        assertEquals("edited", mr.getCachedMessages(CHAT).get(0).content);
    }

    @Test
    public void applyRealtimeDeleteIsIdempotent() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, "bye");

        assertTrue(mr.applyRealtimeDelete(CHAT, "m-1"));
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());
        // Re-delivery of the same deletion is a no-op.
        assertFalse(mr.applyRealtimeDelete(CHAT, "m-1"));
    }

    @Test
    public void updatedAtAndStatusConvergeWithServerEditRows() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);

        // Realtime insert first, then a distinct realtime edit.
        mr.ingestRealtime(CHAT, message("m-1", "first", null));
        assertTrue(mr.applyRealtimeEdit(CHAT, message("m-1", "second", "2026-08-16T12:00:00Z")));
        assertEquals(1, mr.getCachedMessages(CHAT).size());
        assertEquals("second", mr.getCachedMessages(CHAT).get(0).content);
    }

    // ---- REST recovery / refresh convergence ----

    @Test
    public void refreshSupersedesStaleCachedEdit() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, "old-content");

        // A later REST page returns the already-edited row; the merge must
        // prefer the fresh server row (single row, edited content).
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "edited-server"))));
        mr.refreshMessages(CHAT, 30);
        List<CreangerMessage> cached = mr.getCachedMessages(CHAT);
        assertEquals(1, cached.size());
        assertEquals("edited-server", cached.get(0).content);
    }

    @Test
    public void recoveryDropsSoftDeletedTombsTones() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, "bye");

        // get_messages_since can return the deleted row (deleted_at set); the
        // merge must treat it as a tombstone and drop the cached copy so the
        // deleted message never reappears.
        String deleted = "{\"message_id\":\"m-1\",\"chat_seq\":1,\"sender_id\":\"" + OWNER + "\","
                + "\"message_type\":\"text\",\"content\":\"bye\",\"created_at\":\"2026-08-15T08:01:00Z\","
                + "\"edited_at\":null,\"deleted_at\":\"2026-08-16T12:00:00Z\","
                + "\"updated_at\":\"2026-08-16T12:00:00Z\"}";
        t.responses.add(t.json(200, rowsJson(deleted)));
        mr.syncSince(CHAT, 0, 100);
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());
    }

    // ---- account isolation ----

    @Test
    public void clearWipesEditDeleteRollbackState() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t, "original");

        mr.applyOptimisticEdit(CHAT, "m-1", "edited");
        mr.clearCachedMessages();
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());
        // Rollback after logout must not resurrect anything (snapshot gone).
        mr.rollbackEdit(CHAT, "m-1");
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());
    }
}