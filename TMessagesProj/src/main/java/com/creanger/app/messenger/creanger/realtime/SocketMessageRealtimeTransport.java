package com.creanger.app.messenger.creanger.realtime;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLSocketFactory;

/**
 * Device Realtime transport: a dependency-free RFC 6455 WebSocket client that
 * speaks the Supabase Realtime (Phoenix Channels) protocol over a raw socket
 * (TLS for {@code wss://}, plain for the debug loopback {@code ws://}).
 *
 * On connect it performs the HTTP Upgrade handshake (carrying the authenticated
 * Creanger JWT as {@code apikey} + {@code Authorization} headers so Row Level
 * Security scopes the channel), then joins the {@code realtime:messages}
 * channel subscribed to {@code INSERT}/{@code UPDATE}/{@code DELETE} on
 * {@code public.messages}. Raw text
 * frames are forwarded to {@link Listener#onTransportFrame}; server pings are
 * answered automatically; close codes {@code 4003/4007} become a permanent
 * {@link Listener#onTransportUnauthorized}.
 *
 * Two Phoenix-protocol obligations from the official client that a raw socket
 * must replicate, otherwise the subscription silently yields nothing:
 * <ul>
 *   <li>{@code access_token} push — right after the join, the user JWT is
 *       pushed as {@code {topic: realtime:messages, event: access_token,
 *       payload: {access_token}}}. Without it the channel runs as the anon
 *       role and every row is rejected with {@code errors: ["Error 401:
 *       Unauthorized"]}.</li>
 *   <li>Phoenix heartbeat — {@code {topic: phoenix, event: heartbeat}} every
 *       25s (the official interval); without it the server times the
 *       connection out and drops it.</li>
 * </ul>
 *
 * All callbacks are delivered on an internal reader thread; the client
 * relocates them to its poster.
 */
public final class SocketMessageRealtimeTransport implements MessageRealtimeTransport {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    /** Official Phoenix heartbeat interval (realtime-js HEARTBEAT_INTERVAL). */
    static final long HEARTBEAT_INTERVAL_MS = 25_000L;

    private final String scheme;
    private final String host;
    private final int port;
    private final String pathAndQuery;

    private final Object writeLock = new Object();
    private Socket socket;
    // Volatile: close() nulls these from any thread while readLoop()/sendFrame()
    // touch them concurrently.
    private volatile OutputStream output;
    private volatile InputStream input;
    private volatile boolean closed = true;
    private Listener listener;
    private ExecutorService readerExecutor;
    private volatile ScheduledExecutorService heartbeatExecutor;
    private final AtomicLong refCounter = new AtomicLong();
    /**
     * Supabase project anon key, sent as the {@code apikey} header and query
     * param on the realtime handshake. Supabase authenticates the handshake
     * against the project key — the user JWT goes in {@code Authorization}
     * only. Falls back to the access token when unset (legacy behavior).
     */
    private volatile String apiKey;

