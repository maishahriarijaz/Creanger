package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageReaction;
import com.creanger.app.messenger.creanger.realtime.MessageRealtimeClient;
import com.creanger.app.messenger.creanger.realtime.MessageRealtimeTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * MessageRealtimeClient orchestration tests: exactly-once delivery for the open
 * chat, wrong-chat frames dropped, reconnect with capped backoff and
 * {@code lastKnownSeq} recovery, permanent-stop on authorization failures,
 * unsubscribe isolation, and per-client (account) isolation.
 *
 * All runners are synchronous (poster, connect executor, scheduler) so every
 * assertion is deterministic on the calling thread.
 */
public class MessageRealtimeClientTest {

    private static final String CHAT = "chat-1";
    private static final String TOKEN = "jwt-live";

    // ---- scripted realtime transport ----

    private static final class ScriptedTransport implements MessageRealtimeTransport {
        MessageRealtimeTransport.Listener listener;
        final List<String> tokens = new ArrayList<>();
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

        void unauthorized(String reason) {
            listener.onTransportUnauthorized(reason);
        }
    }

    // ---- synchronous runners ----

    private static final class DirectExecutorService extends AbstractExecutorService {
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

    private static ExecutorService directExecutor() {
        return new DirectExecutorService();
    }

    /** A scheduler that records every request for later replay. */
    private static final class RecordingScheduler implements MessageRealtimeClient.ReconnectScheduler {
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void schedule(long delayMillis, Runnable task) {
            delays.add(delayMillis);
            tasks.add(task);
        }

        void runAll() {
            for (Runnable task : tasks) {
                task.run();
            }
            tasks.clear();
        }
    }

    private static final class Events implements MessageRealtimeClient.Listener {
        final List<String> received = new ArrayList<>();
        final List<String> edited = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final List<String> reactionList = new ArrayList<>();
        final List<String> duplicates = new ArrayList<>();
        final List<String> connected = new ArrayList<>();
        final List<String> disconnected = new ArrayList<>();
        final List<String> reconnected = new ArrayList<>();
        final List<Throwable> authErrors = new ArrayList<>();
        final List<Long> recoveredSeqs = new ArrayList<>();

        @Override
        public void onMessageReceived(String chatId, CreangerMessage message) {
            received.add(chatId + "|" + message.id + "|" + message.chatSeq);
        }

        @Override
        public void onMessageEdited(String chatId, CreangerMessage message) {
            edited.add(chatId + "|" + message.id + "|" + message.content);
        }

        @Override
        public void onMessageStatusChanged(String chatId, CreangerMessage message) {
            statuses.add(chatId + "|" + message.id + "|" + message.status);
        }

        @Override
        public void onMessageDeleted(String chatId, String messageId) {
            deleted.add(chatId + "|" + messageId);
        }

        @Override
        public void onReactionChanged(String chatId, MessageReaction reaction, boolean added) {
            reactionList.add(chatId + "|" + reaction.messageId + "|" + reaction.reaction + "|" + added);
        }

        @Override
        public void onDuplicate(String chatId, CreangerMessage message) {
            duplicates.add(chatId + "|" + (message == null ? "null" : message.id));
        }

        @Override
        public void onConnected(String chatId) {
            connected.add(chatId);
        }

        @Override
        public void onDisconnected(String chatId, Throwable cause) {
            disconnected.add(chatId);
        }

        @Override
        public void onReconnected(String chatId, long lastKnownSeq) {
            reconnected.add(chatId);
            recoveredSeqs.add(lastKnownSeq);
        }

        @Override
        public void onAuthError(String chatId, Throwable cause) {
            authErrors.add(cause);
        }
    }

    private static MessageRealtimeClient client(ScriptedTransport transport, Events events,
                                                RecordingScheduler scheduler,
                                                MessageRealtimeClient.AccessTokenProvider tokenProvider) {
        return new MessageRealtimeClient(
                transport,
                tokenProvider == null ? () -> TOKEN : tokenProvider,
                Runnable::run,
                scheduler,
                events,
                directExecutor());
    }

    private static String insert(String id, String chatId, long seq, String content) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"INSERT\","
                + "\"new\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"sent\",\"client_message_id\":null,"
                + "\"chat_seq\":" + seq + ",\"created_at\":\"2026-08-16T10:00:00Z\",\"edited_at\":null,"
                + "\"deleted_at\":null,\"updated_at\":\"2026-08-16T10:00:00Z\"}}}}";
    }

