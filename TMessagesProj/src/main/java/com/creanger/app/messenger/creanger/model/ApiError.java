package com.creanger.app.messenger.creanger.model;

import androidx.annotation.Nullable;

/**
 * Server error contract mirrored from the Custom Auth Server
 * ({@code src/domain/errors.ts} + {@code src/middleware/errorHandler.ts}).
 *
 * Wire format: {@code { success: false, error: { code, message, fields? } }}
 * Certain codes (OTP_COOLDOWN_ACTIVE, RATE_LIMITED) ship with a Retry-After
 * response header which the request layer maps into {@link #retryAfterSeconds}.
 */
public final class ApiError {

    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String OTP_EXPIRED = "OTP_EXPIRED";
    public static final String OTP_MAX_ATTEMPTS = "OTP_MAX_ATTEMPTS";
    public static final String OTP_INVALID = "OTP_INVALID";
    public static final String OTP_COOLDOWN_ACTIVE = "OTP_COOLDOWN_ACTIVE";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String USERNAME_TAKEN = "USERNAME_TAKEN";
    public static final String TOKEN_INVALID = "TOKEN_INVALID";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String TOKEN_REUSE_DETECTED = "TOKEN_REUSE_DETECTED";
    public static final String SESSION_EXPIRED_OR_REVOKED = "SESSION_EXPIRED_OR_REVOKED";
    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    public static final String MISSING_TOKEN = "MISSING_TOKEN";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    public final String code;
    public final String message;
    @Nullable
    public final String[] fieldErrors;
    public final long retryAfterSeconds;

    public ApiError(String code, String message, @Nullable String[] fieldErrors, long retryAfterSeconds) {
        this.code = code;
        this.message = message == null ? "" : message;
        this.fieldErrors = fieldErrors;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public boolean is(String code) {
        return this.code != null && this.code.equals(code);
    }

    @Override
    public String toString() {
        // Never include fieldErrors contents that might echo user input into logs beyond a count.
        return "ApiError{code='" + code + "', message='" + message + "', fields="
                + (fieldErrors == null ? 0 : fieldErrors.length) + ", retryAfterSeconds=" + retryAfterSeconds + '}';
    }
}