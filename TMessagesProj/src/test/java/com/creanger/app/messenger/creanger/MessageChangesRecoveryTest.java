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
 * Edit/delete change recovery (migration 028) on {@link MessageRepository}:
 *
 *  - edits and soft-deletes never bump {@code chat_seq}, so after a Realtime
 *    reconnect they are recovered via {@code get_message_changes_since}, which
 *    returns every row with {@code edited_at}/{@code deleted_at} set that
 *    changed after a per-chat {@code updated_at} watermark
 *  - an edit row replaces the cached row by id; a tombstone row drops it
 *  - rows outside the loaded window are skipped but the watermark still
 *    advances (their content converges on the next refresh)
 *  - the watermark resets on logout / account switch so a fresh session
 *    recovers everything
 */
public class MessageChangesRecoveryTest {

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
        return new MessageRepository(authenticatedEngine(t, store), new CreangerChatApiClient(t));
    }

    /** A messages row as returned by a refresh (before any edit). */
    private static String msgRow(String id, long chatSeq, String content) {
        return "{\"id\":\"" + id + "\",\"chat_id\":\"" + CHAT + "\",\"sender_id\":\"" + SENDER + "\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"sent\","
                + "\"client_message_id\":null,\"chat_seq\":" + chatSeq
                + ",\"created_at\":\"2026-08-15T08:01:00Z\",\"edited_at\":null,\"deleted_at\":null,"
                + "\"updated_at\":\"2026-08-15T08:01:00Z\"}";
    }

    /** A change-recovery row keyed by {@code message_id}, full projection. */
    private static String changeRow(String id, long chatSeq, String content, String editedAt,
                                    String deletedAt, String updatedAt) {
        String edited = editedAt != null ? "\"" + editedAt + "\"" : "null";
        String deleted = deletedAt != null ? "\"" + deletedAt + "\"" : "null";
        return "{\"message_id\":\"" + id + "\",\"chat_id\":\"" + CHAT + "\",\"chat_seq\":" + chatSeq + ","
                + "\"sender_id\":\"" + SENDER + "\",\"message_type\":\"text\",\"content\":\"" + content + "\","
                + "\"status\":\"sent\",\"client_message_id\":null,\"reply_to_message_id\":null,"
                + "\"created_at\":\"2026-08-15T08:01:00Z\",\"edited_at\":" + edited
                + ",\"deleted_at\":" + deleted + ",\"updated_at\":\"" + updatedAt + "\"}";
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

    /** Seeds the cache with one incoming message (content "hi"). */
    private static void seed(MessageRepository mr, ScriptedTransport t) throws Exception {
        t.responses.add(t.json(200, rowsJson(msgRow("m-1", 1, "hi"))));
        mr.refreshMessages(CHAT, 30);
        assertEquals(1, mr.getCachedMessages(CHAT).size());
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

    // ---- edit recovery ----

    @Test
    public void recoverMessageChangesAppliesEditToKnownRow() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t);

        // m-1 was edited while Realtime was down: edited_at set, content advanced.
        t.responses.add(t.json(200, rowsJson(changeRow("m-1", 1, "edited", "2026-08-16T11:00:00Z",
                null, "2026-08-16T11:00:00Z"))));
        List<CreangerMessage> changes = mr.recoverMessageChanges(CHAT, null, 100);

        assertEquals(1, changes.size());
        assertEquals("m-1", changes.get(0).id);
        assertEquals("edited", mr.getCachedMessages(CHAT).get(0).content);
        assertEquals("2026-08-16T11:00:00Z", mr.getCachedMessages(CHAT).get(0).editedAt);
        List<ApiRequest> rpcs = requestsFor(t.requests, "/rest/v1/rpc/get_message_changes_since");
        assertEquals(1, rpcs.size());

        // A null watermark on a fresh session means "everything".
        assertTrue(rpcs.get(0).jsonBody.contains("\"p_after_updated_at\":null"));
    }

    @Test
    public void recoverMessageChangesResumesFromWatermarkAcrossCalls() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t);

        t.responses.add(t.json(200, rowsJson(changeRow("m-1", 1, "edited", "2026-08-16T11:00:00Z",
                null, "2026-08-16T11:00:00Z"))));
        mr.recoverMessageChanges(CHAT, null, 100);
        assertEquals("edited", mr.getCachedMessages(CHAT).get(0).content);

        // Consecutive recovery resumes from the applied watermark.
        t.responses.add(t.json(200, "[]"));
        assertTrue(mr.recoverMessageChanges(CHAT, null, 100).isEmpty());

        List<ApiRequest> rpcs = requestsFor(t.requests, "/rest/v1/rpc/get_message_changes_since");
        assertEquals(2, rpcs.size());
        assertTrue("second recovery must resume from the watermark",
                rpcs.get(1).jsonBody.contains("\"p_after_updated_at\":\"2026-08-16T11:00:00Z\""));
    }

    // ---- delete recovery ----

    @Test
    public void recoverMessageChangesDropsSoftDeletedRow() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t);

        // m-1 was soft-deleted while Realtime was down: tombstone row.
        t.responses.add(t.json(200, rowsJson(changeRow("m-1", 1, "hi", null,
                "2026-08-16T12:00:00Z", "2026-08-16T12:00:00Z"))));
        List<CreangerMessage> changes = mr.recoverMessageChanges(CHAT, null, 100);

        assertEquals(1, changes.size());
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());
    }

    @Test
    public void recoverMessageChangesEditThenDeleteConvergesOnce() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t);

        // Recovery re-fetches the same row twice: an edited state and a later
        // tombstone. The final state must be the delete (no resurrection).
        t.responses.add(t.json(200, rowsJson(changeRow("m-1", 1, "v2", "2026-08-16T11:00:00Z",
                null, "2026-08-16T11:00:00Z"))));
        mr.recoverMessageChanges(CHAT, null, 100);
        assertEquals("v2", mr.getCachedMessages(CHAT).get(0).content);

        t.responses.add(t.json(200, rowsJson(changeRow("m-1", 1, "v2", "2026-08-16T11:00:00Z",
                "2026-08-16T12:00:00Z", "2026-08-16T12:00:00Z"))));
        mr.recoverMessageChanges(CHAT, null, 100);
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());
    }

    // ---- rows outside the loaded window ----

    @Test
    public void recoverMessageChangesSkipsUnknownRowButAdvancesWatermark() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t);

        // Only m-999 changed while offline; it is not in the loaded window, so
        // the recovery must not fabricate a row — its content converges on the
        // next refresh. The watermark must still advance so it is not re-fetched.
        t.responses.add(t.json(200, rowsJson(changeRow("m-999", 999, "far", "2026-08-16T11:00:00Z",
                null, "2026-08-16T11:00:00Z"))));
        List<CreangerMessage> changes = mr.recoverMessageChanges(CHAT, null, 100);
        assertEquals(1, changes.size());

        List<CreangerMessage> cached = mr.getCachedMessages(CHAT);
        assertEquals(1, cached.size());
        assertEquals("m-1", cached.get(0).id);
        assertEquals("hi", cached.get(0).content);

        t.responses.add(t.json(200, "[]"));
        mr.recoverMessageChanges(CHAT, null, 100);
        List<ApiRequest> rpcs = requestsFor(t.requests, "/rest/v1/rpc/get_message_changes_since");
        assertEquals(2, rpcs.size());
        assertTrue(rpcs.get(1).jsonBody.contains("\"p_after_updated_at\":\"2026-08-16T11:00:00Z\""));
    }

    // ---- watermark lifecycle ----

    @Test
    public void clearCachedMessagesResetsChangeWatermark() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t);

        t.responses.add(t.json(200, rowsJson(changeRow("m-1", 1, "edited", "2026-08-16T11:00:00Z",
                null, "2026-08-16T11:00:00Z"))));
        mr.recoverMessageChanges(CHAT, null, 100);
        assertEquals("edited", mr.getCachedMessages(CHAT).get(0).content);

        // Logout: everything is dropped, including the watermark.
        mr.clearCachedMessages();

        t.responses.add(t.json(200, "[]"));
        mr.recoverMessageChanges(CHAT, null, 100);
        List<ApiRequest> rpcs = requestsFor(t.requests, "/rest/v1/rpc/get_message_changes_since");
        assertEquals(2, rpcs.size());
        assertTrue("a fresh session recovers everything again",
                rpcs.get(1).jsonBody.contains("\"p_after_updated_at\":null"));
    }

    @Test
    public void recoverMessageChangesUnauthorizedSurfacesTypedError() throws Exception {
        InMemoryStore store = new InMemoryStore();
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, store);
        seed(mr, t);

        t.responses.add(t.json(401, "{\"message\":\"not authorized\",\"code\":\"UNAUTHORIZED\"}"));
        try {
            mr.recoverMessageChanges(CHAT, null, 100);
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertTrue(e.is(com.creanger.app.messenger.creanger.model.ApiError.UNAUTHORIZED));
        }
        assertEquals("hi", mr.getCachedMessages(CHAT).get(0).content);
    }
}