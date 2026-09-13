package com.creanger.app.messenger.creanger.api;

import static com.creanger.app.messenger.creanger.api.Json.obj;
import static com.creanger.app.messenger.creanger.api.Json.put;

import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;

/**
 * Thin, typed client for Supabase Auth (GoTrue) — the ONLY authentication
 * backend of the app. All endpoints mirror the official Supabase Auth HTTP API
 * (nothing invented):
 *
 *  - POST /auth/v1/token?grant_type=password      email + password login
 *  - POST /auth/v1/token?grant_type=refresh_token session refresh (rotation)
 *  - POST /auth/v1/token?grant_type=id_token      Google Sign-In id token
 *  - POST /auth/v1/signup                         email + password registration
 *  - POST /auth/v1/verify                         email OTP verification
 *  - POST /auth/v1/resend                         resend signup/recovery OTP
 *  - POST /auth/v1/recover                        forgot password
 *  - GET  /auth/v1/user                           current user
 *  - PUT  /auth/v1/user                           update user (password/metadata)
 *  - POST /auth/v1/logout                         revoke current session
 *  - GET/PATCH /rest/v1/...                       PostgREST RPCs + profiles row
 *
 * Every method is synchronous and MUST be called off the main thread. The
 * transport base URL is the Supabase project URL; requests carry the anon key
 * in the {@code apikey} header (added by the transport) and the user JWT as a
 * Bearer token where required.
 */
public class SupabaseAuthClient {

    /** OTP purposes for verify/resend. Mirrors GoTrue verify {@code type}. */
    public static final String OTP_TYPE_SIGNUP = "signup";
    public static final String OTP_TYPE_RECOVERY = "recovery";

    private static final long DEFAULT_RESEND_SECONDS = 60;

    private final CreangerHttpTransport transport;

    public SupabaseAuthClient(CreangerHttpTransport transport) {
        this.transport = transport;
    }

    // ---- registration -------------------------------------------------------

    /**
     * Registers email + password. The username and display name travel in the
     * {@code data} (raw_user_meta_data) block so the DB trigger can build the
     * canonical users/profiles rows at confirm time.
     *
     * @return the session when the project has auto-confirm enabled, or
     *         {@code null} when an email confirmation OTP was sent first.
     */
    public AuthSession signUp(String email, String password, String username, String displayName)
            throws IOException, CreangerApiException {
        JSONObject body = put(obj(), "email", email);
        put(body, "password", password);
        JSONObject data = obj();
        put(data, "username", username);
        if (displayName != null && !displayName.isEmpty()) {
            put(data, "display_name", displayName);
        }
        put(body, "data", data);
        TransportResponse raw = execute("POST", "/auth/v1/signup", body.toString(), null);
        if (!isSuccess(raw)) {
            throw new CreangerApiException(raw.statusCode, parseError(raw));
        }
        return parseOptionalSession(raw.body);
    }

    /**
     * Confirms a signup OTP. Returns the freshly minted session.
     */
    public AuthSession verifyOtp(String type, String email, String token)
            throws IOException, CreangerApiException {
        JSONObject body = put(obj(), "type", type);
        put(body, "email", email);
        put(body, "token", token);
        TransportResponse raw = execute("POST", "/auth/v1/verify", body.toString(), null);
        if (!isSuccess(raw)) {
            throw new CreangerApiException(raw.statusCode, parseError(raw));
        }
        return parseSession(raw.body, raw.statusCode);
    }

    /**
     * Resends a signup/recovery OTP. Returns the cooldown the UI should apply.
     */
    public long resend(String type, String email) throws IOException, CreangerApiException {
        JSONObject body = put(obj(), "type", type);
        put(body, "email", email);
        TransportResponse raw = execute("POST", "/auth/v1/resend", body.toString(), null);
        if (!isSuccess(raw)) {
            throw new CreangerApiException(raw.statusCode, parseError(raw));
        }
        return DEFAULT_RESEND_SECONDS;
    }

