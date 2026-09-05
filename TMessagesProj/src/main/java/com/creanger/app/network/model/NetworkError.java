package com.creanger.app.network.model;

import androidx.annotation.NonNull;

/**
 * Unified error model for custom backend network operations.
 * Does not expose raw HTTP/WebSocket implementation details.
 */
public class NetworkError extends Exception {

    public enum Type {
        NETWORK_ERROR,        // No connectivity, DNS failure, timeout
        AUTH_ERROR,           // 401, 403 - token expired, invalid credentials
        VALIDATION_ERROR,     // 400 - invalid request parameters
        SERVER_ERROR,         // 5xx - backend internal error
        NOT_FOUND,            // 404 - resource not found
        RATE_LIMITED,         // 429 - too many requests
        CONNECTION_ERROR,     // WebSocket connection failed
        TIMEOUT_ERROR,        // Request timeout
        PARSE_ERROR,          // Response parsing failed
        UNKNOWN               // Unexpected error
    }

    private final Type type;
    private final int httpStatusCode;
    private final String errorCode;       // Backend-specific error code
    private final boolean retryable;
    private final long retryAfterMillis;  // For rate limiting

    public NetworkError(@NonNull Type type, @NonNull String message) {
        this(type, message, 0, null, false, 0);
    }

    public NetworkError(@NonNull Type type, @NonNull String message, int httpStatusCode) {
        this(type, message, httpStatusCode, null, false, 0);
    }

    public NetworkError(@NonNull Type type, @NonNull String message, int httpStatusCode, String errorCode) {
        this(type, message, httpStatusCode, errorCode, false, 0);
    }

    public NetworkError(@NonNull Type type, @NonNull String message, int httpStatusCode, String errorCode, boolean retryable, long retryAfterMillis) {
        super(message);
        this.type = type;
        this.httpStatusCode = httpStatusCode;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.retryAfterMillis = retryAfterMillis;
    }

    public static NetworkError networkError(@NonNull String message) {
        return new NetworkError(Type.NETWORK_ERROR, message, 0, null, true, 0);
    }

    public static NetworkError timeoutError(@NonNull String message) {
        return new NetworkError(Type.TIMEOUT_ERROR, message, 0, null, true, 0);
    }

    public static NetworkError authError(@NonNull String message, int httpStatusCode) {
        return new NetworkError(Type.AUTH_ERROR, message, httpStatusCode, null, false, 0);
    }

    public static NetworkError validationError(@NonNull String message, int httpStatusCode, String errorCode) {
        return new NetworkError(Type.VALIDATION_ERROR, message, httpStatusCode, errorCode, false, 0);
    }

    public static NetworkError serverError(@NonNull String message, int httpStatusCode) {
        return new NetworkError(Type.SERVER_ERROR, message, httpStatusCode, null, true, 0);
    }

    public static NetworkError notFound(@NonNull String message) {
        return new NetworkError(Type.NOT_FOUND, message, 404, null, false, 0);
    }

    public static NetworkError rateLimited(@NonNull String message, long retryAfterMillis) {
        return new NetworkError(Type.RATE_LIMITED, message, 429, "RATE_LIMITED", true, retryAfterMillis);
    }

    public static NetworkError connectionError(@NonNull String message) {
        return new NetworkError(Type.CONNECTION_ERROR, message, 0, null, true, 0);
    }

    public static NetworkError parseError(@NonNull String message) {
        return new NetworkError(Type.PARSE_ERROR, message, 0, null, false, 0);
    }

    public static NetworkError unknown(@NonNull String message, Throwable cause) {
        NetworkError error = new NetworkError(Type.UNKNOWN, message, 0, null, false, 0);
        error.initCause(cause);
        return error;
    }

    @NonNull
    public Type getType() {
        return type;
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }

    public boolean isAuthError() {
        return type == Type.AUTH_ERROR || httpStatusCode == 401 || httpStatusCode == 403;
    }

    public boolean isNetworkError() {
        return type == Type.NETWORK_ERROR || type == Type.CONNECTION_ERROR || type == Type.TIMEOUT_ERROR;
    }

    @Override
    @NonNull
    public String toString() {
        return "NetworkError{" +
                "type=" + type +
                ", httpStatusCode=" + httpStatusCode +
                ", errorCode='" + errorCode + '\'' +
                ", retryable=" + retryable +
                ", retryAfterMillis=" + retryAfterMillis +
                ", message='" + getMessage() + '\'' +
                '}';
    }
}