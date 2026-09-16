package com.creanger.app.messenger.creanger;

import org.junit.Test;

import com.creanger.app.messenger.creanger.realtime.CreangerNetworkMonitor;
import com.creanger.app.messenger.creanger.realtime.MessageRealtimeClient;
import com.creanger.app.messenger.creanger.realtime.MessageRealtimeTransport;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Network-aware reconnect tests: a usable network must shorten the reconnect
 * wait, an outage must retry the instant the network returns, a successful
 * (re)connect must reset the backoff ladder, and stale scheduled retries must
 * not re-open a live socket. Runs the same scripted-transport harness as
 * {@link MessageRealtimeClientTest}.
 */
public class NetworkAwareReconnectTest {

    private static final String CHAT = "chat-net";
    private static final String TOKEN = "jwt-net";

    /** Monitor fake with a switchable availability flag. */
    private static final class FakeMonitor extends CreangerNetworkMonitor {
        volatile boolean available = true;
        final List<CreangerNetworkMonitor.Listener> listeners = new ArrayList<>();

        @Override
        public boolean isNetworkAvailable() {
            return available;
        }

        @Override
        public boolean addListener(CreangerNetworkMonitor.Listener listener) {
            listeners.add(listener);
            return true;
        }

        @Override
        public void removeListener(CreangerNetworkMonitor.Listener listener) {
            listeners.remove(listener);
        }

        void networkReturns() {
            available = true;
            for (CreangerNetworkMonitor.Listener listener : new ArrayList<>(listeners)) {
                listener.onNetworkAvailable();
            }
        }
    }

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

        void connected() {
            listener.onTransportConnected();
        }

        void disconnect(Throwable cause) {
            listener.onTransportClosed(cause);
        }
    }

    private static final class DirectExecutorService extends java.util.concurrent.AbstractExecutorService {
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
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
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
            for (Runnable task : new ArrayList<>(tasks)) {
                task.run();
            }
            tasks.clear();
        }
    }

    private static MessageRealtimeClient client(ScriptedTransport transport,
                                                RecordingScheduler scheduler,
                                                FakeMonitor monitor) {
        MessageRealtimeClient client = new MessageRealtimeClient(
                transport,
                () -> TOKEN,
                Runnable::run,
                scheduler,
                new MessageRealtimeClient.Listener() {
                    @Override
                    public void onMessageReceived(String chatId, com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage message) {
                    }

                    @Override
                    public void onMessageEdited(String chatId, com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage message) {
                    }

                    @Override
                    public void onMessageStatusChanged(String chatId, com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage message) {
                    }

                    @Override
                    public void onMessageDeleted(String chatId, String messageId) {
                    }

                    @Override
                    public void onTypingChanged(String chatId, String userId, boolean isTyping) {
                    }

                    @Override
                    public void onReactionChanged(String chatId, com.creanger.app.messenger.creanger.model.MessageModels.MessageReaction reaction, boolean added) {
                    }

                    @Override
                    public void onDuplicate(String chatId, com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage message) {
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
                },
                new DirectExecutorService());
        client.setNetworkMonitor(monitor);
        return client;
    }

    @Test
    public void reconnectWithNetworkUpUsesShortDelay() {
        ScriptedTransport transport = new ScriptedTransport();
        RecordingScheduler scheduler = new RecordingScheduler();
        FakeMonitor monitor = new FakeMonitor();
        MessageRealtimeClient client = client(transport, scheduler, monitor);

        client.subscribe(CHAT, 0);
        transport.connected();
        assertEquals(1, transport.connectCount);

        transport.disconnect(new java.io.IOException("wifi blip"));
        scheduler.runAll();
        assertEquals("network up: retry must stay at the fast end of the ladder",
                1_000L, (long) scheduler.delays.get(0));
        assertEquals(2, transport.connectCount);
        client.close();
    }

    @Test
    public void offlineReconnectRetriesInstantlyWhenNetworkReturns() {
        ScriptedTransport transport = new ScriptedTransport();
        RecordingScheduler scheduler = new RecordingScheduler();
        FakeMonitor monitor = new FakeMonitor();
        monitor.available = false;
        MessageRealtimeClient client = client(transport, scheduler, monitor);

        client.subscribe(CHAT, 0);
        transport.connected();
        transport.disconnect(new java.io.IOException("airplane mode"));

        // The offline path registered both a ladder task and a one-shot
        // network listener. Network return must connect immediately —
        // without waiting out the 30 s ladder task.
        assertEquals(1, monitor.listeners.size());
        monitor.networkReturns();
        assertEquals("network-return retry must fire without the ladder delay",
                2, transport.connectCount);
        assertTrue("one-shot listener must detach after firing",
                monitor.listeners.isEmpty());
        client.close();
    }

    @Test
    public void successfulReconnectResetsBackoffLadder() {
        ScriptedTransport transport = new ScriptedTransport();
        RecordingScheduler scheduler = new RecordingScheduler();
        FakeMonitor monitor = new FakeMonitor();
        monitor.available = false;
        MessageRealtimeClient client = client(transport, scheduler, monitor);

        client.subscribe(CHAT, 0);
        transport.connected();
        transport.disconnect(new java.io.IOException("drop 1"));
        monitor.networkReturns();
        transport.connected();

        // A later drop must start from the fast end again, not the grown ladder.
        transport.disconnect(new java.io.IOException("drop 2"));
        scheduler.runAll();
        assertEquals(1_000L, (long) scheduler.delays.get(scheduler.delays.size() - 1));
        client.close();
    }

    @Test
    public void staleScheduledTaskDoesNotReopenLiveSocket() {
        ScriptedTransport transport = new ScriptedTransport();
        RecordingScheduler scheduler = new RecordingScheduler();
        FakeMonitor monitor = new FakeMonitor();
        monitor.available = false;
        MessageRealtimeClient client = client(transport, scheduler, monitor);

        client.subscribe(CHAT, 0);
        transport.connected();
        transport.disconnect(new java.io.IOException("drop"));

        // Ladder task reconnects…
        scheduler.runAll();
        assertEquals(2, transport.connectCount);
        transport.connected();

        // …then the network-return event fires while the socket is already
        // live — the retry must be suppressed, not re-open the transport.
        monitor.networkReturns();
        assertEquals("no redundant connect while connected", 2, transport.connectCount);
        scheduler.runAll();
        assertEquals(2, transport.connectCount);
        client.close();
    }
}
