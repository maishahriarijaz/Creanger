package com.creanger.app.messenger.creanger.auth;

import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.model.AuthModels.ForgotPasswordResult;
import com.creanger.app.messenger.creanger.model.AuthModels.GoogleAuthResult;
import com.creanger.app.messenger.creanger.model.AuthModels.SignupResult;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * The Creanger auth engine on top of Supabase Auth (GoTrue) — the ONLY
 * authentication/session backend.
 *
 * Responsibilities:
 *  - Own the {@link AuthState} state machine.
 *  - Track the in-memory {@link AuthSession} (access token memory-only).
 *  - Persist the refresh token via {@link CreangerTokenStore}.
 *  - Implement a SINGLE-FLIGHT refresh: concurrent access-token requests while
 *    a refresh is in flight all await the same future instead of firing
 *    parallel refreshes (GoTrue rotates refresh tokens, so parallel refreshes
 *    would trip reuse detection).
 *
 * Flows:
 *  - Login: username OR email + password. Username -> email resolution happens
 *    SERVER-SIDE via a SECURITY DEFINER RPC ({@code resolve_login_email}); an
 *    unresolved identifier goes to GoTrue unchanged so unknown accounts fail
 *    with the generic invalid-credentials answer (no existence leak).
 *  - Register: email + username + password signup, then email OTP verification
 *    (POST /auth/v1/verify type=signup).
 *  - Google: native id token grant auto-provisions the auth.users row; a
 *    missing profiles.username flags the account as needing setup.
 *
 * This class performs blocking IO (via the api client) and MUST be driven from
 * a background thread; UI callers go through {@code CreangerAuthAsync}.
 */
public final class CreangerAuthEngine {

    private final SupabaseAuthClient api;
    private final CreangerTokenStore tokenStore;
    private final Object lock = new Object();

    private volatile AuthState state = AuthState.UNINITIALIZED;
    private volatile AuthSession session; // memory-only access token + refresh token handle
    private volatile SimpleFuture<AuthSession> refreshInFlight;

    public CreangerAuthEngine(SupabaseAuthClient api, CreangerTokenStore tokenStore) {
        this.api = api;
        this.tokenStore = tokenStore;
        this.session = tokenStore.load();
        if (session != null && session.refreshToken != null && !session.refreshToken.isEmpty()) {
            // Access token deliberately unset on load — memory-only.
            state = AuthState.AUTHENTICATED;
        } else {
            state = AuthState.UNINITIALIZED;
            session = null;
        }
    }

    public AuthState getState() {
        return state;
    }

    // ---- registration (email + username + password, then email verification) ----

    /**
     * Creates the auth.users account with username/display name metadata. When
     * the project requires email confirmation the returned session is null and
     * {@link #verifyRegister(String, String)} completes the flow.
     */
    public SignupResult registerWithPassword(String email, String username, String password)
            throws IOException, CreangerApiException {
        setAuthenticating();
        try {
            AuthSession result = api.signUp(email, password, username, null);
            if (result != null) {
                adoptSession(result);
            } else {
                // Await email confirmation; nothing is stored until verified.
                setUnauthenticatedQuietly();
            }
            return new SignupResult(result);
        } catch (IOException | CreangerApiException e) {
            setUnauthenticatedQuietly();
            throw e;
        }
    }

    /** Confirms the signup OTP and adopts the resulting session. */
    public AuthSession verifyRegister(String email, String otp)
            throws IOException, CreangerApiException {
        try {
            AuthSession result = api.verifyOtp(SupabaseAuthClient.OTP_TYPE_SIGNUP, email, otp);
            adoptSession(result);
            return result;
        } catch (IOException | CreangerApiException e) {
            setUnauthenticatedQuietly();
            throw e;
        }
    }

    /** Resends the signup confirmation OTP. */
    public void resendOtp(String email) throws IOException, CreangerApiException {
        api.resend(SupabaseAuthClient.OTP_TYPE_SIGNUP, email);
    }

