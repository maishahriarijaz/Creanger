package com.creanger.app.messenger.creanger.api;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

import androidx.annotation.Nullable;

import com.creanger.app.messenger.creanger.CreangerAuthConfig;

/**
 * {@link CreangerHttpTransport} built on {@link HttpsURLConnection} — the same
 * primitives the rest of the app already uses (see ui/web/HttpPostTask). No new
 * HTTP dependency is introduced. HTTPS-only: the config enforces an https://
 * base URL, and non-HTTPS URLs are rejected before any connection is made.
 */
public class HttpsUrlConnectionTransport implements CreangerHttpTransport {

    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_REFRESH_TOKEN = "refreshToken";
    private static final String HEADER_CLIENT_TYPE = "x-client-type";
    private static final String HEADER_USER_AGENT = "User-Agent";
    private static final String MEDIA_TYPE_JSON = "application/json; charset=utf-8";

    private final TransportConfig config;
    @Nullable
    private final String baseUrlOverride;

    public HttpsUrlConnectionTransport(TransportConfig config) {
        this(config, null);
    }

    /**
     * @param baseUrlOverride optional explicit base URL (e.g. the chat/PostgREST
     *                        data plane); when null the auth base URL is used.
     */
    public HttpsUrlConnectionTransport(TransportConfig config, @Nullable String baseUrlOverride) {
        this.config = config;
        this.baseUrlOverride = baseUrlOverride;
    }

    private String effectiveBaseUrl() {
        return baseUrlOverride != null ? baseUrlOverride : config.getBaseUrl();
    }

    @Override
    public TransportResponse execute(ApiRequest request) throws IOException {
        String baseUrl = effectiveBaseUrl();
        if (!CreangerAuthConfig.isValidBaseUrl(baseUrl)) {
            throw new IOException("Creanger base URL must use HTTPS (loopback HTTP allowed in debug builds)");
        }

        String urlString = baseUrl + request.path;
        if (request.query != null && !request.query.isEmpty()) {
            StringBuilder sb = new StringBuilder(urlString).append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : request.query.entrySet()) {
                if (!first) {
                    sb.append('&');
                }
                first = false;
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            urlString = sb.toString();
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(request.method);
            connection.setConnectTimeout(config.getConnectTimeoutMillis());
            connection.setReadTimeout(config.getReadTimeoutMillis());
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);

            connection.setRequestProperty(HEADER_ACCEPT, MEDIA_TYPE_JSON);
            connection.setRequestProperty(HEADER_USER_AGENT, String.valueOf(config.getUserAgent()));
            connection.setRequestProperty(HEADER_CLIENT_TYPE, "mobile");
            // Supabase (GoTrue auth + PostgREST) requires the anon key on every
            // request; other endpoints ignore it.
            String anonKey = config.getSupabaseAnonKey();
            if (anonKey != null && !anonKey.isEmpty()) {
                connection.setRequestProperty("apikey", anonKey);
            }
            if (request.accessToken != null) {
                connection.setRequestProperty(HEADER_AUTHORIZATION, "Bearer " + request.accessToken);
            }
            if (request.idempotencyKey != null) {
                connection.setRequestProperty("Idempotency-Key", request.idempotencyKey);
            }

            // POST/PATCH carry JSON or binary payloads; PUT carries JSON
            // payloads (GoTrue user updates). GET/HEAD/DELETE/OPTIONS never
            // carry a body through this transport — all current DELETE call
            // sites are query-only.
            if (request.method.equals("POST") || request.method.equals("PATCH")
                    || request.method.equals("PUT")) {
                connection.setDoOutput(true);
                if (request.body != null) {
                    // Raw binary upload (Creanger Media API Edge Function). The
                    // explicit content type wins; JSON requests keep their default.
                    String contentType = request.contentType != null
                            ? request.contentType : "application/octet-stream";
                    connection.setRequestProperty(HEADER_CONTENT_TYPE, contentType);
                    try (OutputStream os = new BufferedOutputStream(connection.getOutputStream())) {
                        os.write(request.body);
                    }
                } else if (request.jsonBody != null) {
                    connection.setRequestProperty(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON);
                    try (OutputStream os = new BufferedOutputStream(connection.getOutputStream())) {
                        os.write(request.jsonBody.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }

            int statusCode = connection.getResponseCode();
            String body = readBody(connection);
            return new TransportResponse(statusCode, body, readRetryAfter(connection));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readBody(HttpURLConnection connection) throws IOException {
        InputStream stream;
        try {
            stream = connection.getInputStream();
        } catch (IOException e) {
            stream = connection.getErrorStream();
        }
        if (stream == null) {
            return "";
        }
        try (InputStream in = new BufferedInputStream(stream);
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static Map<String, String> readRetryAfter(HttpURLConnection connection) {
        String value = connection.getHeaderField("Retry-After");
        if (value == null || value.isEmpty()) {
            return null;
        }
        Map<String, String> headers = new HashMap<>(1);
        headers.put("Retry-After", value);
        return headers;
    }
}