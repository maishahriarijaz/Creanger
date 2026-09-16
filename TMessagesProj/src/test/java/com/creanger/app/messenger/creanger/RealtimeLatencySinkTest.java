package com.creanger.app.messenger.creanger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.creanger.app.messenger.creanger.realtime.MessageRealtimeClient;
import com.creanger.app.messenger.creanger.realtime.MessageRealtimeTransport;
import com.creanger.app.messenger.creanger.realtime.RealtimeLatency;
import com.creanger.app.messenger.creanger.realtime.RealtimeLatencySink;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageReaction;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Verifies the realtime client feeds the chat-screen latency badge: every
 * fresh (non-duplicate) message INSERT folds the row's server {@code
 * created_at} into the E2E series and emits the smoothed sample to the
 * {@link RealtimeLatencySink}; duplicates emit nothing and unsubscribe
 * clears the meter.
 */
public class RealtimeLatencySinkTest {

    private static final String CHAT = "chat-1";
    private static final String TOKEN = "jwt-live";

    // ---- scripted realtime transport (mirrors MessageRealtimeClientTest) ----

    private static final class ScriptedTransport implements MessageRealtimeTransport {
        MessageRealtimeTransport.Listener listener;
        int connectCount;

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
        }

        void connected() {
            listener.onTransportConnected();
        }

        void frame(String json) {
            listener.onTransportFrame(json);
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
        @Override
        public void schedule(long delayMillis, Runnable task) {
        }
    }

    private static final class Events implements MessageRealtimeClient.Listener {
        final List<String> received = new ArrayList<>();

        @Override
        public void onMessageReceived(String chatId, CreangerMessage message) {
            received.add(chatId + "|" + message.id);
        }

        @Override
        public void onMessageEdited(String chatId, CreangerMessage message) {
        }

        @Override
        public void onMessageStatusChanged(String chatId, CreangerMessage message) {
        }

        @Override
        public void onMessageDeleted(String chatId, String messageId) {
        }

        @Override
        public void onTypingChanged(String chatId, String userId, boolean typing) {
        }

        @Override
        public void onReactionChanged(String chatId, MessageReaction reaction, boolean added) {
        }

        @Override
        public void onDuplicate(String chatId, CreangerMessage message) {
        }

        @Override
        public void onConnected(String chatId) {
        }

        @Override
        public void onDisconnected(String chatId, Throwable cause) {
        }

        @Override
        public void onReconnected(String chatId, long lastKnownSeq) {
        }

        @Override
        public void onAuthError(String chatId, Throwable cause) {
        }
    }

    /** Records latency samples delivered on the (synchronous) poster. */
    private static final class SampleSink implements RealtimeLatencySink {
        final List<String> samples = new ArrayList<>();

        @Override
        public void onLatencySample(String chatId, RealtimeLatency.Kind kind, long ms) {
            samples.add(chatId + "|" + kind + "|" + ms);
        }
    }

    private static MessageRealtimeClient client(ScriptedTransport transport, Events events) {
        return new MessageRealtimeClient(
                transport,
                () -> TOKEN,
                Runnable::run,
                new RecordingScheduler(),
                events,
                new DirectExecutorService());
    }

    /** INSERT frame whose row was created at the fixed server timestamp. */
    private static String insertCreatedAt(String id, long seq, String createdAtIso) {
        return "{\"topic\":\"realtime:messages\",\"event\":\"postgres_changes\",\"payload\":{\"data\":{"
                + "\"schema\":\"public\",\"table\":\"messages\",\"eventType\":\"INSERT\","
                + "\"new\":{\"id\":\"" + id + "\",\"chat_id\":\"" + CHAT + "\",\"sender_id\":\"uuid-other\","
                + "\"message_type\":\"text\",\"content\":\"hi\",\"status\":\"sent\",\"client_message_id\":null,"
                + "\"chat_seq\":" + seq + ",\"created_at\":\"" + createdAtIso + "\",\"edited_at\":null,"
                + "\"deleted_at\":null,\"updated_at\":\"" + createdAtIso + "\"}}}}";
    }

