package com.creanger.app.messenger.creanger.api;

import com.creanger.app.messenger.creanger.model.ApiError;

import androidx.annotation.Nullable;

import java.util.Map;

/**
 * A single HTTP request to the Creanger Custom Auth Server. Immutable value
 * that the transport executes.
 */
public final class ApiRequest {

    public final String method; // GET, POST, PATCH, DELETE
    public final String path;   // "/auth/v1/token"
    @Nullable
    public final Map<String, String> query;
    @Nullable
    public final String jsonBody; // null for GET
    @Nullable
    public final String accessToken; // Authorization: Bearer, null for anonymous
    @Nullable
    public final String idempotencyKey;
    /**
     * Raw binary request body (e.g. an image upload to the Creanger Media API
     * {@code /v1/media/upload-image}). When present it takes
     * precedence over {@link #jsonBody}; the transport writes the bytes verbatim
     * with {@link #contentType} as the Content-Type.
     */
    @Nullable
    public final byte[] body;
    @Nullable
    public final String contentType;

    public ApiRequest(String method, String path, String jsonBody, String accessToken) {
        this(method, path, null, jsonBody, accessToken, null);
    }

    public ApiRequest(String method, String path, @Nullable Map<String, String> query,
                      @Nullable String jsonBody, @Nullable String accessToken, @Nullable String idempotencyKey) {
        this(method, path, query, jsonBody, accessToken, idempotencyKey, null, null);
    }

    public ApiRequest(String method, String path, @Nullable Map<String, String> query,
                      @Nullable String jsonBody, @Nullable String accessToken, @Nullable String idempotencyKey,
                      @Nullable byte[] body, @Nullable String contentType) {
        this.method = method;
        this.path = path;
        this.query = query;
        this.jsonBody = jsonBody;
        this.accessToken = accessToken;
        this.idempotencyKey = idempotencyKey;
        this.body = body;
        this.contentType = contentType;
    }
}