    /** Realtime UPDATE frame carrying an edit (deleted_at null). */
    private static String updateEdit(String id, String chatId, String content) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"UPDATE\","
                + "\"new\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"sent\",\"client_message_id\":null,"
                + "\"chat_seq\":1,\"created_at\":\"2026-08-16T10:00:00Z\",\"edited_at\":\"2026-08-16T11:00:00Z\","
                + "\"deleted_at\":null,\"updated_at\":\"2026-08-16T11:00:00Z\"}}}}";
    }

    /** Realtime UPDATE frame where new.deleted_at is set (soft-delete tombstone). */
    private static String tombstone(String id, String chatId) {
        return updateEdit(id, chatId, "previous")
                .replace("\"edited_at\":\"2026-08-16T11:00:00Z\"", "\"edited_at\":null")
                .replace("\"deleted_at\":null", "\"deleted_at\":\"2026-08-16T12:00:00Z\"");
    }

    /**
     * Realtime UPDATE frame carrying a status-only change (the
     * {@code mark_message_status} RPC): new.status advanced, content untouched.
     */
    private static String statusUpdate(String id, String chatId, String oldStatus, String newStatus) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"UPDATE\","
                + "\"new\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"hi\",\"status\":\"" + newStatus + "\",\"client_message_id\":null,"
                + "\"chat_seq\":1,\"created_at\":\"2026-08-16T10:00:00Z\",\"edited_at\":null,"
                + "\"deleted_at\":null,\"updated_at\":\"2026-08-16T12:00:00Z\"},"
                + "\"old\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\","
                + "\"content\":\"hi\",\"status\":\"" + oldStatus + "\"}}}}";
    }

    /** Realtime DELETE frame (hard delete; only old is present). */
    private static String hardDelete(String id, String chatId) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"DELETE\","
                + "\"old\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"old\",\"chat_seq\":1,"
                + "\"created_at\":\"2026-08-16T10:00:00Z\"}}}}";
    }

    /**
     * Realtime frame for a {@code message_reactions} row (no chat_id anywhere).
     * {@code INSERT}/{@code UPDATE} place the row in {@code new} (added); a
     * {@code DELETE} in {@code old} (removed).
     */
    private static String reactionChange(String messageId, String userId, String reaction,
                                         String eventType, boolean inNew) {
        String body = "\"message_id\":\"" + messageId + "\",\"user_id\":\"" + userId + "\","
                + "\"reaction\":\"" + reaction + "\",\"is_custom_emoji\":false,\"custom_emoji_id\":null,"
                + "\"created_at\":\"2026-08-16T10:00:00Z\",\"updated_at\":\"2026-08-16T10:00:00Z\"";
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"message_reactions\",\"eventType\":\"" + eventType + "\","
                + (inNew ? "\"new\":{" : "\"old\":{") + body + "}}}}";
    }

    // ---- tests ----

    @Test
    public void newMessageDeliveredAndTracksLastKnownSeq() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        MessageRealtimeClient client = client(t, events, new RecordingScheduler(), null);

        client.subscribe(CHAT, 0);
        t.connected();
        assertEquals(TOKEN, t.tokens.get(0));
        assertTrue(events.connected.contains(CHAT));

        t.frame(insert("m-5", CHAT, 5, "hi"));
        assertEquals(1, events.received.size());
        assertEquals("chat-1|m-5|5", events.received.get(0));
        assertEquals(5L, client.getLastKnownSeq());

        t.frame(insert("m-6", CHAT, 6, "yo"));
        assertEquals("chat-1|m-6|6", events.received.get(1));
        assertEquals(6L, client.getLastKnownSeq());
    }

    @Test
    public void duplicateEventIsNotRedeliveredAsNew() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        MessageRealtimeClient client = client(t, events, new RecordingScheduler(), null);

        client.subscribe(CHAT, 0);
        t.connected();
        String frame = insert("m-5", CHAT, 5, "hi");
        t.frame(frame);
        t.frame(frame); // delivered twice by Realtime

        assertEquals(1, events.received.size());
        assertEquals(1, events.duplicates.size());
    }

    @Test
    public void duplicateByClientMessageIdIsIgnored() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        MessageRealtimeClient client = client(t, events, new RecordingScheduler(), null);

        client.subscribe(CHAT, 0);
        t.connected();
        t.frame(insert("m-5", CHAT, 5, "hi").replace("\"client_message_id\":null", "\"client_message_id\":\"cm-9\""));
        t.frame(insert("m-5b", CHAT, 6, "hi").replace("\"client_message_id\":null", "\"client_message_id\":\"cm-9\""));

        assertEquals(1, events.received.size());
        assertEquals(1, events.duplicates.size());
    }

    @Test
    public void statusChangeIsSurfacedAndExactRepeatIsDeduplicated() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        MessageRealtimeClient client = client(t, events, new RecordingScheduler(), null);

        client.subscribe(CHAT, 0);
        t.connected();

        t.frame(statusUpdate("m-9", CHAT, "sent", "delivered"));
        assertEquals(1, events.statuses.size());
        assertEquals("chat-1|m-9|delivered", events.statuses.get(0));

        t.frame(statusUpdate("m-9", CHAT, "sent", "delivered")); // re-delivered by Realtime
        assertEquals(1, events.statuses.size());
        assertEquals(1, events.duplicates.size());

        // An advance to read is a different status: surfaced (not an edit).
        t.frame(statusUpdate("m-9", CHAT, "delivered", "read"));
        assertEquals(2, events.statuses.size());
        assertEquals("chat-1|m-9|read", events.statuses.get(1));
        assertTrue(events.edited.isEmpty());
    }

    @Test
    public void reactionAddIsSurfacedAndRemoveToggleIsNotADuplicate() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        MessageRealtimeClient client = client(t, events, new RecordingScheduler(), null);

        client.subscribe(CHAT, 0);
        t.connected();

        t.frame(reactionChange("m-9", "user-a", "\uD83D\uDC4D", "INSERT", true));
        assertEquals(1, events.reactionList.size());
        assertEquals("chat-1|m-9|\uD83D\uDC4D|true", events.reactionList.get(0));

        // Re-delivered add: a duplicate, never surfaced twice.
        t.frame(reactionChange("m-9", "user-a", "\uD83D\uDC4D", "INSERT", true));
        assertEquals(1, events.reactionList.size());
        assertEquals(1, events.duplicates.size());

        // A genuine remove is a real change (toggle), not a duplicate.
        t.frame(reactionChange("m-9", "user-a", "\uD83D\uDC4D", "DELETE", false));
        assertEquals(2, events.reactionList.size());
        assertEquals("chat-1|m-9|\uD83D\uDC4D|false", events.reactionList.get(1));
    }

    @Test
    public void outOfOrderStatusRegressionIsStillSurfacedToRepository() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        MessageRealtimeClient client = client(t, events, new RecordingScheduler(), null);

        client.subscribe(CHAT, 0);
        t.connected();

        t.frame(statusUpdate("m-9", CHAT, "delivered", "read"));
        // A late/regressing delivered is a different status: surfaced, so the
        // repository can reject it monotonically (a client-side drop here would
        // silently hide a genuine delivered on a message already known as read).
        t.frame(statusUpdate("m-9", CHAT, "read", "delivered"));

        assertEquals(2, events.statuses.size());
        assertEquals("chat-1|m-9|read", events.statuses.get(0));
        assertEquals("chat-1|m-9|delivered", events.statuses.get(1));
    }

    @Test
    public void wrongChatFrameIsIgnoredEntirely() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        MessageRealtimeClient client = client(t, events, new RecordingScheduler(), null);

        client.subscribe(CHAT, 0);
        t.connected();
        t.frame(insert("m-1", "chat-OTHER", 1, "no"));

        assertTrue(events.received.isEmpty());
        assertTrue(events.duplicates.isEmpty());
    }

    @Test
    public void reconnectSchedulesBackoffAndReportsRecovery() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        RecordingScheduler scheduler = new RecordingScheduler();
        MessageRealtimeClient client = client(t, events, scheduler, null);

        client.subscribe(CHAT, 10);
        t.connected();
        t.frame(insert("m-11", CHAT, 11, "one"));

        t.disconnect(new IOException("socket closed"));
        assertTrue(events.disconnected.contains(CHAT));
        assertEquals(1, scheduler.delays.size());
        assertEquals(1000L, scheduler.delays.get(0).longValue());

        scheduler.runAll();
        assertEquals(2, t.connectCount); // reconnected

        t.connected();
        assertEquals(1, events.reconnected.size());
        assertEquals(11L, events.recoveredSeqs.get(0).longValue()); // cursor recovery
        assertEquals(1, events.connected.size()); // only the initial connect fired "connected"; the gap fired "reconnected"
    }

    @Test
    public void firstSuccessfulConnectAfterFailureAlsoRecovers() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        RecordingScheduler scheduler = new RecordingScheduler();
        int[] attempts = {0};
        MessageRealtimeClient client = client(t, events, scheduler, () -> {
            if (attempts[0]++ == 0) {
                throw new IOException("token network down");
            }
            return TOKEN;
        });

        client.subscribe(CHAT, 3);
        assertEquals(1, scheduler.delays.size()); // failed → scheduled reconnect
        assertEquals(0, t.connectCount); // the token outage happened before ever reaching the socket

        scheduler.runAll();
        assertEquals(1, t.connectCount); // the retry reached the socket
        t.connected();

        // a gap (token outage) happened BEFORE any successful connect → recover.
        assertEquals(1, events.reconnected.size());
        assertEquals(3L, events.recoveredSeqs.get(0).longValue());
        assertTrue(events.connected.isEmpty());
    }

    @Test
    public void backoffGrowsAndCaps() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        RecordingScheduler scheduler = new RecordingScheduler();
        MessageRealtimeClient client = client(t, events, scheduler, null);

        client.subscribe(CHAT, 0);
        t.connected();
        long[] expected = {1000, 2000, 4000, 8000, 16000, 30000, 30000};
        for (int i = 0; i < expected.length; i++) {
            t.disconnect(null);
            assertEquals(expected[i], scheduler.delays.get(i).longValue());
            scheduler.runAll();
            t.connected();
        }
    }

    @Test
    public void unauthorizedStopsSubscriptionWithoutReconnect() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        RecordingScheduler scheduler = new RecordingScheduler();
        MessageRealtimeClient client = client(t, events, scheduler, null);

        client.subscribe(CHAT, 0);
        t.connected();
        t.unauthorized("realtime closed (4003)");

        assertEquals(1, events.authErrors.size());
        assertFalse(client.isActive());
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    public void tokenAuthFailureStopsWithoutReconnect() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        RecordingScheduler scheduler = new RecordingScheduler();
        MessageRealtimeClient client = client(t, events, scheduler,
                () -> {
                    throw new CreangerApiException(401,
                            new com.creanger.app.messenger.creanger.model.ApiError(
                                    com.creanger.app.messenger.creanger.model.ApiError.UNAUTHORIZED, "no", null, 0));
                });

        client.subscribe(CHAT, 0);

        assertEquals(1, events.authErrors.size());
        assertFalse(client.isActive());
        assertTrue(scheduler.tasks.isEmpty());
        assertEquals(0, t.connectCount);
    }

    @Test
    public void tokenNetworkFailureSchedulesReconnect() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        RecordingScheduler scheduler = new RecordingScheduler();
        MessageRealtimeClient client = client(t, events, scheduler,
                () -> {
                    throw new IOException("network");
                });

        client.subscribe(CHAT, 0);

        assertTrue(events.authErrors.isEmpty());
        assertEquals(1, scheduler.delays.size());
        assertEquals(0, t.connectCount);
    }

    @Test
    public void unsubscribeStopsPendingReconnect() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        RecordingScheduler scheduler = new RecordingScheduler();
        MessageRealtimeClient client = client(t, events, scheduler, null);

        client.subscribe(CHAT, 0);
        t.connected();
        t.disconnect(new IOException("drop"));
        client.unsubscribe();

        scheduler.runAll(); // scheduled task fires but the client must ignore it
        assertEquals(1, t.connectCount);
        assertFalse(client.isActive());
    }

    @Test
    public void switchChatUnsubscribesPrevious() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        RecordingScheduler scheduler = new RecordingScheduler();
        MessageRealtimeClient client = client(t, events, scheduler, null);

        client.subscribe("chat-A", 0);
        t.connected();
        client.subscribe("chat-B", 0);
        t.connected();

        // Old chat went away; a frame for it must be ignored.
        assertEquals(2, t.connectCount);
        t.frame(insert("m-1", "chat-A", 1, "late"));

        assertTrue(events.received.isEmpty());
    }

    @Test
    public void clientsAreIsolatedAcrossAccounts() {
        ScriptedTransport t1 = new ScriptedTransport();
        Events e1 = new Events();
        MessageRealtimeClient c1 = client(t1, e1, new RecordingScheduler(), null);
        c1.subscribe(CHAT, 0);
        t1.connected();
        t1.frame(insert("m-5", CHAT, 5, "hi"));
        c1.close();

        // A fresh client for a different account/chat must treat the same frame as new.
        ScriptedTransport t2 = new ScriptedTransport();
        Events e2 = new Events();
        MessageRealtimeClient c2 = client(t2, e2, new RecordingScheduler(), null);
        c2.subscribe("chat-A", 0);
        t2.connected();
        t2.frame(insert("m-5", "chat-A", 5, "hi"));

        assertEquals(1, e2.received.size()); // no cross-account dedup leak
    }

    // ---- realtime edits ----

    @Test
    public void realtimeEditDeliveredAndExactEchoSuppressed() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        MessageRealtimeClient client = client(t, events, new RecordingScheduler(), null);

        client.subscribe(CHAT, 0);
        t.connected();

        t.frame(updateEdit("m-5", CHAT, "edited-v1"));
        assertEquals(1, events.edited.size());
        assertEquals("chat-1|m-5|edited-v1", events.edited.get(0));

        // The exact same edit re-delivered (Realtime duplicate, or the echo of
        // the user's own optimistic edit) must NOT fire a second edit event.
        t.frame(updateEdit("m-5", CHAT, "edited-v1"));
        assertEquals(1, events.edited.size());
        assertEquals(1, events.duplicates.size());
    }

    @Test
    public void distinctSubsequentEditIsStillDelivered() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        MessageRealtimeClient client = client(t, events, new RecordingScheduler(), null);

        client.subscribe(CHAT, 0);
        t.connected();
        t.frame(updateEdit("m-5", CHAT, "v1"));
        t.frame(updateEdit("m-5", CHAT, "v2"));

        assertEquals(2, events.edited.size());
        assertEquals("chat-1|m-5|v1", events.edited.get(0));
        assertEquals("chat-1|m-5|v2", events.edited.get(1));
    }

    // ---- realtime deletes ----

    @Test
    public void realtimeSoftDeleteTombstoneDeliveredOnce() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        MessageRealtimeClient client = client(t, events, new RecordingScheduler(), null);

        client.subscribe(CHAT, 0);
        t.connected();
        t.frame(tombstone("m-5", CHAT));
        assertEquals(1, events.deleted.size());
        assertEquals("chat-1|m-5", events.deleted.get(0));

        t.frame(tombstone("m-5", CHAT)); // Realtime re-delivery
        assertEquals(1, events.deleted.size());
        assertEquals(0, events.edited.size());
    }

    @Test
    public void realtimeHardDeleteEventDeliveredOnce() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        MessageRealtimeClient client = client(t, events, new RecordingScheduler(), null);

        client.subscribe(CHAT, 0);
        t.connected();
        t.frame(hardDelete("m-6", CHAT));
        assertEquals(1, events.deleted.size());
        assertEquals("chat-1|m-6", events.deleted.get(0));

        t.frame(hardDelete("m-6", CHAT));
        assertEquals(1, events.deleted.size());
    }

    @Test
    public void wrongChatEditAndDeleteAreIgnored() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        MessageRealtimeClient client = client(t, events, new RecordingScheduler(), null);

        client.subscribe(CHAT, 0);
        t.connected();
        t.frame(updateEdit("m-1", "chat-OTHER", "x"));
        t.frame(tombstone("m-2", "chat-OTHER"));
        t.frame(hardDelete("m-3", "chat-OTHER"));

        assertTrue(events.edited.isEmpty());
        assertTrue(events.deleted.isEmpty());
        assertTrue(events.received.isEmpty());
    }

    @Test
    public void editAfterInsertIsNotSwallowedByInsertDedup() {
        ScriptedTransport t = new ScriptedTransport();
        Events events = new Events();
        MessageRealtimeClient client = client(t, events, new RecordingScheduler(), null);

        client.subscribe(CHAT, 0);
        t.connected();
        t.frame(insert("m-5", CHAT, 5, "original"));
        assertEquals(1, events.received.size());

        // A subsequent edit of the same message must still surface as an edit,
        // even though the id is already known to the INSERT dedup.
        t.frame(updateEdit("m-5", CHAT, "edited-v1"));
        assertEquals(1, events.edited.size());
    }
}