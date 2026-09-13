package com.creanger.app.messenger.creanger.api;

import com.creanger.app.messenger.creanger.model.ApiError;

import androidx.annotation.Nullable;

/**
 * Parsed transport-level result of a single HTTP request to the Custom Auth
 * Server. The client inspects {@link #ok} and {@link #error} rather than raw
 * HTTP codes; the envelope contract is enforced by the request layer.
 */
public final class ApiResponse {

    public final int statusCode;
    public final boolean ok;
    public final String body;
    public final ApiError error;

    public ApiResponse(int statusCode, boolean ok, String body, @Nullable ApiError error) {
        this.statusCode = statusCode;
        this.ok = ok;
        this.body = body;
        this.error = error;
    }

    public boolean isSuccess() {
        return ok && error == null;
    }
}