package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.auth.AuthState;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.data.CurrentUserRepository;
import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Current-user / profile foundation tests on the Supabase Auth engine:
 * {@code GET /auth/v1/user} fetch, the persisted profile cache (restore on
 * restart), logout clearing, account switching (no previous-account leakage),
 * refresh keeping the profile, and malformed/network failure behavior.
 *
 * These run against scripted transports + an in-memory secure store; the
 * Keystore-backed disk store needs an Android runtime.
 */
public class CurrentUserRepositoryTest {

    private static final class ScriptedTransport implements CreangerHttpTransport {
        final List<TransportResponse> responses = new ArrayList<>();
        final List<ApiRequest> requests = new ArrayList<>();
        volatile IOException failOnUser = null;

        @Override
        public TransportResponse execute(ApiRequest request) throws java.io.IOException {
            requests.add(request);
            if ("/auth/v1/user".equals(request.path) && failOnUser != null) {
                throw failOnUser;
            }
            TransportResponse response = responses.remove(0);
            if (response == null) {
                throw new java.io.IOException("no canned response for " + request.method + " " + request.path);
            }
            return response;
        }

        TransportResponse json(int status, String body) {
            return new TransportResponse(status, body, null);
        }
    }

    private static final class InMemoryStore implements CreangerTokenStore {
        volatile AuthSession stored;

        @Override
        public AuthSession load() {
            return stored;
        }

        @Override
        public void store(AuthSession session) {
            this.stored = session;
        }

        @Override
        public void clear() {
            this.stored = null;
        }
    }

    private static AuthSession session(String access, String refresh, long refreshedAtMs, CreangerUser user) {
        return new AuthSession(access, refresh, user, refreshedAtMs);
    }

    private static AuthSession idleSession(String refresh, CreangerUser user) {
        // Access token deliberately unset (memory-only after app restart).
        return session("", refresh, 0L, user);
    }

    private static CreangerUser user(String id, String username, String email) {
        return new CreangerUser(id, username, email, true, null, null, null);
    }

    /** GoTrue {@code GET /auth/v1/user} body for the given identity. */
    private String userJson(String id, String username, String email) {
        return "{\"id\":\"" + id + "\",\"email\":\"" + email + "\",\"email_confirmed_at\":"
                + "\"2026-08-15T05:03:55.210Z\",\"created_at\":\"2026-08-15T05:03:55.210Z\","
                + "\"user_metadata\":{\"username\":\"" + username + "\",\"display_name\":\"" + username + "\"}}";
    }

    private static CreangerAuthEngine engineWith(ScriptedTransport t, InMemoryStore store) {
        return new CreangerAuthEngine(new SupabaseAuthClient(t), store);
    }

    // ---- /user fetch stores the current user ----

