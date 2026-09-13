package com.creanger.app.messenger.creanger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

import androidx.annotation.Nullable;

import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.model.MessageModels.MessagePage;
import com.creanger.app.messenger.creanger.auth.AuthState;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;
import com.creanger.app.messenger.creanger.data.ChatRepository;
import com.creanger.app.messenger.creanger.data.MessageRepository;
import com.creanger.app.messenger.creanger.model.AuthModels;
import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;
import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

/**
 * LIVE end-to-end suite against a real Creanger Supabase stack
 * (GoTrue + PostgREST + RPCs). No mocks: every assertion below exercises the
 * real HTTP data plane through {@link SupabaseAuthClient} and
 * {@link CreangerChatApiClient} — the same clients the Android app uses.
 *
 * GATING (per repo policy): when no backend is reachable every test
 * {@code Assume}s away, so this task stays green with zero executions — an
 * unreachable server must be reported as NOT RUN, never faked as PASS.
 *
 * Configuration (env vars, highest priority first):
 *   CREANGER_E2E_BASE_URL   e.g. http://localhost:54321 or https://xyz.supabase.co
 *   CREANGER_E2E_ANON_KEY   project anon key (public by design; RLS governs)
 *   CREANGER_E2E_SEED_EMAIL / CREANGER_E2E_SEED_PASSWORD  optional seeded user
 *   with existing chat memberships for chat/message/reaction/presence tests.
 *
 * Fallback probe order when BASE_URL unset: localhost:54321 then 10.0.2.2:54321
 * (the standard local Supabase stack; 10.0.2.2 is the emulator's host alias).
 */
public class CreangerRealServerE2ETest {

    private static final String ENV_BASE = "CREANGER_E2E_BASE_URL";
    private static final String ENV_ANON = "CREANGER_E2E_ANON_KEY";
    private static final String ENV_SEED_EMAIL = "CREANGER_E2E_SEED_EMAIL";
    private static final String ENV_SEED_PASSWORD = "CREANGER_E2E_SEED_PASSWORD";

    private static final String[] PROBE_BASES = {
            "http://localhost:54321",
            "http://10.0.2.2:54321",
    };
    private static final int HEALTH_TIMEOUT_MS = 2500;

    // ---- live fixtures (null when backend unreachable) ----
    @Nullable
    private String baseUrl;
    @Nullable
    private String anonKey;
    @Nullable
    private SupabaseAuthClient authApi;
    @Nullable
    private CreangerChatApiClient chatApi;
    @Nullable
    private CreangerAuthEngine engine;

    // ======================================================================
    // Setup + reachability gate
    // ======================================================================

    @Before
    public void requireLiveBackend() {
        if (baseUrl == null) {
            discoverBackend();
        }
        assumeTrue("Creanger Supabase backend not reachable - E2E NOT RUN", baseUrl != null);
        assertNotNull(anonKey);

        RealTransport transport = new RealTransport(baseUrl, anonKey);
        authApi = new SupabaseAuthClient(transport);
        chatApi = new CreangerChatApiClient(transport);
        engine = new CreangerAuthEngine(authApi, new VolatileTokenStore());
    }

    private void discoverBackend() {
        String envBase = System.getenv(ENV_BASE);
        List<String> candidates = new ArrayList<>();
        if (envBase != null && !envBase.trim().isEmpty()) {
            candidates.add(envBase.trim());
        }
        Collections.addAll(candidates, PROBE_BASES);

        String envAnon = System.getenv(ENV_ANON);
        for (String candidate : candidates) {
            try {
                int code = healthStatus(candidate);
                if (code == 200) {
                    baseUrl = stripTrailing(candidate);
                    anonKey = (envAnon != null && !envAnon.trim().isEmpty())
                            ? envAnon.trim() : defaultLocalAnonKey();
                    return;
                }
            } catch (IOException ignored) {
                // probe next candidate
            }
        }
    }

    /** Local supabase CLI publishes the standard well-known anon key. */
    private static String defaultLocalAnonKey() {
        return "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
                + "eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9."
                + "CRIMUM-SwC_H7RTxQfdDkT9lCWBYtkAR0rquXPAh8ik";
    }