    // ---- login ----------------------------------------------------------------

    /**
     * Email + password grant. {@code email} must already be resolved
     * (see {@link #resolveLoginEmail}); GoTrue answers with its generic
     * invalid-credentials error for unknown accounts.
     */
    public AuthSession passwordGrant(String email, String password)
            throws IOException, CreangerApiException {
        JSONObject body = put(obj(), "email", email);
        put(body, "password", password);
        return tokenGrant("password", body, null);
    }

    /**
     * Native Google Sign-In: exchanges the Google ID token for a Supabase
     * session. Auto-provisions the auth.users row for new Google emails.
     */
    public AuthSession idTokenGrant(String googleIdToken) throws IOException, CreangerApiException {
        JSONObject body = put(obj(), "provider", "google");
        put(body, "id_token", googleIdToken);
        return tokenGrant("id_token", body, null);
    }

    /**
     * Rotates the session. The previous refresh token is invalidated
     * server-side by GoTrue's rotation.
     */
    public AuthSession refreshTokenGrant(String refreshToken) throws IOException, CreangerApiException {
        JSONObject body = put(obj(), "refresh_token", refreshToken);
        return tokenGrant("refresh_token", body, null);
    }

    private AuthSession tokenGrant(String grantType, JSONObject body, String accessToken)
            throws IOException, CreangerApiException {
        Map<String, String> query = new java.util.LinkedHashMap<>();
        query.put("grant_type", grantType);
        TransportResponse raw = execute("POST", "/auth/v1/token", query, body.toString(), accessToken);
        if (!isSuccess(raw)) {
            throw new CreangerApiException(raw.statusCode, parseError(raw));
        }
        return parseSession(raw.body, raw.statusCode);
    }

    // ---- forgot / reset password ------------------------------------------------

    /**
     * Sends the recovery OTP to the email. Always succeeds with 200 for known
     * formatting-valid addresses (no account-existence leak).
     */
    public void recover(String email) throws IOException, CreangerApiException {
        TransportResponse raw = execute("POST", "/auth/v1/recover",
                put(obj(), "email", email).toString(), null);
        if (!isSuccess(raw)) {
            throw new CreangerApiException(raw.statusCode, parseError(raw));
        }
    }

    /**
     * Sets a new password after a recovery OTP verified via {@link #verifyOtp}
     * (which yields an authenticated session).
     */
    public void updateUserPassword(String accessToken, String newPassword)
            throws IOException, CreangerApiException {
        JSONObject body = put(obj(), "password", newPassword);
        TransportResponse raw = execute("PUT", "/auth/v1/user", body.toString(), accessToken);
        if (!isSuccess(raw)) {
            throw new CreangerApiException(raw.statusCode, parseError(raw));
        }
    }

    /**
     * Updates user metadata and/or password on the authenticated user
     * (PUT /auth/v1/user). Used by the Google new-account setup screen to set
     * display name + initial password.
     */
    public CreangerUser updateUser(String accessToken, @Nullable String password,
                                   @Nullable JSONObject metadata) throws IOException, CreangerApiException {
        JSONObject body = obj();
        if (password != null && !password.isEmpty()) {
            put(body, "password", password);
        }
        if (metadata != null) {
            put(body, "data", metadata);
        }
        TransportResponse raw = execute("PUT", "/auth/v1/user", body.toString(), accessToken);
        if (!isSuccess(raw)) {
            throw new CreangerApiException(raw.statusCode, parseError(raw));
        }
        try {
            return parseUser(new JSONObject(raw.body));
        } catch (JSONException e) {
            throw new CreangerApiException(raw.statusCode, new ApiError(
                    ApiError.INTERNAL_ERROR, "malformed user response", null, 0));
        }
    }

    // ---- session -----------------------------------------------------------------

