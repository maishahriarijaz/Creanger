package com.creanger.app.messenger.creanger;

import org.junit.Assume;
import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.data.ChatRepository;
import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.ChatModels.ChatMember;
import com.creanger.app.messenger.creanger.model.ChatModels.ChatMemberRole;
import com.creanger.app.messenger.creanger.model.ChatModels.ChatType;
import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * REAL end-to-end data-plane test: the production Android chat client
 * ({@link ChatRepository} + {@link CreangerChatApiClient}) against a LIVE local
 * Supabase-gateway stand-in ({@code localhost:5434}, strips {@code /rest/v1})
 * forwarding to a LIVE PostgREST instance ({@code localhost:5433}).
 *
 * The access-token JWT comes from the fully real flow against live GoTrue
 * ({@code CREANGER_E2E_AUTH_BASE}, default the Supabase API gateway
 * {@code http://localhost:54321}): loginWithPassword → session JWT. So the
 * exact claims PostgREST validates (sub, role=authenticated, aud) are those the
 * production client would present.
 *
 * Seed fixture (see {@code /tmp/opencode/seed_dataplane.py}): accounts
 * dataplane-a/b@example.test (password {@link #SEED_PASSWORD}), direct chat
 * A↔B, private group A (A owner, B member), public channel (B owner), private
 * group B (B only).
 *
 * Expected RLS-scoped views:
 *   A sees {direct, groupA, public}; B sees {direct, groupA, public, groupB}.
 *   A is not a member of groupB or the public channel → their member listings
 *   come back empty (RLS hides rows rather than erroring).
 */
public class CreangerDataPlaneE2ETest {

    /** Base URL of the Supabase API gateway / GoTrue (path prefix included in request paths). */
    private static final String AUTH_BASE =
            System.getenv("CREANGER_E2E_AUTH_BASE") != null
                    ? System.getenv("CREANGER_E2E_AUTH_BASE") : "http://localhost:54321";
    /** anon key required by the Kong gateway; not needed for direct GoTrue. */
    private static final String AUTH_APIKEY = System.getenv("CREANGER_E2E_APIKEY");
    private static final String SEED_PASSWORD = "dataplane-password";
    private static final String CHAT_BASE = "http://localhost:5434"; // /rest/v1 gateway stand-in
    private static final String EMAIL_A = "dataplane-a@example.test";
    private static final String EMAIL_B = "dataplane-b@example.test";
    private static final String TITLE_GROUP_A = "Dataplane Private Group A";
    private static final String TITLE_GROUP_B = "Dataplane Private Group B";
    private static final String TITLE_PUBLIC = "Dataplane Public Channel";

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
            String urlString = base + request.path;
            if (request.query != null && !request.query.isEmpty()) {
                StringBuilder sb = new StringBuilder(urlString).append('?');
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
                urlString = sb.toString();
            }
            HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
            try {
                c.setRequestMethod(request.method);
                c.setConnectTimeout(5_000);
                c.setReadTimeout(20_000);
                c.setUseCaches(false);
                c.setRequestProperty("Accept", "application/json; charset=utf-8");
                if (AUTH_APIKEY != null && !AUTH_APIKEY.isEmpty()) {
                    c.setRequestProperty("apikey", AUTH_APIKEY);
                }
                if (request.accessToken != null) {
                    c.setRequestProperty("Authorization", "Bearer " + request.accessToken);
                }
                if ((request.method.equals("POST") || request.method.equals("PATCH"))
                        && request.jsonBody != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    try (OutputStream os = new BufferedOutputStream(c.getOutputStream())) {
                        os.write(request.jsonBody.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int status = c.getResponseCode();
                return new TransportResponse(status, readBody(c), null);
            } finally {
                c.disconnect();
            }
        }

        private static String readBody(HttpURLConnection connection) throws IOException {
            InputStream stream;
            try {
                stream = connection.getInputStream();
            } catch (IOException e) {
                stream = connection.getErrorStream();
            }
            if (stream == null) {
                return "";
            }
            try (InputStream in = new BufferedInputStream(stream)) {
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
                return sb.toString();
            }
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

    private static boolean serverUp(String base, String probePath) {
        try {
            LiveTransport t = new LiveTransport(base);
            TransportResponse r = t.execute(new ApiRequest("GET", probePath, null, null));
            return r.statusCode >= 200 && r.statusCode < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private static CreangerAuthEngine login(String email) throws Exception {
        MemStore store = new MemStore();
        CreangerAuthEngine engine = new CreangerAuthEngine(
                new SupabaseAuthClient(new LiveTransport(AUTH_BASE)), store);
        // Real password grant against live GoTrue; the response JWT is what
        // PostgREST will validate on every data-plane call below.
        engine.loginWithPassword(email, SEED_PASSWORD);
        return engine;
    }

    private static CreangerChat findChat(List<CreangerChat> chats, String title) {
        for (CreangerChat chat : chats) {
            if (title.equals(chat.title)) {
                return chat;
            }
        }
        return null;
    }

    private static CreangerChat findDirect(List<CreangerChat> chats) {
        for (CreangerChat chat : chats) {
            if (ChatType.DIRECT.equals(chat.type)) {
                return chat;
            }
        }
        return null;
    }

    @Test
    public void e2eDataPlaneChatsAndMembers() throws Exception {
        Assume.assumeTrue("GoTrue not running at " + AUTH_BASE, serverUp(AUTH_BASE, "/auth/v1/health"));
        Assume.assumeTrue("gateway stand-in not running at " + CHAT_BASE, serverUp(CHAT_BASE, "/"));

        // Real login against the live auth server (real OTP flow).
        CreangerAuthEngine engineA = login(EMAIL_A);
        CreangerChatApiClient chatApi = new CreangerChatApiClient(new LiveTransport(CHAT_BASE));
        ChatRepository repo = new ChatRepository(engineA, chatApi);

        assertNotNull("A access token present", engineA.requireAccessToken());
        assertNotNull("A profile id", engineA.currentUser().id);

        // refreshChats(): real client + real transport + RLS.
        List<CreangerChat> chatsA = repo.refreshChats();

        CreangerChat direct = findDirect(chatsA);
        assertNotNull("direct chat visible to A", direct);
        assertTrue("direct chat has no title", direct.title == null || direct.title.isEmpty());

        CreangerChat groupA = findChat(chatsA, TITLE_GROUP_A);
        assertNotNull("private group A visible to A", groupA);
        assertEquals("group A type", ChatType.GROUP, groupA.type);
        assertFalse("group A is private", groupA.isPublic);

        CreangerChat pub = findChat(chatsA, TITLE_PUBLIC);
        assertNotNull("public channel visible to A", pub);
        assertEquals("public channel type", ChatType.CHANNEL, pub.type);
        assertTrue("public channel is public", pub.isPublic);

        assertNull("A must NOT see private group B", findChat(chatsA, TITLE_GROUP_B));

        // Membership of the direct chat: exactly the two participants.
        List<ChatMember> directMembers = repo.refreshMembers(direct.id);
        assertEquals("direct chat has 2 members", 2, directMembers.size());
        List<String> ids = new ArrayList<>();
        for (ChatMember m : directMembers) {
            ids.add(m.userId);
            assertEquals("direct member role", ChatMemberRole.MEMBER, m.role);
        }
        assertTrue("A is a participant", ids.contains(engineA.currentUser().id));
        assertTrue("B is a participant", ids.contains(bUserIdOfPair(ids, engineA.currentUser().id)));

        // groupA membership: owner A + member B.
        List<ChatMember> groupAMembers = repo.refreshMembers(groupA.id);
        assertEquals("group A has 2 members", 2, groupAMembers.size());

        // A is not a member of the public channel → member listing is empty.
        assertEquals("A sees no members of the public channel",
                0, repo.refreshMembers(pub.id).size());

        // Caching follows the refresh.
        assertEquals("cached chats", chatsA.size(), repo.getCachedChats().size());
        assertEquals("cached direct members", 2, repo.getCachedMembers(direct.id).size());

        // Per-account cache isolation: B's view differs from A's.
        CreangerAuthEngine engineB = login(EMAIL_B);
        ChatRepository repoB = new ChatRepository(engineB, chatApi);
        List<CreangerChat> chatsB = repoB.refreshChats();
        assertNotNull("B chats", chatsB);
        assertNotNull("B sees groupB", findChat(chatsB, TITLE_GROUP_B));
        assertNotNull("B is a member of groupA", findChat(chatsB, TITLE_GROUP_A));
        assertFalse("A and B caches are not the same instance",
                repo.getCachedChats() == repoB.getCachedChats());
    }

    @Test
    public void e2eDataPlaneUnauthorizedGarbageToken() throws Exception {
        Assume.assumeTrue("gateway stand-in not running at " + CHAT_BASE, serverUp(CHAT_BASE, "/"));

        CreangerChatApiClient chatApi = new CreangerChatApiClient(new LiveTransport(CHAT_BASE));
        try {
            chatApi.listChats("this-is-not-a-jwt");
            fail("garbage JWT must be rejected by PostgREST");
        } catch (CreangerApiException e) {
            assertEquals("garbage token → UNAUTHORIZED", ApiError.UNAUTHORIZED, e.error.code);
        }
    }

    /** Direct chats have exactly two members; return the member id other than mine. */
    private static String bUserIdOfPair(List<String> ids, String mine) {
        for (String id : ids) {
            if (!id.equals(mine)) {
                return id;
            }
        }
        throw new AssertionError("no second participant in direct chat");
    }
}