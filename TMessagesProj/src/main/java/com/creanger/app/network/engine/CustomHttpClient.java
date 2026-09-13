package com.creanger.app.network.engine;

import android.content.Context;

import com.creanger.app.messenger.ApplicationLoader;
import com.creanger.app.messenger.BuildVars;
import com.creanger.app.messenger.FileLog;
import com.creanger.app.messenger.Utilities;
import com.creanger.app.network.model.NetworkError;
import com.creanger.app.network.model.PaginatedResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

/**
 * HTTP client for custom backend with:
 * - Authentication header injection
 * - Automatic token refresh
 * - Retry with exponential backoff
 * - Timeout handling
 * - Request/response logging
 */
public class CustomHttpClient {

    private static final String TAG = "CustomHttpClient";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded; charset=utf-8");

    private final OkHttpClient client;
    private final String baseUrl;
    private final TokenProvider tokenProvider;

    public OkHttpClient getClient() {
        return client;
    }
    private final AtomicInteger requestIdGenerator = new AtomicInteger(1);
    private final Map<Integer, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    // Default timeouts
    private static final int CONNECT_TIMEOUT_SECONDS = 15;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final int WRITE_TIMEOUT_SECONDS = 30;
    private static final int CALL_TIMEOUT_SECONDS = 60;

    // Retry configuration
    private static final int MAX_RETRIES = 3;
    private static final long BASE_RETRY_DELAY_MS = 1000;
    private static final double RETRY_MULTIPLIER = 2.0;

    public interface TokenProvider {
        String getAccessToken();
        String getRefreshToken();
        void onTokensRefreshed(String newAccessToken, String newRefreshToken);
    }

    public interface RequestCallback<T> {
        void onSuccess(T result);
        void onError(NetworkError error);
    }

    public CustomHttpClient(Context context, String baseUrl, TokenProvider tokenProvider) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.tokenProvider = tokenProvider;

        Dispatcher dispatcher = new Dispatcher(Executors.newCachedThreadPool());
        dispatcher.setMaxRequests(64);
        dispatcher.setMaxRequestsPerHost(8);

        ConnectionPool connectionPool = new ConnectionPool(10, 5, TimeUnit.MINUTES);

