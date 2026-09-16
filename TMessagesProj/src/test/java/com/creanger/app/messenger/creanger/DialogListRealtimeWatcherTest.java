package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.realtime.DialogListRealtimeWatcher;
import com.creanger.app.messenger.creanger.realtime.MessageRealtimeClient;
import com.creanger.app.messenger.creanger.realtime.MessageRealtimeTransport;
import com.creanger.app.messenger.creanger.realtime.RealtimeMessageParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * DialogListRealtimeWatcher tests: any-chat message frames nudge the dialog
 * list, non-message frames are silent, drops reconnect with backoff, auth
 * failures stop permanently, and close/destroy tear down.
 *
 * All runners are synchronous so every assertion is deterministic.
 */
public class DialogListRealtimeWatcherTest {

    private static final String TOKEN = "jwt-live";

    private static final class ScriptedTransport implements MessageRealtimeTransport {
        MessageRealtimeTransport.Listener listener;
        int connectCount;
        int closeCount;

        @Override
        public void setListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void connect(String accessToken) {
            connectCount++;
        }

        @Override
        public void sendBroadcast(String event, String payloadJson) {
        }

        @Override
        public void close() {
            closeCount++;
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

    private static final class RecordingScheduler implements MessageRealtimeClient.ReconnectScheduler {
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void schedule(long delayMillis, Runnable task) {
            delays.add(delayMillis);
            tasks.add(task);
        }

        void runAll() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            for (Runnable r : pending) {
                r.run();
            }
        }
    }

    private static String wireInsert(String id, String chatId, String content) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"type\":\"postgres_changes\",\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"type\":\"INSERT\","
                + "\"record\":{\"id\":\"" + id + "\",\"chat_id\":\"" + chatId + "\",\"sender_id\":\"u-2\","
                + "\"message_type\":\"text\",\"content\":\"" + content + "\",\"status\":\"sent\","
                + "\"chat_seq\":9,\"created_at\":\"2026-08-16T10:00:00Z\"}"
                + "}}}";
    }

    private DialogListRealtimeWatcher watcher(ScriptedTransport transport,
                                              RecordingScheduler scheduler,
                                              List<String> nudged) {
        ExecutorService direct = new DirectExecutorService();
        return new DialogListRealtimeWatcher(
                transport,
                () -> TOKEN,
                Runnable::run,
                scheduler,
                nudged::add,
                direct);
    }

    @Test
    public void anyChatInsertNudgesWithChatId() {
        ScriptedTransport transport = new ScriptedTransport();
        RecordingScheduler scheduler = new RecordingScheduler();
        List<String> nudged = new ArrayList<>();
        DialogListRealtimeWatcher w = watcher(transport, scheduler, nudged);

        w.start();
        assertTrue(w.isActive());
        assertEquals(1, transport.connectCount);

        transport.frame(wireInsert("m-1", "chat-a", "hi"));
        assertEquals(1, nudged.size());
        assertEquals("chat-a", nudged.get(0));

        transport.frame(wireInsert("m-2", "chat-b", "yo"));
        assertEquals(2, nudged.size());
        assertEquals("chat-b", nudged.get(1));
    }

    @Test
    public void nonMessageFramesStaySilent() {
        ScriptedTransport transport = new ScriptedTransport();
        RecordingScheduler scheduler = new RecordingScheduler();
        List<String> nudged = new ArrayList<>();
        DialogListRealtimeWatcher w = watcher(transport, scheduler, nudged);
        w.start();

        // Typing broadcast for another surface: no dialog nudge.
        transport.frame("{\"topic\":\"realtime:messages\",\"event\":\"broadcast\","
                + "\"payload\":{\"type\":\"broadcast\",\"event\":\"typing\","
                + "\"payload\":{\"user_id\":\"u-2\",\"chat_id\":\"chat-a\",\"is_typing\":true}}}");
        // Wrong table.
        transport.frame("{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\","
                + "\"payload\":{\"type\":\"postgres_changes\",\"data\":{\"schema\":\"public\","
                + "\"table\":\"stories\",\"type\":\"INSERT\",\"record\":{\"id\":\"s-1\"}}}}");
        assertTrue(nudged.isEmpty());
    }

    @Test
    public void dropReconnectsWithBackoffAndStopsOnAuth() {
        ScriptedTransport transport = new ScriptedTransport();
        RecordingScheduler scheduler = new RecordingScheduler();
        List<String> nudged = new ArrayList<>();
        DialogListRealtimeWatcher w = watcher(transport, scheduler, nudged);
        w.start();

        transport.disconnect(new RuntimeException("drop"));
        assertEquals(1, scheduler.tasks.size());
        assertEquals(Long.valueOf(1000L), scheduler.delays.get(0));
        scheduler.runAll();
        assertEquals(2, transport.connectCount);

        transport.disconnect(new RuntimeException("drop again"));
        assertEquals(Long.valueOf(2000L), scheduler.delays.get(1));

        transport.unauthorized("forbidden");
        assertFalse(w.isActive());
        assertEquals(1, transport.closeCount);
    }

    @Test
    public void closeStopsFramesAndStartIsIdempotent() {
        ScriptedTransport transport = new ScriptedTransport();
        RecordingScheduler scheduler = new RecordingScheduler();
        List<String> nudged = new ArrayList<>();
        DialogListRealtimeWatcher w = watcher(transport, scheduler, nudged);
        w.start();
        w.start();
        assertEquals(1, transport.connectCount);

        w.close();
        assertFalse(w.isActive());
        transport.frame(wireInsert("m-9", "chat-a", "late"));
        assertTrue(nudged.isEmpty());
        // Closed watcher never reconnects.
        transport.disconnect(new RuntimeException("drop"));
        assertTrue(scheduler.tasks.isEmpty());
    }
}
