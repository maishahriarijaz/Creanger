package com.creanger.app.messenger.creanger.api;

import com.creanger.app.messenger.creanger.model.ApiError;

/**
 * Thrown when the Custom Auth Server returned a non-2xx reply. Carries the
 * structured {@link ApiError} (code/message/retry-after) so callers can branch
 * on e.g. OTP_INVALID vs OTP_COOLDOWN_ACTIVE.
 */
public class CreangerApiException extends Exception {

    public final ApiError error;
    public final int statusCode;

    public CreangerApiException(int statusCode, ApiError error) {
        super(error != null && error.message != null ? error.message : "Creanger auth error");
        this.statusCode = statusCode;
        this.error = error;
    }

    public boolean is(String code) {
        return error != null && error.is(code);
    }
}