    // ---- login (username or email + password) ------------------------------------

    public AuthSession loginWithPassword(String identifier, String password)
            throws IOException, CreangerApiException {
        setAuthenticating();
        try {
            String email = resolveEmailForLogin(identifier);
            AuthSession result = api.passwordGrant(email, password);
            adoptSession(result);
            return result;
        } catch (IOException | CreangerApiException e) {
            setUnauthenticatedQuietly();
            throw e;
        }
    }

    /**
     * Server-side resolution only: pure usernames are mapped through the
     * SECURITY DEFINER RPC; emails (and unresolved usernames) are handed to
     * GoTrue as-is so failures stay generic invalid-credential answers.
     */
    private String resolveEmailForLogin(String identifier) throws IOException, CreangerApiException {
        if (identifier.indexOf('@') >= 0) {
            return identifier;
        }
        String resolved = api.resolveLoginEmail(identifier);
        return resolved != null ? resolved : identifier;
    }

    // ---- Google -----------------------------------------------------------------

    /**
     * Signs in with a Google ID token. Supabase auto-provisions new accounts;
     * when the profiles row has no username yet the result reports
     * needsRegistration and the UI shows the account setup screen. The session
     * is adopted either way.
     */
    public GoogleAuthResult googleAuth(String googleIdToken) throws IOException, CreangerApiException {
        setAuthenticating();
        try {
            AuthSession result = api.idTokenGrant(googleIdToken);
            adoptSession(result);

            boolean needsRegistration = false;
            CreangerUser merged = result.user;
            if (result.user != null && result.user.id != null) {
                SupabaseAuthClient.ProfileRow profile = api.getProfile(result.accessToken, result.user.id);
                if (profile == null) {
                    needsRegistration = true;
                } else {
                    merged = mergeProfile(result.user, profile.username,
                            profile.firstName, profile.lastName);
                }
            }
            if (merged != result.user && merged != null) {
                session = session.copyWithUser(merged);
            }
            return new GoogleAuthResult(needsRegistration, session, null);
        } catch (IOException | CreangerApiException e) {
            setUnauthenticatedQuietly();
            throw e;
        }
    }

