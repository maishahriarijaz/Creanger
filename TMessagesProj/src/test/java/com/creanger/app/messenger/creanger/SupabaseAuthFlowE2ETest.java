package com.creanger.app.messenger.creanger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
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
import com.creanger.app.messenger.creanger.model.AuthModels.SignupResult;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * REAL end-to-end verification of the Supabase Auth flow (the ONLY auth
 * backend) against live GoTrue + PostgREST + Inbucket:
 *
 *   - {@code CREANGER_E2E_AUTH_BASE}  Supabase API gateway (default
 *     {@code http://localhost:54321}) routing {@code /auth/v1/*} → GoTrue and
 *     {@code /rest/v1/*} → PostgREST.
 *   - {@code CREANGER_E2E_INBUCKET_BASE} Inbucket mail catcher (default
 *     {@code http://localhost:54324}); real confirmation emails are read from
 *     its REST API — nothing about the email-OTP step is mocked.
 *
 * Everything below runs through the production {@link SupabaseAuthClient},
 * {@link CreangerAuthEngine}, {@link CurrentUserRepository} and token-store
 * seam. Skips (Assume) when the local stack is not running.
 */
public class SupabaseAuthFlowE2ETest {

    private static final String AUTH_BASE =
            System.getenv("CREANGER_E2E_AUTH_BASE") != null
                    ? System.getenv("CREANGER_E2E_AUTH_BASE") : "http://localhost:54321";
    private static final String INBUCKET_BASE =
            System.getenv("CREANGER_E2E_INBUCKET_BASE") != null
                    ? System.getenv("CREANGER_E2E_INBUCKET_BASE") : "http://localhost:54324";
    private static final String PASSWORD = "sup3rSecret!pw";

    /**
     * JVM mirror of {@code HttpsUrlConnectionTransport}: identical base-url,
     * query-string and bearer-header behavior with an arbitrary base URL.
     */
    private static final class LiveTransport implements CreangerHttpTransport {
        private final String base;

        LiveTransport(String base) {
            this.base = base;
        }

        @Override
        public TransportResponse execute(ApiRequest request) throws IOException {
            // Minimal raw-socket HTTP/1.1 client: stock HttpURLConnection
            // rejects PATCH outright on the JVM (Android's OkHttp allows it),
            // so the mirror speaks the protocol directly.
            String pathAndQuery = request.path;
            if (request.query != null && !request.query.isEmpty()) {
                StringBuilder sb = new StringBuilder(pathAndQuery).append('?');
                boolean first = true;
                for (Map.Entry<String, String> e : request.query.entrySet()) {
                    if (!first) {
                        sb.append('&');
                    }
                    first = false;
                    sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                            .append('=')
                            .append(URLEncoder.encode(e.getValue(), "UTF-8"));
                }
                pathAndQuery = sb.toString();
            }
            URL url = new URL(base + pathAndQuery);
            int port = url.getPort() == -1 ? 80 : url.getPort();

            java.net.Socket socket = new java.net.Socket(url.getHost(), port);
            try {
                socket.setSoTimeout(20_000);
                StringBuilder head = new StringBuilder();
                head.append(request.method).append(' ').append(pathAndQuery).append(" HTTP/1.1\r\n");
                head.append("Host: ").append(url.getHost()).append(':').append(port).append("\r\n");
                head.append("Accept: application/json\r\n");
                head.append("Connection: close\r\n");
                if (request.accessToken != null) {
                    head.append("Authorization: Bearer ").append(request.accessToken).append("\r\n");
                }
                byte[] payload =
                        request.jsonBody != null ? request.jsonBody.getBytes(StandardCharsets.UTF_8) : null;
                if (("POST".equals(request.method) || "PATCH".equals(request.method))
                        && payload != null) {
                    head.append("Content-Type: application/json\r\n");
                    head.append("Content-Length: ").append(payload.length).append("\r\n");
                }
                head.append("\r\n");

                java.io.OutputStream out = socket.getOutputStream();
                out.write(head.toString().getBytes(StandardCharsets.UTF_8));
                if (payload != null) {
                    out.write(payload);
                }
                out.flush();

                ByteArrayOutputStream all = new ByteArrayOutputStream();
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    all.write(buf, 0, n);
                }
                return parseResponse(all.toByteArray());
            } finally {
                socket.close();
            }
        }

        private static TransportResponse parseResponse(byte[] raw) throws IOException {
            int headerEnd = -1;
            for (int i = 0; i < raw.length - 3; i++) {
                if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                    headerEnd = i;
                    break;
                }
            }
            if (headerEnd < 0) {
                throw new IOException("malformed HTTP response (no header terminator)");
            }
            String headers = new String(raw, 0, headerEnd, StandardCharsets.UTF_8);
            int status = Integer.parseInt(headers.split(" ")[1]);
            boolean chunked = headers.toLowerCase().contains("transfer-encoding: chunked");
            int contentLength = -1;
            for (String line : headers.split("\r\n")) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            byte[] bodyBytes;
            if (chunked) {
                bodyBytes = dechunk(raw, headerEnd + 4);
            } else if (contentLength >= 0) {
                bodyBytes = java.util.Arrays.copyOfRange(
                        raw, headerEnd + 4, Math.min(raw.length, headerEnd + 4 + contentLength));
            } else {
                bodyBytes = java.util.Arrays.copyOfRange(raw, headerEnd + 4, raw.length);
            }
            return new TransportResponse(status, new String(bodyBytes, StandardCharsets.UTF_8), null);
        }

        private static byte[] dechunk(byte[] raw, int offset) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int pos = offset;
            while (true) {
                int lineEnd = pos;
                while (!(raw[lineEnd] == '\r' && raw[lineEnd + 1] == '\n')) {
                    lineEnd++;
                    if (lineEnd >= raw.length - 1) {
                        throw new IOException("bad chunked encoding");
                    }
                }
                int size = Integer.parseInt(new String(raw, pos, lineEnd - pos, StandardCharsets.UTF_8)
                        .split(";")[0].trim(), 16);
                if (size == 0) {
                    break;
                }
                out.write(raw, lineEnd + 2, size);
                pos = lineEnd + 2 + size + 2; // skip chunk data + CRLF
            }
            return out.toByteArray();
        }
    }

    private static final class MemStore implements CreangerTokenStore {
        volatile AuthSession stored;

        @Override
        public AuthSession load() {
            return stored;
        }

        @Override
        public void store(AuthSession session) {
            stored = session;
        }

        @Override
        public void clear() {
            stored = null;
        }
    }

    // ---- harness helpers -------------------------------------------------------

    private static void assumeStackUp() {
        Assume.assumeTrue("Supabase gateway not running at " + AUTH_BASE, probe(AUTH_BASE + "/auth/v1/health"));
        Assume.assumeTrue("Inbucket not running at " + INBUCKET_BASE, probe(INBUCKET_BASE + "/api/v1/mailbox/probe"));
    }

    private static boolean probe(String urlString) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
            try {
                c.setConnectTimeout(3_000);
                c.setReadTimeout(5_000);
                return c.getResponseCode() < 500;
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static String httpGetJson(String urlString) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            c.setConnectTimeout(5_000);
            c.setReadTimeout(20_000);
            int status = c.getResponseCode();
            InputStream stream = status < 400 ? c.getInputStream() : c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (stream != null) {
                try (InputStream in = new BufferedInputStream(stream)) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
            }
            if (status >= 400) {
                throw new IOException("GET " + urlString + " -> " + status + ": " + sb);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** Reads the 6-digit signup OTP from the REAL confirmation email (Inbucket). */
    private static String fetchSignupOtp(String email) throws Exception {
        String mailbox = email.substring(0, email.indexOf('@'));
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            JSONArray box = new JSONArray(httpGetJson(INBUCKET_BASE + "/api/v1/mailbox/" + mailbox));
            if (box.length() > 0) {
                String id = box.getJSONObject(0).getString("id");
                JSONObject msg = new JSONObject(
                        httpGetJson(INBUCKET_BASE + "/api/v1/mailbox/" + mailbox + "/" + id));
                String text = msg.getJSONObject("body").getString("text");
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\\b([0-9]{6})\\b").matcher(text);
                if (m.find()) {
                    return m.group(1);
                }
            }
            Thread.sleep(300);
        }
        throw new AssertionError("no confirmation email with OTP arrived for " + email);
    }

    private static String uniqueEmail(String tag) {
        return tag + "-" + UUID.randomUUID().toString().substring(0, 13) + "@example.test";
    }

    /**
     * Full REAL registration: signup (metadata carries the username for the DB
     * bridge trigger) → confirmation email → OTP → verified session.
     */
    private static AuthSession registerVerified(CreangerAuthEngine engine, String email, String username)
            throws Exception {
        SignupResult pending = engine.registerWithPassword(email, username, PASSWORD);
        assertNull("project requires email confirmation", pending.session);
        assertEquals(AuthState.UNAUTHENTICATED, engine.getState());
        String otp = fetchSignupOtp(email);
        return engine.verifyRegister(email, otp);
    }

    private static CreangerAuthEngine engine(MemStore store) {
        return new CreangerAuthEngine(new SupabaseAuthClient(new LiveTransport(AUTH_BASE)), store);
    }

    // ---- 1. Login with email + password ------------------------------------------

    @Test
    public void e2eLoginWithEmailAndPassword() throws Exception {
        assumeStackUp();
        MemStore store = new MemStore();
        CreangerAuthEngine reg = engine(store);
        String email = uniqueEmail("loginmail");
        String username = ("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        registerVerified(reg, email, username);

        // Fresh engine/process: pure password grant against live GoTrue.
        MemStore loginStore = new MemStore();
        CreangerAuthEngine login = engine(loginStore);
        AuthSession session = login.loginWithPassword(email, PASSWORD);

        assertEquals(AuthState.AUTHENTICATED, login.getState());
        assertNotNull(session.accessToken);
        assertTrue(session.accessToken.length() > 50);
        assertNotNull(session.user);
        assertEquals(email, session.user.email);
        assertEquals(username, session.user.username);
        assertNotNull("session persisted", loginStore.stored);
        assertEquals(username, loginStore.stored.user.username);
    }

    // ---- 2. Login with username + password ----------------------------------------

    @Test
    public void e2eLoginWithUsernameAndPassword() throws Exception {
        assumeStackUp();
        MemStore regStore = new MemStore();
        String email = uniqueEmail("loginname");
        String username = ("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        registerVerified(engine(regStore), email, username);

        // Username resolution happens SERVER-SIDE via resolve_login_email; the
        // client never learns the mapping beyond what the RPC returns.
        CreangerAuthEngine login = engine(new MemStore());
        AuthSession session = login.loginWithPassword(username, PASSWORD);

        assertEquals(AuthState.AUTHENTICATED, login.getState());
        assertEquals("resolved to the right account", email, session.user.email);

        // Unknown usernames must NOT be distinguishable from bad passwords.
        CreangerAuthEngine unknown = engine(new MemStore());
        try {
            unknown.loginWithPassword("nosuchuser" + UUID.randomUUID().toString().replace("-", ""), PASSWORD);
            fail("unknown username must not authenticate");
        } catch (CreangerApiException e) {
            assertTrue(e.is(ApiError.INVALID_CREDENTIALS));
        }
    }

    // ---- 3. Google id_token grant wiring (real GoTrue boundary) --------------------
    //
    // A genuine Google ID token can only originate from Play Services on a
    // device, which a JVM run cannot produce. What CAN be verified here is that
    // the production idTokenGrant wiring reaches the REAL GoTrue provider
    // endpoint and surfaces its typed failure instead of a mock answer.

    @Test
    public void e2eGoogleIdTokenGrantReachesRealGoTrue() throws Exception {
        assumeStackUp();
        CreangerAuthEngine login = engine(new MemStore());
        try {
            login.googleAuth("not-a-real-google-id-token");
            fail("garbage Google ID token must not authenticate");
        } catch (CreangerApiException e) {
            assertEquals("GoTrue rejected the id_token grant", 400, e.statusCode);
        }
    }

    // ---- 6. Username availability: live + server-backed -----------------------------

    @Test
    public void e2eUsernameAvailabilityIsServerBacked() throws Exception {
        assumeStackUp();
        MemStore store = new MemStore();
        String username = ("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        registerVerified(engine(store), uniqueEmail("avail"), username);
        CreangerAuthEngine probe = engine(new MemStore());

        assertTrue("free username available", probe.checkUsername(
                "free" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)));
        assertFalse("registered username taken", probe.checkUsername(username));
        assertFalse("server enforces the format rules too", probe.checkUsername("ab"));
        // Case-insensitivity is enforced by CITEXT storage.
        assertFalse("availability is case-insensitive",
                probe.checkUsername(username.toUpperCase().replace('_', 'a')));
    }

    // ---- 7. Username validation follows the intended rules ---------------------------
    //
    // Client-side rules are pinned by CreangerUsernamePolicyTest; this checks
    // the SERVER agrees. Since GoTrue treats signup metadata as opaque, a
    // policy-violating username must NOT abort signup (migration 035): the
    // account is created with an UNCLAIMED username and the app's claim/setup
    // flow completes the profile afterwards — exactly the Google new-user path.

    @Test
    public void e2eInvalidUsernameMetadataLeavesUsernameUnclaimedNotAborted() throws Exception {
        assumeStackUp();
        MemStore store = new MemStore();
        CreangerAuthEngine eng = engine(store);
        String email = uniqueEmail("badname");

        SignupResult pending = eng.registerWithPassword(email, "ab", PASSWORD);
        assertNull("confirmation still required", pending.session);
        AuthSession session = eng.verifyRegister(email, fetchSignupOtp(email));

        assertEquals("signup completed despite invalid metadata",
                AuthState.AUTHENTICATED, eng.getState());
        assertNotNull(session.user.id);
        // GoTrue echoes raw user_metadata, so the app-level user still shows
        // the requested string — the authoritative claim state is profiles,
        // where the trigger dropped the malformed value:
        SupabaseAuthClient api = new SupabaseAuthClient(new LiveTransport(AUTH_BASE));
        assertNull("malformed username dropped server-side (unclaimed profile)",
                api.getProfile(session.accessToken, session.user.id));

        // And the user can claim a valid username right away (RLS PATCH),
        // completing the profile like the account-setup screen does.
        String claimed = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        api.updateOwnProfile(session.accessToken, session.user.id, claimed, "User");
        assertEquals(claimed, api.getProfile(session.accessToken, session.user.id).username);
        assertEquals(AuthState.AUTHENTICATED, eng.getState());
    }

    // ---- 8. Register → email verification → session ----------------------------------

    @Test
    public void e2eRegisterVerifyYieldsRealSession() throws Exception {
        assumeStackUp();
        MemStore store = new MemStore();
        CreangerAuthEngine eng = engine(store);
        String email = uniqueEmail("reg");
        String username = ("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));

        AuthSession session = registerVerified(eng, email, username);

        assertEquals(AuthState.AUTHENTICATED, eng.getState());
        assertNotNull(session.accessToken);
        assertNotNull(session.refreshToken);
        assertEquals(email, session.user.email);
        assertEquals(username, session.user.username);
        // Persisted for restore; the bridge trigger materialized the profile.
        assertEquals(username, store.stored.user.username);
    }

    // ---- 9. Session restore after app restart -----------------------------------------

    @Test
    public void e2eSessionRestoresAfterRestartWithRealRotation() throws Exception {
        assumeStackUp();
        MemStore disk = new MemStore(); // plays the role of KeystoreTokenStore
        CreangerAuthEngine first = engine(disk);
        String email = uniqueEmail("restore");
        registerVerified(first, email, "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));

        // Simulated cold start: brand-new engine over the persisted store.
        CreangerAuthEngine restarted = engine(disk);
        assertEquals(AuthState.AUTHENTICATED, restarted.getState());
        assertNotNull("profile restored offline", restarted.currentUser());

        // Access tokens are memory-only → restore must rotate for real.
        String access = restarted.requireAccessToken();
        assertNotNull(access);
        // And the rotated access token is accepted by GoTrue for /user.
        CreangerUser me = restarted.fetchMe();
        assertEquals(restarted.currentUser().id, me.id);
        assertEquals(email, me.email);
    }

    // ---- 10. Logout → back to the login state ------------------------------------------

    @Test
    public void e2eLogoutRevokesServerSideAndClearsLocal() throws Exception {
        assumeStackUp();
        MemStore disk = new MemStore();
        CreangerAuthEngine eng = engine(disk);
        registerVerified(eng, uniqueEmail("logout"), "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        String oldRefresh = eng.currentRefreshToken();

        eng.logout();

        assertEquals("UI routes back to login on UNAUTHENTICATED", AuthState.UNAUTHENTICATED, eng.getState());
        assertNull(eng.currentUser());
        assertNull("local tokens cleared", disk.stored);
        assertNull(eng.currentRefreshToken());

        // Server-side proof: the revoked refresh token can never mint again.
        try {
            new SupabaseAuthClient(new LiveTransport(AUTH_BASE)).refreshTokenGrant(oldRefresh);
            fail("revoked refresh token must not rotate");
        } catch (CreangerApiException e) {
            assertTrue(e.is(ApiError.SESSION_EXPIRED_OR_REVOKED));
        }
    }

    // ---- 11. Account switching does not leak cached data --------------------------------

    @Test
    public void e2eAccountSwitchDoesNotLeakProfileOrTokens() throws Exception {
        assumeStackUp();
        MemStore disk = new MemStore(); // one physical device store
        CreangerAuthEngine eng = engine(disk);

        String emailA = uniqueEmail("switcha");
        CreangerUser userA = registerVerified(eng, emailA,
                "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)).user;
        String refreshA = eng.currentRefreshToken();
        CurrentUserRepository repoA = new CurrentUserRepository(eng, disk);
        assertEquals(userA.id, repoA.getCachedUser().id);

        // Account B signs up + verifies THROUGH THE SAME engine + store.
        String emailB = uniqueEmail("switchb");
        AuthSession sessionB = registerVerified(eng, emailB,
                "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));

        assertEquals("engine serves B only", sessionB.user.id, eng.currentUser().id);
        assertEquals("store holds B only", sessionB.user.id, disk.stored.user.id);
        assertNotEquals("refresh token replaced with B's session", refreshA, disk.stored.refreshToken);

        // Repository view rebuilt over the same store shows B's profile only.
        CurrentUserRepository repoB = new CurrentUserRepository(eng, disk);
        assertEquals(sessionB.user.id, repoB.getCachedUser().id);
        assertNotEquals(userA.id, repoB.getCachedUser().id);

        // A's tokens are gone from memory; only B can act.
        assertEquals(emailB, eng.fetchMe().email);
    }

    // ---- 12. Invalid credentials show the correct errors ---------------------------------

    @Test
    public void e2eInvalidCredentialsMapToTypedErrors() throws Exception {
        assumeStackUp();
        MemStore store = new MemStore();
        String email = uniqueEmail("wrongpw");
        registerVerified(engine(store), email, "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        CreangerAuthEngine login = engine(new MemStore());

        // Wrong password on an existing account.
        try {
            login.loginWithPassword(email, "totally-wrong-pw");
            fail("wrong password must not authenticate");
        } catch (CreangerApiException e) {
            assertTrue("got " + e.error.code, e.is(ApiError.INVALID_CREDENTIALS));
            assertEquals(AuthState.UNAUTHENTICATED, login.getState());
            assertNull(login.currentUser());
        }

        // Malformed-but-valid-shaped email still fails generically (no leak).
        CreangerAuthEngine ghost = engine(new MemStore());
        try {
            ghost.loginWithPassword("ghost-" + UUID.randomUUID() + "@example.test", PASSWORD);
            fail("unknown account must not authenticate");
        } catch (CreangerApiException e) {
            assertTrue(e.is(ApiError.INVALID_CREDENTIALS));
        }
    }
}
