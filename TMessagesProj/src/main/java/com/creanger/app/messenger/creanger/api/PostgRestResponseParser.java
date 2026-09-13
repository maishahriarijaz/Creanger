package com.creanger.app.messenger.creanger.api;

import org.json.JSONException;
import org.json.JSONObject;
import com.creanger.app.messenger.creanger.model.ApiError;

import androidx.annotation.Nullable;

/**
 * Response interpretation for the PostgREST REST data plane (chats,
 * chat_members). PostgREST does NOT use the Custom Auth Server envelope
 * ({@code { success, data, error }}): successful list requests return a raw
 * JSON array while failures carry an HTTP status plus
 * {@code { code, message, details, hint }} (PostgREST error shape).
 *
 * The auth flow for these requests is the short-lived access-token JWT sent as
 * a Bearer token; Row Level Security then scopes rows to
 * {@code request.jwt.claims -> 'sub'} (see {@code current_user_id()} in
 * migration 001). Errors therefore surface RLS/JWT layers (401/403) as
 * {@link ApiError#UNAUTHORIZED}.
 */
public final class PostgRestResponseParser {

    private PostgRestResponseParser() {
    }

    /**
     * Interprets a PostgREST result. {@link ApiError} is null when the request
     * succeeded (2xx); otherwise it carries a stable code for branching.
     */
    @Nullable
    public static ApiError interpretError(int statusCode, @Nullable String body) {
        boolean ok = statusCode >= 200 && statusCode < 300;
        if (ok) {
            return null;
        }
        if (statusCode == 401 || statusCode == 403) {
            return new ApiError(ApiError.UNAUTHORIZED, messageOf(body, "not authorized"), null, 0);
        }
        String code = null;
        String message = null;
        try {
            if (body != null && !body.isEmpty()) {
                JSONObject root = new JSONObject(body);
                code = root.optString("code", null);
                message = root.optString("message", null);
            }
        } catch (JSONException e) {
            // Malformed error body; fall through to generic INTERNAL_ERROR.
        }
        if (code == null || code.isEmpty()) {
            code = ApiError.INTERNAL_ERROR;
        }
        return new ApiError(code, message, null, 0);
    }

    @Nullable
    private static String messageOf(@Nullable String body, String fallback) {
        try {
            if (body != null && !body.isEmpty()) {
                JSONObject root = new JSONObject(body);
                return root.optString("message", fallback);
            }
        } catch (JSONException ignored) {
            // fall through
        }
        return fallback;
    }
}