    @Test
    public void freshInsertEmitsE2eSample() {
        ScriptedTransport transport = new ScriptedTransport();
        Events events = new Events();
        SampleSink sink = new SampleSink();
        MessageRealtimeClient c = client(transport, events);
        c.setLatencySink(sink);
        c.subscribe(CHAT, 0);
        transport.connected();

        long createdMs = System.currentTimeMillis();
        String iso = isoMillis(createdMs);
        transport.frame(insertCreatedAt("m-1", 1, iso));

        assertEquals(1, events.received.size());
        assertEquals(1, sink.samples.size());
        assertTrue(sink.samples.get(0).startsWith(CHAT + "|E2E|"));
        assertTrue(c.getE2eLatencyMs() >= 0);
    }

    @Test
    public void duplicateInsertEmitsNoSample() {
        ScriptedTransport transport = new ScriptedTransport();
        Events events = new Events();
        SampleSink sink = new SampleSink();
        MessageRealtimeClient c = client(transport, events);
        c.setLatencySink(sink);
        c.subscribe(CHAT, 0);
        transport.connected();

        transport.frame(insertCreatedAt("m-1", 1, isoMillis(System.currentTimeMillis())));
        transport.frame(insertCreatedAt("m-1", 1, isoMillis(System.currentTimeMillis()))); // exact duplicate

        assertEquals(1, events.received.size());
        assertEquals(1, sink.samples.size());
    }

    @Test
    public void sampleMsReflectsServerTimestampAge() {
        ScriptedTransport transport = new ScriptedTransport();
        Events events = new Events();
        SampleSink sink = new SampleSink();
        MessageRealtimeClient c = client(transport, events);
        c.setLatencySink(sink);
        c.subscribe(CHAT, 0);
        transport.connected();

        // Row created 5 minutes ago: a backlog-sized sample must be discarded
        // (no badge update), while a row "created now" must produce a sample.
        long now = System.currentTimeMillis();
        transport.frame(insertCreatedAt("m-old", 1, isoMillis(now - 300_000L)));
        transport.frame(insertCreatedAt("m-new", 2, isoMillis(now)));

        assertEquals(2, events.received.size());
        assertEquals(1, sink.samples.size());
        assertTrue(sink.samples.get(0).startsWith(CHAT + "|E2E|"));
    }

    @Test
    public void unsubscribeClearsMeter() {
        ScriptedTransport transport = new ScriptedTransport();
        Events events = new Events();
        SampleSink sink = new SampleSink();
        MessageRealtimeClient c = client(transport, events);
        c.setLatencySink(sink);
        c.subscribe(CHAT, 0);
        transport.connected();
        transport.frame(insertCreatedAt("m-1", 1, isoMillis(System.currentTimeMillis())));
        assertTrue(c.getE2eLatencyMs() >= 0);

        c.unsubscribe();
        assertEquals(-1, c.getE2eLatencyMs());
        assertEquals(-1, c.getRttLatencyMs());
    }

    /** Formats an epoch-millis instant as the ISO-8601 Z timestamp Postgres emits. */
    private static String isoMillis(long epochMs) {
        long days = Math.floorDiv(epochMs, 86_400_000L);
        long millisOfDay = Math.floorMod(epochMs, 86_400_000L);
        long secondsOfDay = millisOfDay / 1000L;
        long millis = millisOfDay % 1000L;
        long year;
        long month;
        long day;
        // Civil-from-days (Howard Hinnant) — mirrors RealtimeLatency.daysFromCivil.
        long z = days + 719_468L;
        long era = Math.floorDiv(z, 146_097L);
        long doe = z - era * 146_097L;
        long yoe = (doe - doe / 1460L + doe / 36524L - doe / 146_096L) / 365L;
        long y = yoe + era * 400L;
        long doy = doe - (365L * yoe + yoe / 4L - yoe / 100L);
        long mp = (5L * doy + 2L) / 153L;
        long d = doy - (153L * mp + 2L) / 5L + 1L;
        long m = mp < 10L ? mp + 3L : mp - 9L;
        year = y + (m <= 2L ? 1L : 0L);
        month = m;
        day = d;
        return String.format("%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
                year, month, day,
                secondsOfDay / 3600L, (secondsOfDay / 60L) % 60L, secondsOfDay % 60L, millis);
    }
}