    /**
     * @param endpoint e.g. {@code wss://xyz.supabase.co/realtime/v1/websocket?vsn=1.0.0}
     */
    public SocketMessageRealtimeTransport(String endpoint) {
        java.net.URI uri = java.net.URI.create(endpoint);
        this.scheme = uri.getScheme();
        this.host = uri.getHost();
        int p = uri.getPort();
        this.port = ("wss".equalsIgnoreCase(scheme))
                ? (p == -1 ? 443 : p)
                : (p == -1 ? 80 : p);
        String path = uri.getRawPath();
        String query = uri.getRawQuery();
        this.pathAndQuery = path + (query != null ? ("?" + query) : "");
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Sets the Supabase anon key used as {@code apikey} on the handshake. */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public void connect(String accessToken) {
        if (closed != true) {
            // Already running (or mid-open); force a clean slate first.
            close();
        }
        String configuredKey = apiKey;
        String endpointToken = configuredKey != null && !configuredKey.isEmpty()
                ? configuredKey
                : (accessToken != null ? accessToken : "");
        String key = WebSocketFrameCodec.generateKey();
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("apikey", endpointToken);

        try {
            Socket socketLocal;
            if ("wss".equalsIgnoreCase(scheme)) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                socketLocal = factory.createSocket();
                socketLocal.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            } else {
                socketLocal = new Socket();
                socketLocal.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            }
            socketLocal.setSoTimeout(READ_TIMEOUT_MS);
            this.socket = socketLocal;
            this.output = new BufferedOutputStream(socketLocal.getOutputStream());
            this.input = new BufferedInputStream(socketLocal.getInputStream());

            String q = pathAndQuery.contains("?") ? pathAndQuery + "&apikey=" + urlEncode(endpointToken)
                    : pathAndQuery + "?apikey=" + urlEncode(endpointToken);
            String request = WebSocketFrameCodec.handshakeRequest(host, q, key, headers);
            writeRaw(request.getBytes(StandardCharsets.UTF_8));
            String responseHead = readResponseHead(this.input);
            int status = WebSocketFrameCodec.responseStatus(responseHead);
            if (status != 101) {
                close();
                if (status == 401 || status == 403) {
                    notifyUnauthorized("realtime handshake rejected (" + status + ")");
                } else {
                    notifyClosed(new IOException("realtime upgrade failed: HTTP " + status));
                }
                return;
            }

            this.closed = false;
            sendJoin();
            sendAccessToken(accessToken);
            startHeartbeat();
            Listener l = this.listener;
            if (l != null) {
                l.onTransportConnected();
            }

            readerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "creanger-realtime-reader");
                t.setDaemon(true);
                return t;
            });
            readerExecutor.execute(this::readLoop);
        } catch (IOException e) {
            close();
            notifyClosed(e);
        } catch (RuntimeException e) {
            close();
            notifyClosed(e);
        }
    }

    /**
     * Marks the transport closed, stops the reader and releases the socket.
     * Safe to call from any thread (including the Android main thread during
     * fragment teardown): the socket itself is always closed on a background
     * daemon thread because a TLS {@code close()} can flush/block and would
     * otherwise throw {@code NetworkOnMainThreadException}. State (closed
     * flag, streams) is updated synchronously so readers/senders observe the
     * close immediately.
     */
    @Override
    public void close() {
        closed = true;
        stopHeartbeat();
        if (readerExecutor != null) {
            readerExecutor.shutdownNow();
            readerExecutor = null;
        }
        final Socket s = socket;
        socket = null;
        output = null;
        input = null;
        if (s != null) {
            Thread t = new Thread(() -> closeQuietly(s), "creanger-realtime-close");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    // ---- internals ----

    private void readLoop() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        try {
            while (!closed) {
                InputStream in = this.input;
                if (in == null) {
                    return;
                }
                int n;
                try {
                    n = in.read(chunk);
                } catch (Exception e) {
                    if (closed) {
                        return;
                    }
                    throw e;
                }
                if (n < 0) {
                    break;
                }
                buffer.write(chunk, 0, n);
                byte[] data = buffer.toByteArray();
                int offset = 0;
                while (offset < data.length) {
                    WebSocketFrameCodec.Frame frame;
                    try {
                        frame = WebSocketFrameCodec.decode(data, offset, data.length - offset);
                    } catch (WebSocketFrameCodec.NotEnoughData e) {
                        byte[] rest = new byte[data.length - offset];
                        System.arraycopy(data, offset, rest, 0, rest.length);
                        buffer.reset();
                        buffer.write(rest);
                        break;
                    } catch (WebSocketFrameCodec.MalformedFrame e) {
                        throw new IOException("malformed websocket frame", e);
                    }
                    offset += frame.consumed;
                    buffer.reset();
                    if (offset < data.length) {
                        buffer.write(data, offset, data.length - offset);
                    }
                    if (!handleFrame(frame)) {
                        return;
                    }
                }
                if (closed) {
                    return;
                }
            }
            notifyClosed(null);
        } catch (IOException e) {
            if (!closed) {
                notifyClosed(e);
            }
        } catch (RuntimeException e) {
            if (!closed) {
                notifyClosed(e);
            }
        }
    }

    /** @return false to stop the read loop */
    private boolean handleFrame(WebSocketFrameCodec.Frame frame) throws IOException {
        switch (frame.opcode) {
            case WebSocketFrameCodec.OP_TEXT:
            case WebSocketFrameCodec.OP_BINARY: {
                String text = new String(frame.payload, StandardCharsets.UTF_8);
                Listener l = this.listener;
                if (l != null) {
                    l.onTransportFrame(text);
                }
                return true;
            }
            case WebSocketFrameCodec.OP_PING: {
                sendFrame(WebSocketFrameCodec.OP_PONG, frame.payload);
                return true;
            }
            case WebSocketFrameCodec.OP_PONG: {
                return true;
            }
            case WebSocketFrameCodec.OP_CLOSE: {
                int code = frame.closeCode;
                if (code == WebSocketFrameCodec.CLOSE_UNAUTHORIZED
                        || code == WebSocketFrameCodec.CLOSE_FORBIDDEN) {
                    close();
                    notifyUnauthorized("realtime closed (" + code + ")");
                } else {
                    close();
                    notifyClosed(null);
                }
                return false;
            }
            case WebSocketFrameCodec.OP_CONTINUATION:
            default:
                return true;
        }
    }

    private void sendJoin() throws IOException {
        final String join;
        try {
            join = buildJoinPayload();
        } catch (JSONException e) {
            throw new IOException("failed to build join frame", e);
        }
        sendFrame(WebSocketFrameCodec.OP_TEXT, join.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Pushes the user JWT as a channel {@code access_token} event (the
     * official client's Realtime Authorization step). The handshake alone
     * leaves the channel on the anon role, which makes the server reject
     * every row with {@code Error 401: Unauthorized} — this push is what
     * actually wires RLS to the subscription. No-op when the token is empty.
     */
    private void sendAccessToken(String accessToken) throws IOException {
        if (accessToken == null || accessToken.isEmpty()) {
            return;
        }
        final String frame;
        try {
            frame = buildAccessTokenPayload(accessToken, nextRef());
        } catch (JSONException e) {
            throw new IOException("failed to build access_token frame", e);
        }
        sendFrame(WebSocketFrameCodec.OP_TEXT, frame.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds the channel authorization frame. Public so the exact protocol
     * shape is unit-testable without a socket.
     */
    public static String buildAccessTokenPayload(String accessToken, String ref) throws JSONException {
        JSONObject envelope = new JSONObject();
        envelope.put("topic", RealtimeMessageParser.CHANNEL_TOPIC);
        envelope.put("event", "access_token");
        envelope.put("payload", new JSONObject().put("access_token", accessToken));
        envelope.put("ref", ref);
        return envelope.toString();
    }

    /**
     * Builds the Phoenix heartbeat frame (empty payload on the {@code
     * phoenix} topic). Public so the exact protocol shape is unit-testable
     * without a socket.
     */
    public static String buildHeartbeatPayload(String ref) throws JSONException {
        JSONObject envelope = new JSONObject();
        envelope.put("topic", "phoenix");
        envelope.put("event", "heartbeat");
        envelope.put("payload", new JSONObject());
        envelope.put("ref", ref);
        return envelope.toString();
    }

    private String nextRef() {
        return Long.toString(refCounter.incrementAndGet());
    }

    /** Starts the 25s Phoenix heartbeat; restarts cleanly when already running. */
    private void startHeartbeat() {
        stopHeartbeat();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "creanger-realtime-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor = executor;
        executor.scheduleAtFixedRate(() -> {
            if (closed) {
                return;
            }
            try {
                String frame = buildHeartbeatPayload(nextRef());
                sendFrame(WebSocketFrameCodec.OP_TEXT, frame.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                // A failed heartbeat means the socket is dead: tear down so
                // the client reconnects (which rejoins + re-authenticates).
                stopHeartbeat();
                close();
                notifyClosed(e instanceof IOException ? (IOException) e : new IOException(e));
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        ScheduledExecutorService executor = heartbeatExecutor;
        heartbeatExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Builds the Phoenix {@code phx_join} frame that subscribes to
     * {@code postgres_changes} on {@code public.messages} for INSERT, UPDATE
     * and DELETE — and, on the same channel, on {@code public.message_reactions}
     * for INSERT, UPDATE and DELETE so reaction adds/removes (migration 027)
     * flow to the parser alongside message events. UPDATE on messages delivers
     * edits and soft-delete tombstones; DELETE covers hard deletes. Public so
     * the subscription shape is unit-testable without a socket.
     */
    public static String buildJoinPayload() throws JSONException {
        JSONObject config = new JSONObject();
        JSONObject broadcast = new JSONObject();
        broadcast.put("self", false);
        broadcast.put("ack", false);
        config.put("broadcast", broadcast);
        config.put("presence", new JSONObject().put("key", ""));

        org.json.JSONArray changes = new org.json.JSONArray()
                .put(postgresChange(RealtimeMessageParser.EVENT_JOIN_INSERT, RealtimeMessageParser.TABLE_MESSAGES))
                .put(postgresChange(RealtimeMessageParser.EVENT_JOIN_UPDATE, RealtimeMessageParser.TABLE_MESSAGES))
                .put(postgresChange(RealtimeMessageParser.EVENT_JOIN_DELETE, RealtimeMessageParser.TABLE_MESSAGES))
                .put(postgresChange(RealtimeMessageParser.EVENT_JOIN_INSERT, RealtimeMessageParser.TABLE_MESSAGE_REACTIONS))
                .put(postgresChange(RealtimeMessageParser.EVENT_JOIN_UPDATE, RealtimeMessageParser.TABLE_MESSAGE_REACTIONS))
                .put(postgresChange(RealtimeMessageParser.EVENT_JOIN_DELETE, RealtimeMessageParser.TABLE_MESSAGE_REACTIONS));
        config.put("postgres_changes", changes);

        JSONObject join = new JSONObject();
        join.put("topic", RealtimeMessageParser.CHANNEL_TOPIC);
        join.put("event", "phx_join");
        join.put("payload", new JSONObject().put("config", config));
        join.put("ref", null);
        return join.toString();
    }

    private static JSONObject postgresChange(String event) throws JSONException {
        return postgresChange(event, RealtimeMessageParser.TABLE_MESSAGES);
    }

    private static JSONObject postgresChange(String event, String table) throws JSONException {
        JSONObject pg = new JSONObject();
        pg.put("event", event);
        pg.put("schema", RealtimeMessageParser.SCHEMA_PUBLIC);
        pg.put("table", table);
        return pg;
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        byte[] frame = WebSocketFrameCodec.clientFrame(opcode, payload);
        synchronized (writeLock) {
            OutputStream out = this.output;
            if (out == null || closed) {
                throw new IOException("realtime socket closed");
            }
            out.write(frame);
            out.flush();
        }
    }

    @Override
    public void sendBroadcast(String event, String payloadJson) throws IOException {
        if (closed) {
            return;
        }
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("topic", RealtimeMessageParser.CHANNEL_TOPIC);
            envelope.put("event", "broadcast");
            JSONObject payload = new JSONObject();
            payload.put("type", "broadcast");
            payload.put("event", event);
            payload.put("payload", new JSONObject(payloadJson));
            envelope.put("payload", payload);
            envelope.put("ref", null);
            sendFrame(WebSocketFrameCodec.OP_TEXT, envelope.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("failed to build broadcast frame", e);
        }
    }

    private void writeRaw(byte[] data) throws IOException {
        synchronized (writeLock) {
            if (output == null) {
                throw new IOException("realtime socket closed");
            }
            output.write(data);
            output.flush();
        }
    }

    private static String readResponseHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b == -1) {
                throw new IOException("realtime closed during handshake");
            }
            head.write(b);
            if (b == '\r' || b == '\n') {
                state++;
            } else {
                state = 0;
            }
        }
        return new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String urlEncode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private void notifyUnauthorized(String reason) {
        Listener l = this.listener;
        if (l != null) {
            l.onTransportUnauthorized(reason);
        }
    }

    private void notifyClosed(Throwable cause) {
        Listener l = this.listener;
        if (l != null) {
            l.onTransportClosed(cause);
        }
    }
}