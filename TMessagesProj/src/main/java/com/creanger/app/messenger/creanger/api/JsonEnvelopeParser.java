package com.creanger.app.messenger.creanger.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import com.creanger.app.messenger.creanger.model.ApiError;

import androidx.annotation.Nullable;

/**
 * Parses the Custom Auth Server response envelope:
 * {@code { success: boolean, data?: {...}, error?: { code, message, fields? } }}
 * and maps transport results into {@link ApiResponse}. Token/OTP/email values
 * are never logged here.
 */
public final class JsonEnvelopeParser {

    private JsonEnvelopeParser() {
    }

    public static ApiResponse parse(int statusCode, String body, @Nullable String retryAfter) {
        boolean ok = statusCode >= 200 && statusCode < 300;
        long retryAfterSeconds = parseRetryAfter(retryAfter);

        ApiError error = null;
        if (!ok) {
            String code = null;
            String message = null;
            String[] fieldErrors = null;
            try {
                if (body != null && !body.isEmpty()) {
                    JSONObject root = new JSONObject(body);
                    if (root.has("error")) {
                        JSONObject errorObj = root.optJSONObject("error");
                        if (errorObj != null) {
                            code = errorObj.optString("code", null);
                            message = errorObj.optString("message", null);
                            if (errorObj.has("fields")) {
                                JSONArray fields = errorObj.optJSONArray("fields");
                                if (fields != null && fields.length() > 0) {
                                    fieldErrors = new String[fields.length()];
                                    for (int i = 0; i < fields.length(); i++) {
                                        fieldErrors[i] = String.valueOf(fields.opt(i));
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (JSONException e) {
                // Malformed error body; fall through to generic INTERNAL_ERROR.
            }
            if (code == null) {
                code = ApiError.INTERNAL_ERROR;
            }
            error = new ApiError(code, message, fieldErrors, retryAfterSeconds);
        }

        return new ApiResponse(statusCode, ok, body == null ? "" : body, error);
    }

    /** Returns the {@code data} object from a success response, or null. */
    @Nullable
    public static JSONObject dataObject(String body) {
        try {
            if (body == null || body.isEmpty()) {
                return null;
            }
            JSONObject root = new JSONObject(body);
            if (!root.optBoolean("success", false)) {
                return null;
            }
            return root.optJSONObject("data");
        } catch (JSONException e) {
            return null;
        }
    }

    static long parseRetryAfter(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}