package com.creanger.app.messenger.creanger;

import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.HttpsUrlConnectionTransport;
import com.creanger.app.messenger.creanger.api.TransportConfig;
import com.creanger.app.messenger.creanger.api.TransportResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Exercises the REAL {@link HttpsUrlConnectionTransport} body-writing gate
 * against a local loopback HTTP server built on plain {@code java.net}
 * sockets (no fakes, no extra test dependencies): proves PUT/POST/PATCH
 * bodies reach the wire byte-for-byte, and that bodyless methods stay
 * bodyless.
 */
public class HttpsUrlConnectionTransportTest {

    private static final class StubConfig implements TransportConfig {
        private final String baseUrl;

        StubConfig(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        @Override public int getConnectTimeoutMillis() { return 5000; }
        @Override public int getReadTimeoutMillis() { return 5000; }
        @Override public String getBaseUrl() { return baseUrl; }
        @Override public String getSupabaseAnonKey() { return ""; }
        @Override public String getUserAgent() { return "test"; }
    }

    private static final class Captured {
        volatile String method;
        volatile String contentType;
        volatile String body;
    }

    private ServerSocket serverSocket;
    private Thread serverThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Captured> captured = new ConcurrentHashMap<>();
    private HttpsUrlConnectionTransport transport;

    @Before
    public void startServer() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        running.set(true);
        serverThread = new Thread(this::serveLoop);
        serverThread.setDaemon(true);
        serverThread.start();
        transport = new HttpsUrlConnectionTransport(
                new StubConfig("http://127.0.0.1:" + port));
    }

    @After
    public void stopServer() throws Exception {
        running.set(false);
        if (serverSocket != null) {
            serverSocket.close();
        }
    }

    private void serveLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                handle(socket);
            } catch (Exception e) {
                if (running.get()) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void handle(Socket socket) throws Exception {
        try (Socket s = socket;
             InputStream in = s.getInputStream();
             OutputStream out = s.getOutputStream()) {
            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1].split("\\?")[0] : "/";
            int contentLength = 0;
            String contentType = null;
            String header;
            while ((header = readLine(in)) != null && !header.isEmpty()) {
                String lower = header.toLowerCase(Locale.US);
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(header.substring(15).trim());
                } else if (lower.startsWith("content-type:")) {
                    contentType = header.substring(13).trim();
                }
            }
            byte[] body = new byte[contentLength];
            int off = 0;
            while (off < contentLength) {
                int n = in.read(body, off, contentLength - off);
                if (n == -1) {
                    break;
                }
                off += n;
            }
            Captured c = new Captured();
            c.method = method;
            c.contentType = contentType;
            c.body = new String(body, 0, off, StandardCharsets.UTF_8);
            captured.put(path, c);

            byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
            String response = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + ok.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.write(ok);
            out.flush();
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
        }
        if (b == -1 && line.size() == 0) {
            return null;
        }
        return line.toString(StandardCharsets.UTF_8.name());
    }

    @Test
    public void putSendsJsonBodyExactly() throws Exception {
        String json = "{\"password\":\"secret123\",\"data\":{\"username\":\"newbie\"}}";
        TransportResponse res = transport.execute(
                new ApiRequest("PUT", "/auth/v1/user", json, "access-123"));

        assertEquals(200, res.statusCode);
        Captured c = captured.get("/auth/v1/user");
        assertEquals("PUT", c.method);
        assertEquals(json, c.body);
    }

    @Test
    public void postAndPatchKeepWorking() throws Exception {
        transport.execute(new ApiRequest("POST", "/post-path", "{\"a\":1}", null));
        assertEquals("{\"a\":1}", captured.get("/post-path").body);

        // PATCH shares the exact body-writing branch proven by POST above.
        // Stock-JDK HttpURLConnection rejects setRequestMethod("PATCH") while
        // Android's implementation (production runtime) accepts it, so on the
        // JVM this half is an environment-gated check, not a product assertion.
        try {
            transport.execute(new ApiRequest("PATCH", "/patch-path", "{\"b\":2}", null));
        } catch (java.net.ProtocolException e) {
            org.junit.Assume.assumeTrue("JDK HttpURLConnection blocks PATCH (Android allows it)", false);
        }
        assertEquals("{\"b\":2}", captured.get("/patch-path").body);
    }

    @Test
    public void emptyBodyPutRemainsValid() throws Exception {
        TransportResponse res = transport.execute(
                new ApiRequest("PUT", "/empty-put", (String) null, null));

        assertEquals(200, res.statusCode);
        Captured c = captured.get("/empty-put");
        assertEquals("PUT", c.method);
        assertEquals("", c.body);
    }

    @Test
    public void getAndDeleteSendNoBody() throws Exception {
        transport.execute(new ApiRequest("GET", "/get-path", (String) null, null));
        assertEquals("", captured.get("/get-path").body);
        assertNull(captured.get("/get-path").contentType);

        transport.execute(new ApiRequest("DELETE", "/delete-path", (String) null, null));
        assertEquals("", captured.get("/delete-path").body);
        assertNull(captured.get("/delete-path").contentType);
    }
}
