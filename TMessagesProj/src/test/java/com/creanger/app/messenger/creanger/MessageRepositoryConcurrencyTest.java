package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.data.MessageRepository;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the release-audit concurrency and correctness fixes:
 *
 *  - concurrent realtime ingest from two threads must not lose updates
 *    (the get->merge->put cache sequence is now atomic)
 *  - clear() during an in-flight send must not let the send confirmation
 *    resurrect cleared rows (logout vs in-flight apply race)
 *  - a realtime/recovered edit row (which carries no attachments) must keep
 *    the held attachment metadata instead of blanking a media bubble
 *  - markMessageStatus must NOT stamp the client clock into the server-domain
 *    status recovery cursor (a skewed clock would skip future recoveries)
 */
public class MessageRepositoryConcurrencyTest {

    private static final String CHAT = "chat-1";

    private static final class ScriptedTransport implements CreangerHttpTransport {
        final List<TransportResponse> responses = new ArrayList<>();
        final List<ApiRequest> requests = new ArrayList<>();
        volatile IOException failAll = null;
        volatile CountDownLatch holdRequests = null;

        @Override
        public TransportResponse execute(ApiRequest request) throws IOException {
            requests.add(request);
            CountDownLatch hold = holdRequests;
            if (hold != null) {
                try {
                    hold.await();
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

    private static AuthSession freshSession(String access, String refresh, CreangerUser user) {
        return new AuthSession(access, refresh, user, System.currentTimeMillis());
    }

    private static CreangerUser user(String id, String username, String email) {
        return new CreangerUser(id, username, email, true, null, null, null);
    }

    private static MessageRepository repo(ScriptedTransport t, InMemoryStore store) {
        store.store(freshSession("acc-live", "ref", user("uuid-1", "alice", "a@x.com")));
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), store);
        return new MessageRepository(engine, new CreangerChatApiClient(t));
    }

    private static CreangerMessage serverRow(String id, long chatSeq) {
        return new CreangerMessage(id, CHAT, "uuid-2", "text", "body-" + id,
                MessageStatus.SENT, null, chatSeq, "2026-08-15T08:01:00Z", null, null,
                "2026-08-15T08:01:00Z", null, false, null);
    }

    private static MediaAttachment mediaAtt(String mediaId, String storageKey) {
        return new MediaAttachment(null, null, mediaId, 0, null, "cloudinary", storageKey,
                "https://pub.example/" + storageKey, null, "image/png", 12345, "abc123",
                800, 600, null, null, null, null, null, null, "2026-08-15T08:01:00Z");
    }

    // ---- race: concurrent cache read-modify-write ----

    @Test
    public void concurrentIngestsDoNotLoseUpdates() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, new InMemoryStore());

        final int perThread = 250;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(2);
        final Atomic<Throwable> failure = new Atomic<>();

        Thread a = new Thread(() -> runIngests(mr, start, done, failure, 0, perThread));
        Thread b = new Thread(() -> runIngests(mr, start, done, failure, perThread, perThread * 2));
        a.start();
        b.start();
        start.countDown();
        assertTrue("ingest threads did not finish", done.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError("worker failed", failure.get());
        }

        List<CreangerMessage> cached = mr.getCachedMessages(CHAT);
        assertEquals("every ingested row must survive concurrent applies", perThread * 2, cached.size());
    }

    private static void runIngests(MessageRepository mr, CountDownLatch start,
                                   CountDownLatch done, Atomic<Throwable> failure,
                                   int from, int to) {
        try {
            start.await();
            for (int i = from; i < to; i++) {
                mr.ingestRealtime(CHAT, serverRow("m-" + i, i + 1));
            }
        } catch (Throwable e) {
            failure.set(e);
        } finally {
            done.countDown();
        }
    }

    private static final class Atomic<T> {
        volatile T value;

        void set(T v) {
            value = v;
        }

        T get() {
            return value;
        }
    }

    // ---- race: clear() vs in-flight send confirmation ----

    @Test
    public void clearDuringInFlightSendDoesNotResurrectRows() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        InMemoryStore store = new InMemoryStore();
        MessageRepository mr = repo(t, store);

        final CountDownLatch releaseRpc = new CountDownLatch(1);
        t.holdRequests = releaseRpc;
        t.responses.add(t.json(200, "\"srv-inflight\""));

