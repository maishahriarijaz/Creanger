package com.creanger.app.messenger.creanger;

import org.json.JSONObject;
import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.auth.AuthState;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.GoogleAuthResult;
import com.creanger.app.messenger.creanger.model.AuthModels.SignupResult;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class CreangerAuthEngineTest {

    private static final String SESSION_JSON =
            "{\"access_token\":\"acc\",\"refresh_token\":\"ref\",\"user\":{\"id\":\"u1\",\"email\":\"u@x.com\",\"email_confirmed_at\":\"2026-01-01T00:00:00Z\",\"user_metadata\":{\"username\":\"user9\"}}}";

    private static final class ScriptedTransport implements CreangerHttpTransport {
        volatile List<TransportResponse> responses = new ArrayList<>();
        final List<ApiRequest> requests = new ArrayList<>();

        @Override
        public TransportResponse execute(ApiRequest request) throws java.io.IOException {
            requests.add(request);
            TransportResponse response = responses.remove(0);
            if (response == null) {
                throw new java.io.IOException("no canned response");
            }
            return response;
        }

        TransportResponse json(int status, String body) {
            return new TransportResponse(status, body, null);
        }
    }

    private static final class InMemoryTokenStore implements CreangerTokenStore {
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

    private static AuthSession sessionWith(String access, String refresh) {
        return new AuthSession(access, refresh, null, System.currentTimeMillis());
    }

    private static boolean isRefreshGrant(ApiRequest request) {
        return "/auth/v1/token".equals(request.path)
                && request.query != null
                && "refresh_token".equals(request.query.get("grant_type"));
    }

    @Test
    public void restoresRefreshTokenFromStoreOnStart() {
        InMemoryTokenStore store = new InMemoryTokenStore();
        store.store(sessionWith("", "refresh-persisted"));
        ScriptedTransport t = new ScriptedTransport();
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), store);

        assertEquals(AuthState.AUTHENTICATED, engine.getState());
        assertEquals("refresh-persisted", engine.currentRefreshToken());
    }

    @Test
    public void requireAccessTokenRefreshesWhenMissing_inMemory() throws Exception {
        InMemoryTokenStore store = new InMemoryTokenStore();
        store.store(sessionWith("", "refresh-persisted"));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\"}"));
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), store);

        String access = engine.requireAccessToken();

        assertEquals("new-access", access);
        assertEquals(AuthState.AUTHENTICATED, engine.getState());
        assertTrue(isRefreshGrant(t.requests.get(0)));
    }

    @Test
    public void usesCachedAccessTokenWithoutRefresh() throws Exception {
        InMemoryTokenStore store = new InMemoryTokenStore();
        store.store(sessionWith("", "refresh"));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "{\"access_token\":\"a1\",\"refresh_token\":\"r1\"}"));
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), store);
        engine.requireAccessToken();

        // Second call must reuse the in-memory access token; transport must be silent.
        String access = engine.requireAccessToken();
        assertEquals("a1", access);
        assertEquals(1, t.requests.size());
    }

    @Test
    public void singleFlightDeduplicatesConcurrentRefreshes() throws Exception {
        InMemoryTokenStore store = new InMemoryTokenStore();
        store.store(sessionWith("", "refresh"));
        ScriptedTransport t = new ScriptedTransport();
        // Only ONE refresh response is canned. If concurrent callers were not
        // deduplicated, a second refresh request would throw (no response).
        t.responses.add(t.json(200, "{\"access_token\":\"a1\",\"refresh_token\":\"r2\"}"));
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), store);

        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(5);
        // Written from 5 threads — must be thread-safe or sizes race.
        final List<String> results = java.util.Collections.synchronizedList(new ArrayList<String>());
        Throwable[] failures = new Throwable[5];

        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            threads[idx] = new Thread(() -> {
                try {
                    start.await();
                    results.add(engine.requireAccessToken());
                } catch (Throwable e) {
                    failures[idx] = e;
                } finally {
                    done.countDown();
                }
            });
            threads[idx].start();
        }
        start.countDown();
        assertTrue("timed out waiting for single-flight", done.await(10, TimeUnit.SECONDS));
        for (Throwable f : failures) {
            assertNull("worker failed: " + f, f);
        }
        assertEquals(5, results.size());
        for (String r : results) {
            assertEquals("a1", r);
        }
        // Exactly one refresh request on the wire.
        int refreshCount = 0;
        for (ApiRequest req : t.requests) {
            if (isRefreshGrant(req)) {
                refreshCount++;
            }
        }
        assertEquals(1, refreshCount);
    }

    @Test
    public void failedRefreshMarksUnauthenticated() throws Exception {
        InMemoryTokenStore store = new InMemoryTokenStore();
        store.store(sessionWith("", "refresh-expired"));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(400, "{\"code\":400,\"error_code\":\"invalid_grant\",\"msg\":\"Invalid Refresh Token: Already Used\"}"));
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), store);

        try {
            engine.requireAccessToken();
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertTrue(e.is("SESSION_EXPIRED_OR_REVOKED"));
        }
        assertEquals(AuthState.UNAUTHENTICATED, engine.getState());
    }

    @Test
    public void loginWithPasswordResolvesUsernameServerSideThenGrants() throws Exception {
        InMemoryTokenStore store = new InMemoryTokenStore();
        ScriptedTransport t = new ScriptedTransport();
        // 1. SECURITY DEFINER RPC resolves the username -> email.
        t.responses.add(t.json(200, "\"u@x.com\""));
        // 2. GoTrue password grant with the resolved email.
        t.responses.add(t.json(200, SESSION_JSON));
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), store);

        AuthSession result = engine.loginWithPassword("user9", "secret123");

        assertEquals("acc", result.accessToken);
        assertEquals(AuthState.AUTHENTICATED, engine.getState());
        assertNotNull(store.stored);
        assertEquals("ref", store.stored.refreshToken);
        assertNotNull(result.user);
        assertEquals("user9", result.user.username);

        ApiRequest rpc = t.requests.get(0);
        assertEquals("/rest/v1/rpc/resolve_login_email", rpc.path);
        assertEquals("user9", rpc.query.get("p_identifier"));

        ApiRequest grant = t.requests.get(1);
        assertEquals("/auth/v1/token", grant.path);
        assertEquals("password", grant.query.get("grant_type"));
        JSONObject body = new JSONObject(grant.jsonBody);
        assertEquals("u@x.com", body.getString("email"));
        assertEquals("secret123", body.getString("password"));
    }

    @Test
    public void loginWithEmailSkipsResolutionRpc() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, SESSION_JSON));
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), new InMemoryTokenStore());

        engine.loginWithPassword("u@x.com", "pw");

        assertEquals(1, t.requests.size());
        assertEquals("password", t.requests.get(0).query.get("grant_type"));
    }

    @Test
    public void signupConfirmationRequiredLeavesStateUnauthenticated() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        // Auto-confirm OFF: bare user object, no tokens.
        t.responses.add(t.json(200, "{\"id\":\"u1\",\"email\":\"u@x.com\"}"));
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), new InMemoryTokenStore());

        SignupResult result = engine.registerWithPassword("u@x.com", "user9", "secret123");

        assertTrue(result.isEmailConfirmationRequired());
        assertEquals(AuthState.UNAUTHENTICATED, engine.getState());

        // Verification completes the flow and adopts the session.
        t.responses.add(t.json(200, SESSION_JSON));
        AuthSession session = engine.verifyRegister("u@x.com", "123456");
        assertEquals("acc", session.accessToken);
        assertEquals(AuthState.AUTHENTICATED, engine.getState());
        assertEquals("signup", t.requests.get(1).jsonBody != null
                ? new JSONObject(t.requests.get(1).jsonBody).getString("type") : "");
    }

    @Test
    public void googleAdoptsSessionAndFlagsMissingProfileUsername() throws Exception {
        InMemoryTokenStore store = new InMemoryTokenStore();
        ScriptedTransport t = new ScriptedTransport();
        // 1. id_token grant — fresh Google account, no username metadata.
        t.responses.add(t.json(200,
                "{\"access_token\":\"acc\",\"refresh_token\":\"ref\",\"user\":{\"id\":\"g1\",\"email\":\"g@gmail.com\",\"user_metadata\":{\"name\":\"G Name\"}}}"));
        // 2. profiles row exists but username unclaimed.
        t.responses.add(t.json(200, "[{\"user_id\":\"g1\",\"username\":null,\"first_name\":\"G Name\",\"last_name\":null}]"));
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), store);

        GoogleAuthResult result = engine.googleAuth("google-id-token");

        assertTrue(result.needsRegistration);
        assertNotNull(result.session);
        assertEquals("ref", store.stored.refreshToken);
        assertEquals(AuthState.AUTHENTICATED, engine.getState());

        ApiRequest grant = t.requests.get(0);
        assertEquals("id_token", grant.query.get("grant_type"));
        JSONObject grantBody = new JSONObject(grant.jsonBody);
        assertEquals("google", grantBody.getString("provider"));
        assertEquals("google-id-token", grantBody.getString("id_token"));
        assertEquals("/rest/v1/profiles", t.requests.get(1).path);
        assertEquals("eq.g1", t.requests.get(1).query.get("user_id"));
    }

    @Test
    public void googleAuthCompleteUpdatesUserAndProfileOnActiveSession() throws Exception {
        InMemoryTokenStore store = new InMemoryTokenStore();
        store.store(sessionWith("acc", "ref"));
        ScriptedTransport t = new ScriptedTransport();
        // Engine needs a current user for complete; simulate via googleAuth first.
        t.responses.add(t.json(200,
                "{\"access_token\":\"acc2\",\"refresh_token\":\"ref2\",\"user\":{\"id\":\"g1\",\"email\":\"g@gmail.com\"}}"));
        t.responses.add(t.json(200, "[]")); // no profile row yet -> needsRegistration
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), store);
        engine.googleAuth("google-id-token");

        // 3. PUT /auth/v1/user sets password + metadata.
        t.responses.add(t.json(200,
                "{\"id\":\"g1\",\"email\":\"g@gmail.com\",\"user_metadata\":{\"username\":\"newbie\",\"display_name\":\"New Bie\"}}"));
        // 4. PATCH profiles row claims the username.
        t.responses.add(t.json(204, ""));

        AuthSession result = engine.googleAuthComplete("newbie", "New Bie", "secret123");

        assertEquals("newbie", result.user.username);
        assertEquals("New Bie", result.user.displayName);
        assertEquals(AuthState.AUTHENTICATED, engine.getState());
        assertEquals("ref2", store.stored.refreshToken);
        assertNotNull(store.stored.user);
        assertEquals("newbie", store.stored.user.username);

        ApiRequest put = t.requests.get(2);
        assertEquals("/auth/v1/user", put.path);
        assertEquals("PUT", put.method);
        JSONObject putBody = new JSONObject(put.jsonBody);
        assertEquals("secret123", putBody.getString("password"));
        assertEquals("newbie", putBody.getJSONObject("data").getString("username"));

        ApiRequest patch = t.requests.get(3);
        assertEquals("PATCH", patch.method);
        assertEquals("/rest/v1/profiles", patch.path);
        JSONObject patchBody = new JSONObject(patch.jsonBody);
        assertEquals("newbie", patchBody.getString("username"));
    }

    @Test
    public void logoutClearsPersistedTokens() throws Exception {
        InMemoryTokenStore store = new InMemoryTokenStore();
        store.store(sessionWith("acc", "refresh"));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(204, ""));
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), store);

        engine.logout();

        assertNull(store.stored);
        assertEquals(AuthState.UNAUTHENTICATED, engine.getState());
        assertNull(engine.currentRefreshToken());
        assertEquals("/auth/v1/logout", t.requests.get(0).path);
    }

    @Test
    public void resetPasswordVerifiesRecoveryThenSetsPassword() throws Exception {
        InMemoryTokenStore store = new InMemoryTokenStore();
        ScriptedTransport t = new ScriptedTransport();
        // 1. verify recovery OTP mints a session.
        t.responses.add(t.json(200, SESSION_JSON));
        // 2. PUT user applies the new password.
        t.responses.add(t.json(200, "{\"id\":\"u1\",\"email\":\"u@x.com\"}"));
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), store);

        AuthSession result = engine.resetPassword("u@x.com", "123456", "newSecret99");

        assertEquals("acc", result.accessToken);
        assertEquals(AuthState.AUTHENTICATED, engine.getState());

        ApiRequest verify = t.requests.get(0);
        assertEquals("/auth/v1/verify", verify.path);
        assertEquals("recovery", new JSONObject(verify.jsonBody).getString("type"));
        ApiRequest put = t.requests.get(1);
        assertEquals("newSecret99", new JSONObject(put.jsonBody).getString("password"));
    }

    @Test
    public void checkUsernamePassesThroughAvailability() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "false"));
        CreangerAuthEngine engine = new CreangerAuthEngine(new SupabaseAuthClient(t), new InMemoryTokenStore());

        boolean available = engine.checkUsername("taken");

        assertFalse(available);
        assertEquals("/rest/v1/rpc/check_username_available", t.requests.get(0).path);
        assertEquals("taken", t.requests.get(0).query.get("p_username"));
    }
}
