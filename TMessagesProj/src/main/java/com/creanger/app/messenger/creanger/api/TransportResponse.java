package com.creanger.app.messenger.creanger.api;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Map;

/** Raw transport result (status line, body, headers of interest). */
public final class TransportResponse {

    public final int statusCode;
    public final String body;
    public final Map<String, String> headers;

    public TransportResponse(int statusCode, String body, @Nullable Map<String, String> headers) {
        this.statusCode = statusCode;
        this.body = body == null ? "" : body;
        this.headers = headers == null ? Collections.<String, String>emptyMap() : headers;
    }
}