        final CountDownLatch senderDone = new CountDownLatch(1);
        final Atomic<Throwable> failure = new Atomic<>();
        Thread sender = new Thread(() -> {
            try {
                mr.sendTextMessage(CHAT, "cm-1", "hello");
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                senderDone.countDown();
            }
        });
        sender.start();

        // Wait until the optimistic pending row entered the cache...
        long deadline = System.currentTimeMillis() + 5000;
        while (mr.getCachedMessages(CHAT).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertFalse("optimistic row should be cached before the RPC completes",
                mr.getCachedMessages(CHAT).isEmpty());

        // ...then log out while the RPC is still blocked on the transport.
        mr.clearCachedMessages();
        assertTrue(mr.getCachedMessages(CHAT).isEmpty());

        releaseRpc.countDown();
        assertTrue("sender did not finish", senderDone.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError("sender failed", failure.get());
        }

        assertEquals("confirmed row must not resurrect after clear()", 0, mr.getCachedMessages(CHAT).size());
    }

    // ---- media flash: edit rows carry no attachments ----

    @Test
    public void realtimeEditPreservesHeldAttachments() {
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, new InMemoryStore());

        List<MediaAttachment> atts = Collections.singletonList(mediaAtt("media-1", "img/x"));
        CreangerMessage mediaRow = new CreangerMessage("m-img", CHAT, "uuid-2", "image", "caption",
                MessageStatus.SENT, null, 1L, "2026-08-15T08:01:00Z", null, null,
                "2026-08-15T08:01:00Z", null, false, atts);
        mr.ingestRealtime(CHAT, mediaRow);

        // An edit frame / 028 recovery projection carries NO attachments.
        CreangerMessage editedNoAtts = new CreangerMessage("m-img", CHAT, "uuid-2", "image", "new caption",
                MessageStatus.SENT, null, 1L, "2026-08-15T08:01:00Z", "2026-08-15T09:00:00Z", null,
                "2026-08-15T09:00:00Z", null, false, null);
        assertTrue(mr.applyRealtimeEdit(CHAT, editedNoAtts));

        List<CreangerMessage> cached = mr.getCachedMessages(CHAT);
        assertEquals(1, cached.size());
        assertEquals("new caption", cached.get(0).content);
        assertFalse("held metadata must survive an attachment-less edit row",
                cached.get(0).attachments.isEmpty());
        assertEquals("img/x", cached.get(0).attachments.get(0).storageKey);
    }

    // ---- clock skew: local ack must not poison the server-domain cursor ----

    @Test
    public void markMessageStatusDoesNotAdvanceRecoveryCursorWithClientClock() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        MessageRepository mr = repo(t, new InMemoryStore());

        mr.ingestRealtime(CHAT, serverRow("m-1", 1));

        // Recipient marks the incoming message delivered (mark_message_status RPC).
        t.responses.add(t.json(200, "\"m-1\""));
        mr.markMessageStatus(CHAT, "m-1", "delivered");
        assertEquals(MessageStatus.DELIVERED, mr.getCachedMessages(CHAT).get(0).status);

        // A later reconnect recovery must resume with a NULL cursor ("everything")
        // rather than a client-clock timestamp that could skip server rows.
        t.responses.add(t.json(200, "[]"));
        mr.recoverMessageStatuses(CHAT, null, 50);

        boolean foundRecovery = false;
        for (ApiRequest r : t.requests) {
            if ("/rest/v1/rpc/get_message_statuses_since".equals(r.path)) {
                foundRecovery = true;
                assertTrue("cursor must stay null after a local-only ack, got: " + r.jsonBody,
                        r.jsonBody.contains("\"p_after_updated_at\":null"));
            }
        }
        assertTrue("recovery request expected", foundRecovery);

        // And the watermark was not silently set from the client clock either:
        // a recovery seeded by the caller-provided cursor still reaches the RPC.
        t.responses.add(t.json(200, "[]"));
        mr.recoverMessageStatuses(CHAT, "2026-08-20T00:00:00Z", 50);
        int recoveryCalls = 0;
        for (ApiRequest r : t.requests) {
            if ("/rest/v1/rpc/get_message_statuses_since".equals(r.path)) {
                recoveryCalls++;
            }
        }
        assertEquals(2, recoveryCalls);
    }
}