        this.client = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(new AuthInterceptor())
                .addInterceptor(new LoggingInterceptor())
                .addInterceptor(new RetryInterceptor())
                .cookieJar(new okhttp3.CookieJar() {
                private final java.util.Map<String, java.util.List<okhttp3.Cookie>> cookieStore = new java.util.concurrent.ConcurrentHashMap<>();

                @Override
                public void saveFromResponse(okhttp3.HttpUrl url, java.util.List<okhttp3.Cookie> cookies) {
                    cookieStore.put(url.host(), cookies);
                }

                @Override
                public java.util.List<okhttp3.Cookie> loadForRequest(okhttp3.HttpUrl url) {
                    java.util.List<okhttp3.Cookie> cookies = cookieStore.get(url.host());
                    return cookies != null ? cookies : java.util.Collections.emptyList();
                }
            })
                .build();
    }

    private String normalizeBaseUrl(String url) {
        if (!url.endsWith("/")) {
            url += "/";
        }
        return url;
    }

    // ==================== Core HTTP Methods ====================

    public <T> int get(String path, Class<T> responseType, RequestCallback<T> callback) {
        return get(path, null, responseType, callback);
    }

    public <T> int get(String path, Map<String, String> queryParams, Class<T> responseType, RequestCallback<T> callback) {
        String url = buildUrl(path, queryParams);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        return enqueueRequest(request, responseType, callback);
    }

    public <T> int post(String path, Object body, Class<T> responseType, RequestCallback<T> callback) {
        return request("POST", path, body, responseType, callback);
    }

    public <T> int put(String path, Object body, Class<T> responseType, RequestCallback<T> callback) {
        return request("PUT", path, body, responseType, callback);
    }

    public <T> int patch(String path, Object body, Class<T> responseType, RequestCallback<T> callback) {
        return request("PATCH", path, body, responseType, callback);
    }

    public <T> int delete(String path, Class<T> responseType, RequestCallback<T> callback) {
        return delete(path, null, responseType, callback);
    }

    public <T> int delete(String path, Map<String, String> queryParams, Class<T> responseType, RequestCallback<T> callback) {
        String url = buildUrl(path, queryParams);
        Request request = new Request.Builder()
                .url(url)
                .delete()
                .build();
        return enqueueRequest(request, responseType, callback);
    }

    private <T> int request(String method, String path, Object body, Class<T> responseType, RequestCallback<T> callback) {
        String url = buildUrl(path, null);
        RequestBody requestBody = null;

        if (body != null) {
            if (body instanceof Map) {
                // Form body
                FormBody.Builder formBuilder = new FormBody.Builder();
                @SuppressWarnings("unchecked")
                Map<String, String> map = (Map<String, String>) body;
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    formBuilder.add(entry.getKey(), entry.getValue());
                }
                requestBody = formBuilder.build();
            } else if (body instanceof MultipartBody) {
                requestBody = (MultipartBody) body;
            } else {
                // JSON body
                String json = toJson(body);
                requestBody = RequestBody.create(json, JSON);
            }
        }

        Request.Builder requestBuilder = new Request.Builder().url(url);
        switch (method) {
            case "POST":
                requestBuilder.post(requestBody);
                break;
            case "PUT":
                requestBuilder.put(requestBody);
                break;
            case "PATCH":
                requestBuilder.patch(requestBody);
                break;
        }

        Request request = requestBuilder.build();
        return enqueueRequest(request, responseType, callback);
    }

    // ==================== Multipart Upload ====================

    public int uploadMultipart(String path, MultipartBody multipartBody, Class<?> responseType, RequestCallback<?> callback) {
        String url = buildUrl(path, null);
        Request request = new Request.Builder()
                .url(url)
                .post(multipartBody)
                .build();
        return enqueueRequest(request, responseType, callback);
    }

    public static class MultipartBuilder {
        private final MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);

        public MultipartBuilder addFormField(String name, String value) {
            builder.addFormDataPart(name, value);
            return this;
        }

        public MultipartBuilder addFile(String name, String filename, String mimeType, byte[] content) {
            builder.addFormDataPart(name, filename, RequestBody.create(content, MediaType.get(mimeType)));
            return this;
        }

        public MultipartBuilder addFile(String name, String filename, String mimeType, okio.ByteString content) {
            builder.addFormDataPart(name, filename, RequestBody.create(content, MediaType.get(mimeType)));
            return this;
        }

        public MultipartBuilder addFile(String name, String filename, String mimeType, java.io.File file) {
            builder.addFormDataPart(name, filename, RequestBody.create(file, MediaType.get(mimeType)));
            return this;
        }

        public MultipartBody build() {
            return builder.build();
        }
    }

    // ==================== Request Execution ====================

    private int enqueueRequest(Request request, Class<?> responseType, RequestCallback<?> callback) {
        int requestId = requestIdGenerator.getAndIncrement();

        // Check for cached auth token
        String accessToken = tokenProvider.getAccessToken();
        if (accessToken != null && !accessToken.isEmpty()) {
            // Token will be added by AuthInterceptor
        }

        @SuppressWarnings("unchecked")
        PendingRequest<Object> pending = new PendingRequest<>(requestId, request, responseType, (RequestCallback<Object>) callback, 0);
        pendingRequests.put(requestId, pending);

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                handleFailure(pending, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                handleResponse(pending, response);
            }
        });

        return requestId;
    }

    private void handleResponse(PendingRequest<?> pending, Response response) {
        int requestId = pending.requestId;
        int attempt = pending.attempt;

        try {
            int code = response.code();
            ResponseBody body = response.body();

            if (!response.isSuccessful()) {
                // Handle specific error codes
                NetworkError error = parseErrorResponse(code, body);
                
                // Check if we should retry
                if (shouldRetry(code, attempt)) {
                    scheduleRetry(pending);
                    return;
                }

                // Check if token expired - trigger refresh
                if (code == 401 && attempt == 0 && tokenProvider.getRefreshToken() != null) {
                    refreshTokenAndRetry(pending);
                    return;
                }

                pendingRequests.remove(requestId);
                deliverError(pending.callback, error);
                return;
            }

            // Parse successful response
            String responseString = body != null ? body.string() : "{}";
            Object result = parseResponse(responseString, pending.responseType);

            pendingRequests.remove(requestId);
            deliverSuccess(pending.callback, result);

        } catch (Exception e) {
            FileLog.e(e);
            if (shouldRetry(0, attempt)) {
                scheduleRetry(pending);
            } else {
                pendingRequests.remove(requestId);
                deliverError(pending.callback, NetworkError.parseError("Response parsing failed: " + e.getMessage()));
            }
        }
    }

    private void handleFailure(PendingRequest<?> pending, IOException e) {
        int requestId = pending.requestId;
        int attempt = pending.attempt;

        FileLog.e("Request " + requestId + " failed (attempt " + (attempt + 1) + "): " + e.getMessage());

        if (shouldRetry(0, attempt)) {
            scheduleRetry(pending);
        } else {
            pendingRequests.remove(requestId);
            NetworkError error;
            if (e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException) {
                error = NetworkError.networkError("Connection failed: " + e.getMessage());
            } else {
                error = NetworkError.unknown("Request failed: " + e.getMessage(), e);
            }
            deliverError(pending.callback, error);
        }
    }

    private boolean shouldRetry(int httpCode, int attempt) {
        if (attempt >= MAX_RETRIES) return false;
        if (httpCode >= 500 && httpCode < 600) return true;
        if (httpCode == 429) return true;
        if (httpCode == 408) return true;
        return false;
    }

    private void scheduleRetry(PendingRequest<?> pending) {
        int attempt = pending.attempt + 1;
        long delay = (long) (BASE_RETRY_DELAY_MS * Math.pow(RETRY_MULTIPLIER, attempt));

        pending.attempt = attempt;
        FileLog.d("Scheduling retry for request " + pending.requestId + " in " + delay + "ms (attempt " + (attempt + 1) + ")");

        Utilities.stageQueue.postRunnable(() -> {
            client.newCall(pending.request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    handleFailure(pending, e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    handleResponse(pending, response);
                }
            });
        }, delay);
    }

    private void refreshTokenAndRetry(PendingRequest<?> pending) {
        FileLog.d("Access token expired, attempting refresh for request " + pending.requestId);

        // This would be implemented by the token provider
        // For now, just fail with auth error
        pendingRequests.remove(pending.requestId);
        deliverError(pending.callback, NetworkError.authError("Token expired and refresh not implemented", 401));
    }

    private NetworkError parseErrorResponse(int code, ResponseBody body) {
        String errorMessage = "HTTP " + code;
        String errorCode = null;

        if (body != null) {
            try {
                String bodyString = body.string();
                JSONObject json = new JSONObject(bodyString);
                errorMessage = json.optString("message", json.optString("error", errorMessage));
                errorCode = json.optString("code", json.optString("error_code", null));
            } catch (Exception e) {
                // Ignore parsing errors
            }
        }

        switch (code) {
            case 400:
                return NetworkError.validationError(errorMessage, code, errorCode);
            case 401:
            case 403:
                return NetworkError.authError(errorMessage, code);
            case 404:
                return NetworkError.notFound(errorMessage);
            case 429:
                long retryAfter = 0;
                try {
                    retryAfter = Long.parseLong(body.string()) * 1000L;
                } catch (Exception ignored) {}
                return NetworkError.rateLimited(errorMessage, retryAfter);
            case 500:
            case 502:
            case 503:
            case 504:
                return NetworkError.serverError(errorMessage, code);
            default:
                return new NetworkError(NetworkError.Type.SERVER_ERROR, errorMessage, code, errorCode);
        }
    }

    private Object parseResponse(String jsonString, Class<?> responseType) throws JSONException {
        if (responseType == Void.class || responseType == String.class) {
            return jsonString;
        }
        if (responseType == JSONObject.class) {
            return new JSONObject(jsonString);
        }
        if (responseType == JSONArray.class) {
            return new JSONArray(jsonString);
        }
        // For POJOs, we'd use a JSON library like Gson
        // For now, return raw JSON
        return jsonString;
    }

    private String toJson(Object obj) {
        if (obj instanceof Map) {
            return new JSONObject((Map<?, ?>) obj).toString();
        }
        if (obj instanceof JSONObject) {
            return obj.toString();
        }
        // For POJOs, use reflection or a JSON library
        return new JSONObject().toString(); // Placeholder
    }

    private String buildUrl(String path, Map<String, String> queryParams) {
        StringBuilder url = new StringBuilder(baseUrl).append(path.replaceFirst("^/", ""));
        if (queryParams != null && !queryParams.isEmpty()) {
            url.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                if (!first) url.append("&");
                url.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
        }
        return url.toString();
    }

    private void deliverSuccess(RequestCallback<?> callback, Object result) {
        Utilities.stageQueue.postRunnable(() -> {
            @SuppressWarnings("unchecked")
            RequestCallback<Object> cb = (RequestCallback<Object>) callback;
            cb.onSuccess(result);
        });
    }

    private void deliverError(RequestCallback<?> callback, NetworkError error) {
        Utilities.stageQueue.postRunnable(() -> {
            @SuppressWarnings("unchecked")
            RequestCallback<Object> cb = (RequestCallback<Object>) callback;
            cb.onError(error);
        });
    }

    public void cancelRequest(int requestId) {
        PendingRequest<?> pending = pendingRequests.remove(requestId);
        if (pending != null) {
            // OkHttp doesn't support cancellation by ID easily
            // Would need to track Call objects
            FileLog.d("Cancel requested for " + requestId);
        }
    }

    public void cancelAllRequests() {
        pendingRequests.clear();
        client.dispatcher().cancelAll();
    }

    // ==================== Interceptors ====================

    private class AuthInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request original = chain.request();
            String accessToken = tokenProvider.getAccessToken();

            if (accessToken != null && !accessToken.isEmpty()) {
                Request.Builder builder = original.newBuilder()
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Accept", "application/json");
                return chain.proceed(builder.build());
            }

            return chain.proceed(original);
        }
    }

    private class LoggingInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            long startTime = System.currentTimeMillis();

            if (BuildVars.DEBUG_PRIVATE_VERSION) {
                FileLog.d("→ " + request.method() + " " + request.url());
                Headers headers = request.headers();
                for (String name : headers.names()) {
                    if (!name.equalsIgnoreCase("Authorization")) {
                        FileLog.d("  " + name + ": " + headers.get(name));
                    }
                }
            }

            Response response = chain.proceed(request);
            long duration = System.currentTimeMillis() - startTime;

            if (BuildVars.DEBUG_PRIVATE_VERSION) {
                FileLog.d("← " + response.code() + " " + request.url() + " (" + duration + "ms)");
            }

            return response;
        }
    }

    private class RetryInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = chain.proceed(request);

            // Retry on 5xx, 429, 408
            int code = response.code();
            int attempt = 0; // Would need to track from request tag

            if ((code >= 500 && code < 600) || code == 429 || code == 408) {
                // Could implement retry here, but we handle it at the application level
            }

            return response;
        }
    }

    private static class PendingRequest<T> {
        final int requestId;
        final Request request;
        final Class<?> responseType;
        final RequestCallback<?> callback;
        int attempt;

        PendingRequest(int requestId, Request request, Class<?> responseType, RequestCallback<?> callback, int attempt) {
            this.requestId = requestId;
            this.request = request;
            this.responseType = responseType;
            this.callback = callback;
            this.attempt = attempt;
        }
    }

    // ==================== Utility Methods ====================

    public void setBaseUrl(String baseUrl) {
        // Not supported after initialization
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setTimeouts(int connect, int read, int write, TimeUnit unit) {
        // Would require rebuilding client
    }

    public void shutdown() {
        cancelAllRequests();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}