    public CreangerUser getUser(String accessToken) throws IOException, CreangerApiException {
        TransportResponse raw = execute("GET", "/auth/v1/user", (String) null, accessToken);
        if (!isSuccess(raw)) {
            throw new CreangerApiException(raw.statusCode, parseError(raw));
        }
        try {
            return parseUser(new JSONObject(raw.body));
        } catch (JSONException e) {
            throw new CreangerApiException(raw.statusCode, new ApiError(
                    ApiError.INTERNAL_ERROR, "malformed user response", null, 0));
        }
    }

    /**
     * Revokes the current session server-side. Best-effort: callers clear the
     * local store regardless of the outcome.
     */
    public void signOut(String accessToken) throws IOException, CreangerApiException {
        TransportResponse raw = execute("POST", "/auth/v1/logout", "{}", accessToken);
        if (!isSuccess(raw)) {
            throw new CreangerApiException(raw.statusCode, parseError(raw));
        }
    }

    // ---- PostgREST: username RPCs + profile row -------------------------------------

    /**
     * Live availability check backed by SECURITY DEFINER RPC
     * {@code check_username_available(p_username)}.
     */
    public boolean checkUsername(String normalizedUsername) throws IOException, CreangerApiException {
        Map<String, String> query = new java.util.LinkedHashMap<>();
        query.put("p_username", normalizedUsername);
        TransportResponse raw = execute("GET", "/rest/v1/rpc/check_username_available", query, null, null);
        if (!isSuccess(raw)) {
            throw new CreangerApiException(raw.statusCode, parseError(raw));
        }
        String body = raw.body == null ? "" : raw.body.trim();
        return Boolean.parseBoolean(body);
    }