    /**
     * Completes a new-Google-account setup against the ALREADY-ACTIVE session:
     * sets display name + initial password via PUT /auth/v1/user and claims the
     * username on the own profiles row (RLS-scoped PATCH).
     */
    public AuthSession googleAuthComplete(String username, @Nullable String displayName,
                                          @Nullable String password)
            throws IOException, CreangerApiException {
        AuthSession current = this.session;
        if (current == null || current.user == null || current.user.id == null) {
            throw new CreangerApiException(0, new ApiError(
                    ApiError.MISSING_TOKEN, "no active Google session", null, 0));
        }
        org.json.JSONObject meta = new org.json.JSONObject();
        try {
            meta.put("username", username);
            if (displayName != null && !displayName.isEmpty()) {
                meta.put("display_name", displayName);
            }
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
        CreangerUser updatedRemote = api.updateUser(current.accessToken, password, meta);
        api.updateOwnProfile(current.accessToken, current.user.id, username, displayName);

        CreangerUser merged = mergeProfile(
                updatedRemote != null ? updatedRemote : current.user,
                username, displayName, null);
        AuthSession next = current.copyWithUser(merged);
        synchronized (lock) {
            this.session = next;
        }
        tokenStore.store(next);
        return next;
    }

    // ---- forgot / reset password --------------------------------------------------

    /** Sends the recovery OTP email. Always succeeds generically (no leak). */
    public ForgotPasswordResult forgotPassword(String email) throws IOException, CreangerApiException {
        api.recover(email);
        return new ForgotPasswordResult(true, DEFAULT_RESEND_SECONDS);
    }

    private static final long DEFAULT_RESEND_SECONDS = 60;

    /**
     * Verifies the recovery OTP, adopts the session it mints and updates the
     * password on the authenticated user (the standard Supabase recovery flow;
     * the user ends up signed in on this device).
     */
    public AuthSession resetPassword(String email, String otp, String newPassword)
            throws IOException, CreangerApiException {
        AuthSession verified = api.verifyOtp(SupabaseAuthClient.OTP_TYPE_RECOVERY, email, otp);
        adoptSession(verified);
        api.updateUserPassword(verified.accessToken, newPassword);
        return verified;
    }

    // ---- username availability -----------------------------------------------------

    /**
     * Live availability check backed by the SECURITY DEFINER RPC. Blocking;
     * must run off the main thread (UI goes through {@code CreangerAuthAsync}).
     */
    public boolean checkUsername(String normalizedUsername) throws IOException, CreangerApiException {
        return api.checkUsername(normalizedUsername);
    }

    // ---- access token + single-flight refresh ---------------------------------------

    /**
     * Returns a usable access token, refreshing when the in-memory one is
     * absent/expired. Single-flight: concurrent callers share one refresh.
     */
    public String requireAccessToken() throws IOException, CreangerApiException {
        AuthSession current = this.session;
        if (current != null && hasFreshAccessToken(current)) {
            return current.accessToken;
        }
        AuthSession refreshed = refreshSession();
        return refreshed.accessToken;
    }

    // Conservative freshness window. GoTrue's default JWT expiry is 3600s; any
    // custom expiry below this window would require parsing expires_in instead.
    private static final long ACCESS_TOKEN_TTL_MS = 50 * 60 * 1000;

    private static boolean hasFreshAccessToken(AuthSession session) {
        if (session.accessToken == null || session.accessToken.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        // Any token in memory is live until the TTL elapses; conservative
        // clock-skew margin is included in the window above.
        return session.refreshedAtEpochMs != 0 && (now - session.refreshedAtEpochMs) < ACCESS_TOKEN_TTL_MS;
    }

    /**
     * Single-flight token rotation (grant_type=refresh_token). The network call
     * runs OUTSIDE the session lock so concurrent callers block on the shared
     * future instead of firing duplicate refreshes (GoTrue invalidates the old
     * refresh token on rotation; reuse would kill the whole session family).
     */
    public AuthSession refreshSession() throws IOException, CreangerApiException {
        SimpleFuture<AuthSession> inFlightToAwait;
        String refreshToken;
        CreangerUser userToKeep;
        synchronized (lock) {
            AuthSession current = this.session;
            if (current != null && hasFreshAccessToken(current)) {
                return current;
            }
            if (current == null || current.refreshToken == null || current.refreshToken.isEmpty()) {
                state = AuthState.UNAUTHENTICATED;
                throw new CreangerApiException(0, new ApiError(ApiError.TOKEN_INVALID, "no refresh token available", null, 0));
            }
            SimpleFuture<AuthSession> inFlight = refreshInFlight;
            if (inFlight != null) {
                // Another thread is refreshing; wait for its result OUTSIDE the lock.
                inFlightToAwait = inFlight;
                refreshToken = null;
                userToKeep = null;
            } else {
                inFlightToAwait = null;
                refreshToken = current.refreshToken;
                userToKeep = current.user;
                refreshInFlight = new SimpleFuture<>();
                state = AuthState.REFRESHING;
            }
        }

        if (inFlightToAwait != null) {
            return awaitFuture(inFlightToAwait);
        }

        try {
            AuthSession rotated = api.refreshTokenGrant(refreshToken);
            // The refresh response does echo the user, but keep the cached
            // profile when absent so a rotation never wipes current-user state.
            if (rotated.user == null) {
                rotated = rotated.copyWithUser(userToKeep);
            }
            adoptSession(rotated);
            SimpleFuture<AuthSession> future = this.refreshInFlight;
            if (future != null) {
                future.complete(rotated);
            }
            return rotated;
        } catch (IOException | CreangerApiException e) {
            SimpleFuture<AuthSession> future = this.refreshInFlight;
            if (future != null) {
                future.completeExceptionally(e);
            }
            synchronized (lock) {
                if (this.session != null && refreshToken.equals(this.session.refreshToken)) {
                    // Definitive token failure: clear the persisted session.
                    clearLocalSession();
                } else {
                    state = AuthState.UNAUTHENTICATED;
                }
            }
            throw e;
        } finally {
            synchronized (lock) {
                refreshInFlight = null;
            }
        }
    }

    private static AuthSession awaitFuture(SimpleFuture<AuthSession> future) throws IOException, CreangerApiException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (IOException | CreangerApiException e) {
            throw e;
        } catch (Throwable t) {
            if (t instanceof CreangerApiException) {
                throw (CreangerApiException) t;
            }
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException(t);
        }
    }

    @Nullable
    public CreangerUser currentUser() {
        AuthSession current = session;
        return current != null ? current.user : null;
    }

    public String currentRefreshToken() {
        AuthSession current = session;
        return current != null ? current.refreshToken : null;
    }

    // ---- me / logout ----------------------------------------------------------------

    public CreangerUser fetchMe() throws IOException, CreangerApiException {
        String access = requireAccessToken();
        CreangerUser user = api.getUser(access);
        AuthSession current = session;
        if (current != null && (current.user == null || !equalsId(current.user, user)) && current.refreshToken != null) {
            adoptSession(new AuthSession(access, current.refreshToken, user, current.refreshedAtEpochMs));
        } else if (current != null) {
            // Keep access token in memory updated.
            adoptSession(current.copyWithAccessToken(access));
        }
        return user;
    }

    private static boolean equalsId(CreangerUser a, CreangerUser b) {
        return a != null && b != null && a.id != null && a.id.equals(b.id);
    }

    /** Logs out against Supabase Auth (best-effort) and clears all local tokens. */
    public void logout() throws IOException, CreangerApiException {
        AuthSession current = session;
        if (current != null && current.accessToken != null && !current.accessToken.isEmpty()) {
            try {
                api.signOut(current.accessToken);
            } catch (CreangerApiException e) {
                // Server-side failures are still a local logout; the revoked or
                // expired refresh token gets resolved by clearing the store.
            }
        }
        clearLocalSession();
    }

    public void clearLocalSession() {
        synchronized (lock) {
            tokenStore.clear();
            session = null;
            refreshInFlight = null;
            state = AuthState.UNAUTHENTICATED;
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    private static CreangerUser mergeProfile(CreangerUser base, @Nullable String username,
                                             @Nullable String firstName, @Nullable String lastName) {
        String display = base.displayName;
        if (display == null || display.isEmpty()) {
            String full = ((firstName != null ? firstName : "") +
                    (lastName != null ? " " + lastName : "")).trim();
            display = full.isEmpty() ? null : full;
        }
        return new CreangerUser(base.id,
                username != null ? username : base.username,
                base.email, base.emailVerified,
                display, base.avatarUrl, base.createdAt);
    }

    // ---- state manipulation -------------------------------------------------------------

    private void adoptSession(AuthSession next) {
        synchronized (lock) {
            session = next;
            if (next != null && next.refreshToken != null && !next.refreshToken.isEmpty()) {
                tokenStore.store(next);
                state = AuthState.AUTHENTICATED;
            } else {
                state = AuthState.UNAUTHENTICATED;
            }
        }
    }

    private void setAuthenticating() {
        state = AuthState.AUTHENTICATING;
    }

    private void setUnauthenticatedQuietly() {
        // Do not wipe the persisted token just because one network attempt
        // failed; only a definitive invalid-refresh state triggers that in the
        // engine's refresh path. Here we merely reflect the transient state.
        state = AuthState.UNAUTHENTICATED;
    }
}