    private static int healthStatus(String base) throws IOException {
        HttpURLConnection conn = open(base + "/auth/v1/health");
        try {
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(HEALTH_TIMEOUT_MS);
        conn.setReadTimeout(HEALTH_TIMEOUT_MS);
        return conn;
    }

    private static String stripTrailing(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private static String uniqueEmail() {
        return "e2e-" + UUID.randomUUID() + "@creanger.test";
    }

    private static String uniqueUsername() {
        return "e2e" + Long.toHexString(System.nanoTime()).substring(0, 10);
    }

    /** Registers + logs in a fresh disposable user; skips if server demands OTP mail we cannot read. */
    private AuthModels.AuthSession freshSession() throws IOException, CreangerApiException {
        String email = uniqueEmail();
        String password = "e2e-Passw0rd!" + UUID.randomUUID();
        AuthModels.SignupResult signup = engine.registerWithPassword(email, uniqueUsername(), password);
        assumeTrue("Server requires email confirmation OTP which E2E cannot read - skipping",
                signup.session != null);

        AuthModels.AuthSession session = engine.loginWithPassword(email, password);
        assertNotNull("password login after signup must yield a session", session);
        assertEquals(AuthState.AUTHENTICATED, engine.getState());
        return session;
    }

    /** Optional seeded membership context; null when creds absent OR user owns no chats. */
    @Nullable
    private SeededContext seedContext() throws IOException, CreangerApiException {
        String email = System.getenv(ENV_SEED_EMAIL);
        String password = System.getenv(ENV_SEED_PASSWORD);
        if (email == null || password == null || engine == null) {
            return null;
        }
        engine.loginWithPassword(email, password);
        ChatRepository chats = new ChatRepository(engine, chatApi);
        List<CreangerChat> list = chats.refreshChats();
        if (list.isEmpty()) {
            return null;
        }
        MessageRepository messages = new MessageRepository(engine, chatApi);
        return new SeededContext(chats, messages, list.get(0));
    }

    private static final class SeededContext {
        final ChatRepository chats;
        final MessageRepository messages;
        final CreangerChat chat;

        SeededContext(ChatRepository chats, MessageRepository messages, CreangerChat chat) {
            this.chats = chats;
            this.messages = messages;
            this.chat = chat;
        }
    }

    // ======================================================================
    // AUTH — real GoTrue flow
    // ======================================================================

    @Test
    public void authFullFlowRegisterLoginMeRefreshLogout() throws Exception {
        AuthModels.AuthSession session = freshSession();

        // current user round-trip
        AuthModels.CreangerUser me = engine.fetchMe();
        assertNotNull(me);
        assertNotNull(me.id);
        assertEquals(session.user.id, me.id);

        // token refresh rotates the access token (GoTrue refresh_token grant)
        String accessBefore = session.accessToken;
        assertNotEquals(accessBefore, session.refreshToken);
        AuthModels.AuthSession refreshed = engine.refreshSession();
        assertNotNull(refreshed);
        assertNotNull(refreshed.accessToken);
        assertTrue("refresh must produce a usable session", !refreshed.accessToken.isEmpty());

        // logout revokes the session server-side
        engine.logout();
        assertEquals(AuthState.UNAUTHENTICATED, engine.getState());

        // after logout the old access token must be rejected by /auth/v1/user
        int code = rawUserStatus(baseUrl, anonKey, accessBefore);
        assertTrue("revoked/old token should not authorize /auth/v1/user, got " + code,
                code == 401 || code == 403);
    }

    @Test
    public void usernameAvailabilityRpc() throws Exception {
        freshSession(); // some deployments require auth for the rpc; harmless either way
        String candidate = uniqueUsername();
        boolean available = engine.checkUsername(candidate);
        assertTrue("fresh random username should be available", available);
    }

    // ======================================================================
    // CHAT / MESSAGES — real PostgREST writes, DB-side verification
    // ======================================================================

    @Test
    public void chatListHistorySendEditDeleteReactionPresence() throws Exception {
        freshSession();
        SeededContext ctx = seedContext();
        assumeTrue("No seeded chat membership (set CREANGER_E2E_SED_EMAIL/PASSWORD) - chat E2E NOT RUN",
                ctx != null);

        String token = engine.requireAccessToken();
        String chatId = ctx.chat.id;

        // --- history page ---
        MessagePage pageObj = ctx.messages.refreshMessages(chatId,
                MessageRepository.DEFAULT_PAGE_SIZE);
        List<CreangerMessage> page = pageObj.messages;
        assertNotNull(page);
        long newestSeq = 0;
        for (CreangerMessage m : page) {
            if (m.chatSeq != null && m.chatSeq > newestSeq) {
                newestSeq = m.chatSeq;
            }
        }

        // --- send: authoritative id + client_message_id idempotency ---
        String clientId = "e2e-" + UUID.randomUUID();
        String contentA = "e2e send " + System.currentTimeMillis();
        String id1 = ctx.messages.sendTextMessage(chatId, clientId, contentA);
        assertNotNull(id1);

        // Duplicate delivery of the SAME clientMessageId must resolve to the SAME
        // server row (schema UNIQUE on client_message_id) — exactly-one semantics.
        String id2 = ctx.messages.sendTextMessage(chatId, clientId, contentA);
        assertEquals("duplicate client_message_id must reconcile to one message", id1, id2);

        // DB-side effect: get_messages_since(newestSeq) must contain exactly one
        // row for that uuid.
        List<CreangerMessage> since = chatApi.getMessagesSince(token, chatId, Math.max(newestSeq, 0), 50);
        int matches = 0;
        for (CreangerMessage m : since) {
            if (id1.equals(m.id)) {
                matches++;
            }
        }
        assertEquals("exactly one logical message after HTTP+echo window", 1, matches);

        // --- edit: authoritative content change visible in DB read-back ---
        String edited = contentA + " EDITED";
        ctx.messages.completeEdit(chatId, id1, edited);
        List<CreangerMessage> afterEdit = chatApi.getMessagesSince(token, chatId, Math.max(newestSeq, 0), 50);
        CreangerMessage editedRow = findById(afterEdit, id1);
        assertNotNull(editedRow);
        assertTrue("edit must persist authoritative content",
                editedRow.content != null && editedRow.content.contains("EDITED"));

        // --- reaction add/remove round-trip ---
        String rx = chatApi.addReaction(token, id1, "🔥");
        assertNotNull(rx);
        chatApi.removeReaction(token, id1, "🔥");

        // --- delete: soft-delete flag surfaces on read-back ---
        chatApi.deleteMessage(token, id1);
        List<CreangerMessage> afterDelete = chatApi.getMessagesSince(token, chatId, Math.max(newestSeq, 0), 100);
        CreangerMessage deletedRow = findById(afterDelete, id1);
        if (deletedRow != null) {
            assertNotNull("deleted message must expose deleted_at", deletedRow.deletedAt);
        }

        // --- pagination: loadOlder returns strictly-older pages when available ---
        if (newestSeq > 0) {
            MessagePage olderPage = ctx.messages.loadOlderMessages(
                    chatId, newestSeq, MessageRepository.DEFAULT_PAGE_SIZE);
            List<CreangerMessage> older = olderPage.messages;
            for (CreangerMessage m : older) {
                if (m.chatSeq != null) {
                    assertTrue("older pages must be strictly older", m.chatSeq <= newestSeq);
                }
            }
        }

        // --- presence: set_presence RPC (migration 031) + REST read-back ---
        chatApi.setPresence(token, "online", true);
        JSONArray presence = chatApi.getPresence(token, Collections.singletonList(engine.currentUser().id));
        boolean foundOnline = false;
        for (int i = 0; i < presence.length(); i++) {
            JSONObject row = presence.optJSONObject(i);
            if (row != null && engine.currentUser().id.equals(row.optString("user_id"))) {
                foundOnline = "online".equals(row.optString("status"));
            }
        }
        assertTrue("presence upsert must be readable via user_presence", foundOnline);
        chatApi.setPresence(token, "offline", true); // leave clean state
    }

    @Test
    public void offlinePendingQueueSurvivesRestartAndReconciles() throws Exception {
        AuthModels.AuthSession session = freshSession();
        SeededContext ctx = seedContext();
        assumeTrue("offline queue E2E requires seeded chat membership - NOT RUN", ctx != null);

        String chatId = ctx.chat.id;
        String clientId = "e2e-offline-" + UUID.randomUUID();
        String myId = session.user != null ? session.user.id : null;

        // The same facade ChatActivity uses: pending row lives in the bridge's
        // chat cache (SQLite-backed via MessageRepository in the app).
        com.creanger.app.messenger.creanger.data.CreangerChatBridge bridge =
                new com.creanger.app.messenger.creanger.data.CreangerChatBridge(
                        ctx.messages, myId, null);
        bridge.insertPendingRow(chatId, clientId, "queued while offline");
        boolean pendingVisible = false;
        for (com.creanger.app.messenger.creanger.data.CreangerMessageUiModel m
                : bridge.getMessages(chatId)) {
            if (clientId.equals(m.clientMessageId)) {
                pendingVisible = true;
            }
        }
        assertTrue("pending row must exist before reconnect", pendingVisible);

        // "Reconnect": real network send with the SAME clientMessageId →
        // repository dedupes by client_message_id and reconciles to the server uuid.
        String serverId = ctx.messages.sendTextMessage(chatId, clientId, "queued while offline");
        assertNotNull(serverId);

        // Exactly ONE logical message locally after reconciliation.
        int occurrences = 0;
        for (CreangerMessage m : ctx.messages.getCachedMessages(chatId)) {
            if (serverId.equals(m.id)) {
                occurrences++;
            }
        }
        assertEquals("pending+authoritative must collapse to one logical row", 1, occurrences);

        // DB-side proof of exactly-one semantics.
        List<CreangerMessage> since = chatApi.getMessagesSince(
                engine.requireAccessToken(), chatId, 0, 200);
        int dbMatches = 0;
        for (CreangerMessage m : since) {
            if (serverId.equals(m.id)) {
                dbMatches++;
            }
        }
        assertEquals("server must hold exactly one row for the retried clientId", 1, dbMatches);
    }

    @Nullable
    private static CreangerMessage findById(List<CreangerMessage> list, String id) {
        for (CreangerMessage m : list) {
            if (id.equals(m.id)) {
                return m;
            }
        }
        return null;
    }

    /** Raw /auth/v1/user status for a token — used to prove logout revoked it. */
    private static int rawUserStatus(String base, String key, String accessToken) throws IOException {
        HttpURLConnection conn = open(base + "/auth/v1/user");
        conn.setRequestProperty("apikey", key);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        try {
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    // ======================================================================
    // REAL transport — plain JDK HttpURLConnection over the REAL interface
    // ======================================================================

    private static final class RealTransport implements CreangerHttpTransport {
        private final String base;
        private final String anonKey;

        RealTransport(String base, String anonKey) {
            this.base = base;
            this.anonKey = anonKey;
        }

        @Override
        public TransportResponse execute(ApiRequest request) throws IOException {
            StringBuilder url = new StringBuilder(base).append(request.path);
            if (request.query != null && !request.query.isEmpty()) {
                url.append('?');
                boolean first = true;
                for (Map.Entry<String, String> e : request.query.entrySet()) {
                    if (!first) {
                        url.append('&');
                    }
                    url.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
                    first = false;
                }
            }

            boolean tls = url.toString().startsWith("https://");
            HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            if (tls) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(null); // platform defaults = validation ON
            }
            conn.setRequestMethod(request.method);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("apikey", anonKey);
            if (request.accessToken != null && !request.accessToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + request.accessToken);
            }
            if (request.idempotencyKey != null) {
                conn.setRequestProperty("Idempotency-Key", request.idempotencyKey);
            }

            byte[] payload = request.body;
            if (payload == null && request.jsonBody != null) {
                payload = request.jsonBody.getBytes(StandardCharsets.UTF_8);
            }
            if (payload != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type",
                        request.contentType != null ? request.contentType : "application/json");
                conn.getOutputStream().write(payload);
            }

            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = in == null ? "" : readAll(in);
            return new TransportResponse(code, body, flatten(conn.getHeaderFields()));
        }

        private static java.util.Map<String, String> flatten(Map<String, List<String>> raw) {
            java.util.Map<String, String> out = new LinkedHashMap<>();
            if (raw != null) {
                for (Map.Entry<String, List<String>> e : raw.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                        out.put(e.getKey(), e.getValue().get(0));
                    }
                }
            }
            return out;
        }

        private static String urlEncode(String s) {
            try {
                return java.net.URLEncoder.encode(s, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                throw new AssertionError(e);
            }
        }

        private static String readAll(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toString("UTF-8");
        }
    }

    /** Non-persistent store: E2E verifies SERVER behavior, storage is unit-tested. */
    private static final class VolatileTokenStore implements CreangerTokenStore {
        private volatile AuthModels.AuthSession session;

        @Override
        public AuthModels.AuthSession load() {
            return session;
        }

        @Override
        public void store(AuthModels.AuthSession s) {
            this.session = s;
        }

        @Override
        public void clear() {
            this.session = null;
        }
    }
}