    /**
     * Server-side username -> email resolution backed by SECURITY DEFINER RPC
     * {@code resolve_login_email(p_identifier)}. Returns {@code null} when the
     * identifier is not a known username (or is itself an email); callers then
     * attempt the grant unchanged so GoTrue's generic error applies.
     */
    public String resolveLoginEmail(String identifier) throws IOException, CreangerApiException {
        Map<String, String> query = new java.util.LinkedHashMap<>();
        query.put("p_identifier", identifier);
        TransportResponse raw = execute("GET", "/rest/v1/rpc/resolve_login_email", query, null, null);
        if (!isSuccess(raw)) {
            throw new CreangerApiException(raw.statusCode, parseError(raw));
        }
        String body = raw.body == null ? "" : raw.body.trim();
        if (body.isEmpty() || "null".equals(body)) {
            // PostgREST renders a SQL NULL scalar result literally as `null`.
            return null;
        }
        try {
            Object value = new org.json.JSONTokener(body).nextValue();
            if (value instanceof String) {
                String email = ((String) value).trim();
                return email.isEmpty() ? null : email;
            }
            return null;
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Loads the profiles row for a user. Returns {@code null} when missing or
     * when the username has not been claimed yet.
     */
    @Nullable
    public ProfileRow getProfile(String accessToken, String userId) throws IOException, CreangerApiException {
        Map<String, String> query = new java.util.LinkedHashMap<>();
        query.put("user_id", "eq." + userId);
        query.put("select", "user_id,username,first_name,last_name,display_name");
        TransportResponse raw = execute("GET", "/rest/v1/profiles", query, null, accessToken);
        if (!isSuccess(raw)) {
            throw new CreangerApiException(raw.statusCode, parseError(raw));
        }
        try {
            JSONArray arr = new JSONArray(raw.body);
            if (arr.length() == 0) {
                return null;
            }
            JSONObject o = arr.getJSONObject(0);
            ProfileRow row = new ProfileRow(
                    o.optString("user_id", null),
                    o.optString("username", null),
                    o.optString("first_name", null),
                    o.optString("last_name", null),
                    o.optString("display_name", null));
            return row.username == null || row.username.isEmpty() ? null : row;
        } catch (JSONException e) {
            throw new CreangerApiException(raw.statusCode, new ApiError(
                    ApiError.INTERNAL_ERROR, "malformed profiles response", null, 0));
        }
    }

    /**
     * Claims/updates the caller's own profile row (RLS-scoped). Used by the
     * Google setup flow to persist username + names + agreement acceptance.
     *
     * @param termsAcceptedAtIso UTC ISO-8601 timestamp of agreement acceptance,
     *                           or null to leave any existing value untouched.
     */
    public void updateOwnProfile(String accessToken, String userId, String username, String firstName,
            @Nullable String displayName, @Nullable String termsAcceptedAtIso)
            throws IOException, CreangerApiException {
        Map<String, String> query = new java.util.LinkedHashMap<>();
        query.put("user_id", "eq." + userId);
        JSONObject body = put(obj(), "username", username);
        put(body, "first_name", firstName == null || firstName.isEmpty() ? "User" : firstName);
        if (displayName != null && !displayName.isEmpty()) {
            put(body, "display_name", displayName);
        }
        if (termsAcceptedAtIso != null && !termsAcceptedAtIso.isEmpty()) {
            put(body, "terms_accepted_at", termsAcceptedAtIso);
        }
        ApiRequest request = new ApiRequest("PATCH", "/rest/v1/profiles", query, body.toString(), accessToken, null);
        TransportResponse raw = transport.execute(request);
        if (!isSuccess(raw)) {
            throw new CreangerApiException(raw.statusCode, parseError(raw));
        }
    }

    // ---- parsing ------------------------------------------------------------------

    private static boolean isSuccess(TransportResponse raw) {
        return raw.statusCode >= 200 && raw.statusCode < 300;
    }

    private TransportResponse execute(String method, String path, String jsonBody, String accessToken)
            throws IOException {
        return execute(method, path, null, jsonBody, accessToken);
    }

    private TransportResponse execute(String method, String path, Map<String, String> query,
                                      String jsonBody, String accessToken) throws IOException {
        return transport.execute(new ApiRequest(method, path, query, jsonBody, accessToken, null));
    }

    /**
     * Parses a GoTrue token/session response:
     * {@code { access_token, refresh_token, user { id, email, user_metadata, ... } }}.
     */
    static AuthSession parseSession(String body, int statusCode) throws CreangerApiException {
        try {
            JSONObject root = new JSONObject(body);
            String access = root.optString("access_token", null);
            String refresh = root.optString("refresh_token", null);
            if (access == null || refresh == null) {
                throw new CreangerApiException(statusCode, new ApiError(
                        ApiError.INTERNAL_ERROR, "session response missing tokens", null, 0));
            }
            CreangerUser user = root.has("user") ? parseUser(root.optJSONObject("user")) : null;
            return new AuthSession(access, refresh, user, System.currentTimeMillis());
        } catch (JSONException e) {
            throw new CreangerApiException(statusCode, new ApiError(
                    ApiError.INTERNAL_ERROR, "malformed session response", null, 0));
        }
    }

    /**
     * Parses a signup response: either a full session (auto-confirm on) or a
     * bare user object (confirmation OTP sent).
     */
    @Nullable
    static AuthSession parseOptionalSession(String body) throws CreangerApiException {
        try {
            JSONObject root = new JSONObject(body);
            if (root.has("access_token") && root.has("refresh_token")) {
                return parseSession(body, 200);
            }
            // Confirmation required — no tokens yet.
            return null;
        } catch (JSONException e) {
            throw new CreangerApiException(200, new ApiError(
                    ApiError.INTERNAL_ERROR, "malformed signup response", null, 0));
        }
    }

    /**
     * Maps a GoTrue user into the app-side value type. Username/display name
     * come from user_metadata (mirrored into profiles by the DB trigger).
     */
    static CreangerUser parseUser(JSONObject u) {
        if (u == null) {
            return null;
        }
        JSONObject meta = u.optJSONObject("user_metadata");
        String username = meta != null ? meta.optString("username", null) : null;
        String displayName = meta != null ? meta.optString("display_name", null) : null;
        if (displayName == null && meta != null) {
            displayName = meta.optString("name", null);
        }
        return new CreangerUser(
                u.optString("id", null),
                emptyToNull(username),
                emptyToNull(u.optString("email", null)),
                !u.optString("email_confirmed_at", "").isEmpty(),
                emptyToNull(displayName),
                null,
                u.optString("created_at", null));
    }

    /**
     * Maps GoTrue/PostgREST errors onto stable {@link ApiError} codes:
     * invalid_grant (refresh reuse/expiry vs wrong credentials), rate limits,
     * duplicate email, OTP states. Unknown codes pass through verbatim.
     */
    static ApiError parseError(TransportResponse raw) {
        long retryAfterSeconds = JsonEnvelopeParser.parseRetryAfter(
                raw.headers != null ? raw.headers.get("Retry-After") : null);
        String code = null;
        String message = null;
        try {
            if (raw.body != null && !raw.body.isEmpty()) {
                JSONObject root = new JSONObject(raw.body);
                // GoTrue shapes: {"code":400,"error_code":"invalid_grant","msg":...}
                // (numeric code + symbolic error_code) or legacy OAuth2
                // {"error":"...","error_description":"..."}. The numeric code is
                // NOT the symbolic code — never map it through normalize().
                Object numericCode = root.opt("code");
                code = firstNonEmpty(root.optString("error_code", null),
                        root.optString("error", null),
                        numericCode instanceof String ? (String) numericCode : null);
                message = firstNonEmpty(root.optString("msg", null),
                        root.optString("error_description", null), root.optString("message", null));
            }
        } catch (JSONException ignored) {
            // fall through to generic mapping
        }
        if (raw.statusCode == 429) {
            code = ApiError.RATE_LIMITED;
        }
        if (raw.statusCode == 409 || "23505".equals(code)) {
            // PostgREST unique-violation on the profiles PATCH: a concurrent
            // claim won the username race. Surface it as USERNAME_TAKEN so the
            // setup screen can tell the user to pick another name.
            code = ApiError.USERNAME_TAKEN;
        }
        if (code == null) {
            code = ApiError.INTERNAL_ERROR;
        } else {
            code = normalize(code, message);
        }
        return new ApiError(code, message, null, retryAfterSeconds);
    }

    private static String normalize(String code, String message) {
        switch (code) {
            case "invalid_credentials":
            case "validation_failure":
                return ApiError.INVALID_CREDENTIALS;
            case "invalid_grant":
                if (message != null && message.toLowerCase().contains("refresh")) {
                    return ApiError.SESSION_EXPIRED_OR_REVOKED;
                }
                return ApiError.INVALID_CREDENTIALS;
            case "user_already_exists":
            case "email_exists":
                return ApiError.EMAIL_ALREADY_REGISTERED;
            case "otp_expired":
                return ApiError.OTP_EXPIRED;
            case "otp_disabled":
                return ApiError.OTP_INVALID;
            case "over_request_rate_limit":
            case "over_email_send_rate_limit":
            case "over_sms_send_rate_limit":
                return ApiError.RATE_LIMITED;
            case "weak_password":
                return ApiError.VALIDATION_ERROR;
            case "user_banned":
            case "user_not_found":
                return ApiError.ACCOUNT_NOT_FOUND;
            default:
                return code;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static String emptyToNull(@Nullable String v) {
        // Android's org.json returns the literal string "null" for JSON null
        // (unlike the reference impl used in JVM tests) — treat it as missing.
        return v == null || v.isEmpty() || "null".equals(v) ? null : v;
    }

    /** Minimal profiles-row projection used for new-Google-account detection. */
    public static final class ProfileRow {
        public final String userId;
        public final String username;
        @Nullable
        public final String firstName;
        @Nullable
        public final String lastName;
        @Nullable
        public final String displayName;

        public ProfileRow(String userId, @Nullable String username,
                          @Nullable String firstName, @Nullable String lastName,
                          @Nullable String displayName) {
            this.userId = userId;
            this.username = username;
            this.firstName = firstName;
            this.lastName = lastName;
            this.displayName = displayName;
        }
    }
}