    @Test
    public void fetchMeStoresCurrentUserInSessionAndPersistsIt() throws Exception {
        // App alive with a fresh in-memory access token; only /user hits the wire.
        InMemoryStore store = new InMemoryStore();
        store.store(session("acc-live", "ref", System.currentTimeMillis(), null));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, userJson("uuid-1", "alice", "a@x.com")));
        CurrentUserRepository repo = new CurrentUserRepository(engineWith(t, store), store);

        CreangerUser me = repo.refreshCurrentUser();

        assertEquals("uuid-1", me.id);
        assertEquals("a@x.com", me.email);
        assertEquals(AuthState.AUTHENTICATED, repo.getState());
        assertTrue(repo.isAuthenticated());
        assertSame("live session user", me, repo.getCurrentUser());
        // Persisted for offline restoration.
        assertNotNull("persisted user", store.stored.user);
        assertEquals("uuid-1", store.stored.user.id);
        // Exactly one /user request, no refresh call.
        assertEquals(1, t.requests.size());
        assertEquals("/auth/v1/user", t.requests.get(0).path);
    }

    // ---- caching: persisted profile restored on app restart ----

    @Test
    public void persistedProfileIsRestoredOnRestartWithoutNetwork() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(idleSession("refresh", user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport(); // no responses = no network needed
        CurrentUserRepository repo = new CurrentUserRepository(engineWith(t, store), store);

        assertEquals(AuthState.AUTHENTICATED, repo.getState());
        CreangerUser restored = repo.getCachedUser();
        assertNotNull("offline cached user", restored);
        assertEquals("uuid-1", restored.id);
        assertEquals("alice", restored.username);
        assertEquals(0, t.requests.size());
    }

    @Test
    public void getCachedUserPrefersLiveSessionUser() throws Exception {
        CreangerUser live = user("uuid-1", "alice", "a@x.com");
        InMemoryStore store = new InMemoryStore();
        store.store(session("acc", "ref", System.currentTimeMillis(), live));
        ScriptedTransport t = new ScriptedTransport();
        CurrentUserRepository repo = new CurrentUserRepository(engineWith(t, store), store);

        CreangerUser cached = repo.getCachedUser();

        assertSame(live, cached);
    }

    // ---- logout clears the user + session ----

    @Test
    public void logoutClearsUserAndPersistedSession() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(session("acc", "ref", System.currentTimeMillis(), user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(204, ""));
        CurrentUserRepository repo = new CurrentUserRepository(engineWith(t, store), store);

        assertNotNull(repo.getCurrentUser());
        repo.clear();

        assertEquals(AuthState.UNAUTHENTICATED, repo.getState());
        assertFalse(repo.isAuthenticated());
        assertNull(repo.getCurrentUser());
        assertNull(repo.getCachedUser());
        assertNull(store.stored);
    }

    // ---- account switching must not leak the previous account ----

    @Test
    public void loginAsNewAccountReplacesPreviousProfile() throws Exception {
        // Previous account fully cached (restart-persisted profile).
        InMemoryStore store = new InMemoryStore();
        store.store(idleSession("ref-A", user("uuid-A", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        CreangerAuthEngine engine = engineWith(t, store);

        // New account logs in; the password-grant response carries user B.
        t.responses.add(t.json(200,
                "{\"access_token\":\"acc-B\",\"refresh_token\":\"ref-B\","
                        + "\"user\":{\"id\":\"uuid-B\",\"email\":\"b@x.com\",\"email_confirmed_at\":\"2026-01-01T00:00:00Z\","
                        + "\"user_metadata\":{\"username\":\"bob\"}}}"));
        AuthSession session = engine.loginWithPassword("b@x.com", "pw-B");

        assertEquals("uuid-B", session.user.id);
        // No trace of account A anywhere.
        assertEquals("uuid-B", engine.currentUser().id);
        assertNotNull(store.stored);
        assertEquals("uuid-B", store.stored.user.id);
        assertFalse("refresh token rotated to new account", "ref-A".equals(store.stored.refreshToken));
    }

    @Test
    public void switchingToUserLessSessionDropsCachedProfile() throws Exception {
        // Account A cached; a brand-new session WITHOUT a profile must not
        // keep account A's user leaking (matches KeystoreTokenStore's
        // remove-on-null guard in production).
        InMemoryStore store = new InMemoryStore();
        store.store(idleSession("ref-A", user("uuid-A", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        CreangerAuthEngine engine = engineWith(t, store);

        // GoTrue responds with tokens only (no user object).
        t.responses.add(t.json(200,
                "{\"access_token\":\"acc-B\",\"refresh_token\":\"ref-B\"}"));
        engine.loginWithPassword("b@x.com", "pw-B");

        assertNull("no stale previous-account profile", engine.currentUser());
        assertNull("persisted cache cleared", store.stored.user);
    }

    // ---- refresh keeps the current user (rotation never wipes the profile) ----

    @Test
    public void refreshPreservesCurrentUserAcrossRotation() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(idleSession("ref", user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        // The refresh endpoint only echoes tokens; the engine must keep the user.
        t.responses.add(t.json(200, "{\"access_token\":\"acc2\",\"refresh_token\":\"ref2\"}"));
        CreangerAuthEngine engine = engineWith(t, store);

        AuthSession rotated = engine.refreshSession();

        assertEquals("acc2", rotated.accessToken);
        assertEquals("uuid-1", rotated.user.id);
        assertEquals("uuid-1", engine.currentUser().id);
        assertNotNull("persisted through rotation", store.stored.user);
        assertEquals("uuid-1", store.stored.user.id);
    }

    @Test
    public void refreshAfterRestartRoundTripsProfile() throws Exception {
        // Restart state: persisted session has a user, no access token.
        InMemoryStore store = new InMemoryStore();
        store.store(idleSession("ref", user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "{\"access_token\":\"acc2\",\"refresh_token\":\"ref2\"}"));
        CreangerAuthEngine engine = engineWith(t, store);

        engine.requireAccessToken();

        assertEquals("uuid-1", engine.currentUser().id);
        assertNotNull(store.stored.user);
        assertEquals("uuid-1", store.stored.user.id);
        assertEquals(1, t.requests.size());
        assertEquals("/auth/v1/token", t.requests.get(0).path);
        assertEquals("refresh_token", t.requests.get(0).query.get("grant_type"));
    }

    // ---- malformed /user responses ----

    @Test
    public void malformedMeResponseThrowsTypedError() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(session("acc", "ref", System.currentTimeMillis(), user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "not-json-at-all"));
        CurrentUserRepository repo = new CurrentUserRepository(engineWith(t, store), store);

        try {
            repo.refreshCurrentUser();
            fail("expected CreangerApiException for malformed /user body");
        } catch (CreangerApiException e) {
            assertTrue(e.is(ApiError.INTERNAL_ERROR));
        }
        // The failed fetch must not disturb the live profile.
        assertEquals(AuthState.AUTHENTICATED, repo.getState());
        assertEquals("uuid-1", repo.getCurrentUser().id);
    }

    @Test
    public void meServerErrorSurfacesAsTypedException() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(session("acc", "ref", System.currentTimeMillis(), user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(403, "{\"code\":403,\"error_code\":\"user_not_found\",\"msg\":\"User from sub claim in JWT does not exist\"}"));
        CurrentUserRepository repo = new CurrentUserRepository(engineWith(t, store), store);

        try {
            repo.refreshCurrentUser();
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertTrue(e.is(ApiError.ACCOUNT_NOT_FOUND));
        }
        assertEquals("uuid-1", repo.getCurrentUser().id);
    }

    // ---- network failures ----

    @Test
    public void networkFailureOnMeThrowsIoErrorAndKeepsCache() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(session("acc", "ref", System.currentTimeMillis(), user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.failOnUser = new IOException("no route to host");
        CurrentUserRepository repo = new CurrentUserRepository(engineWith(t, store), store);

        try {
            repo.refreshCurrentUser();
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("no route to host", e.getMessage());
        }
        // Offline fallback still serves the cached profile.
        assertEquals(AuthState.AUTHENTICATED, repo.getState());
        assertEquals("uuid-1", repo.getCurrentUser().id);
        assertEquals("uuid-1", repo.getCachedUser().id);
    }

    @Test
    public void definitiveRefreshFailureClearsSessionAndProfile() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(idleSession("ref", user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        // GoTrue answers reused/revoked refresh tokens with invalid_grant.
        t.responses.add(t.json(400, "{\"code\":400,\"error_code\":\"invalid_grant\",\"msg\":\"Invalid Refresh Token: Session Not Found\"}"));
        CreangerAuthEngine engine = engineWith(t, store);

        try {
            engine.requireAccessToken();
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertTrue(e.is(ApiError.SESSION_EXPIRED_OR_REVOKED));
        }
        // Definitive token failure: session + cached profile are wiped so a
        // later user never inherits this account's profile.
        assertEquals(AuthState.UNAUTHENTICATED, engine.getState());
        assertNull(engine.currentUser());
        assertNull(store.stored);
    }
}
