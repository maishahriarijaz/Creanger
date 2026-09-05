package com.creanger.app.network.websocket;

import android.os.Handler;
import android.os.Looper;

import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.FileLog;
import com.creanger.app.messenger.Utilities;
import com.creanger.app.network.model.NetworkError;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * WebSocket client for real-time backend events.
 * Supports:
 * - Automatic reconnection with exponential backoff
 * - Authenticated connections
 * - Event parsing and dispatching
 * - Connection state callbacks
 */
public class CustomWebSocketClient {

    public enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        FAILED
    }

    public interface EventListener {
        void onEvent(String eventType, JSONObject payload);
        void onStateChanged(State newState);
        void onError(NetworkError error);
    }

    public interface TokenProvider {
        String getAccessToken();
    }

    private final OkHttpClient httpClient;
    private final String wsUrl;
    private final TokenProvider tokenProvider;
    private final EventListener eventListener;

    private WebSocket webSocket;
    private State currentState = State.DISCONNECTED;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, EventHandler> eventHandlers = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Reconnection config
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long BASE_RECONNECT_DELAY_MS = 2000;
    private static final double RECONNECT_MULTIPLIER = 1.5;
    private static final long MAX_RECONNECT_DELAY_MS = 60000;
    private static final long PING_INTERVAL_MS = 30000;

    private ScheduledFuture<?> pingFuture;
    private Runnable pingRunnable;

    public CustomWebSocketClient(OkHttpClient httpClient, String baseUrl, TokenProvider tokenProvider, EventListener eventListener) {
        this.httpClient = httpClient;
        this.wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "ws";
        this.tokenProvider = tokenProvider;
        this.eventListener = eventListener;
    }

    public void connect() {
        if (currentState == State.CONNECTING || currentState == State.CONNECTED) {
            FileLog.d("WebSocket already connected/connecting");
            return;
        }

        isShuttingDown.set(false);
        reconnectAttempt.set(0);
        doConnect();
    }

    private void doConnect() {
        setState(State.CONNECTING);

        String token = tokenProvider.getAccessToken();
        Request request = new Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer " + (token != null ? token : ""))
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                FileLog.d("WebSocket connected");
                reconnectAttempt.set(0);
                setState(State.CONNECTED);
                startPing();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                FileLog.d("WebSocket binary message: " + bytes.size() + " bytes");
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                FileLog.d("WebSocket closing: " + code + " " + reason);
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                FileLog.d("WebSocket closed: " + code + " " + reason);
                stopPing();
                if (!isShuttingDown.get()) {
                    scheduleReconnect();
                } else {
                    setState(State.DISCONNECTED);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
                FileLog.e("WebSocket failure: " + t.getMessage(), t);
                stopPing();
                if (!isShuttingDown.get()) {
                    scheduleReconnect();
                } else {
                    setState(State.FAILED);
                }
            }
        });
    }

    private void handleMessage(String text) {
        try {
            JSONObject json = new JSONObject(text);
            String eventType = json.optString("event", json.optString("type", ""));
            JSONObject payload = json.optJSONObject("data");

            if (eventType.isEmpty()) {
                FileLog.d("WebSocket message without event type: " + text);
                return;
            }

            // Dispatch to registered handlers
            EventHandler handler = eventHandlers.get(eventType);
            if (handler != null) {
                handler.handle(payload);
            }

            // Also notify main listener
            if (eventListener != null) {
                mainHandler.post(() -> eventListener.onEvent(eventType, payload != null ? payload : new JSONObject()));
            }

        } catch (JSONException e) {
            FileLog.e("Failed to parse WebSocket message: " + text, e);
        }
    }

    public void send(String eventType, JSONObject payload) {
        if (webSocket == null || currentState != State.CONNECTED) {
            FileLog.d("Cannot send, WebSocket not connected");
            return;
        }

        JSONObject message = new JSONObject();
        try {
            message.put("event", eventType);
            if (payload != null) {
                message.put("data", payload);
            }
            webSocket.send(message.toString());
        } catch (JSONException e) {
            FileLog.e("Failed to create WebSocket message", e);
        }
    }

    public void sendPing() {
        if (webSocket != null && currentState == State.CONNECTED) {
            webSocket.send("{\"event\":\"ping\"}");
        }
    }

    public void disconnect() {
        isShuttingDown.set(true);
        stopPing();
        scheduler.shutdown();

        if (webSocket != null) {
            webSocket.close(1000, "Client disconnect");
            webSocket = null;
        }
        setState(State.DISCONNECTED);
    }

    public void registerHandler(String eventType, EventHandler handler) {
        eventHandlers.put(eventType, handler);
    }

    public void unregisterHandler(String eventType) {
        eventHandlers.remove(eventType);
    }

    public State getState() {
        return currentState;
    }

    public boolean isConnected() {
        return currentState == State.CONNECTED;
    }

    private void setState(State newState) {
        if (currentState != newState) {
            currentState = newState;
            FileLog.d("WebSocket state: " + newState);
            if (eventListener != null) {
                mainHandler.post(() -> eventListener.onStateChanged(newState));
            }
        }
    }

    private void startPing() {
        stopPing();
        pingRunnable = () -> {
            if (currentState == State.CONNECTED) {
                sendPing();
                pingFuture = scheduler.schedule(pingRunnable, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
        };
        pingFuture = scheduler.schedule(pingRunnable, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPing() {
        if (pingFuture != null) {
            pingFuture.cancel(false);
            pingFuture = null;
        }
        pingRunnable = null;
    }

    private void scheduleReconnect() {
        if (isShuttingDown.get()) return;

        int attempt = reconnectAttempt.getAndIncrement();
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            FileLog.d("Max reconnect attempts reached");
            setState(State.FAILED);
            if (eventListener != null) {
                mainHandler.post(() -> eventListener.onError(NetworkError.connectionError("Max reconnect attempts reached")));
            }
            return;
        }

        long delay = (long) Math.min(BASE_RECONNECT_DELAY_MS * Math.pow(RECONNECT_MULTIPLIER, attempt), MAX_RECONNECT_DELAY_MS);
        FileLog.d("Scheduling WebSocket reconnect in " + delay + "ms (attempt " + (attempt + 1) + ")");
        setState(State.RECONNECTING);

        scheduler.schedule(() -> {
            if (!isShuttingDown.get()) {
                doConnect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    public interface EventHandler {
        void handle(JSONObject payload);
    }
}