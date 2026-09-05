package com.creanger.app.messenger.creanger;

import org.junit.Test;
import com.creanger.app.messenger.creanger.api.ApiRequest;
import com.creanger.app.messenger.creanger.api.SupabaseAuthClient;
import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.api.CreangerHttpTransport;
import com.creanger.app.messenger.creanger.api.TransportResponse;
import com.creanger.app.messenger.creanger.auth.AuthState;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.data.ChatRepository;
import com.creanger.app.messenger.creanger.model.ApiError;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.model.AuthModels.DeviceInfo;
import com.creanger.app.messenger.creanger.model.AuthModels.Fingerprint;
import com.creanger.app.messenger.creanger.model.ChatModels.ChatMember;
import com.creanger.app.messenger.creanger.model.ChatModels.ChatMemberRole;
import com.creanger.app.messenger.creanger.model.ChatModels.ChatType;
import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Chat foundation tests: RLS-scoped chat listing, membership listing, empty
 * state, unauthorized (PostgREST 401/403 → typed error), the minimal per-user
 * cache, logout / account-switch isolation, and malformed/network failures.
 *
 * These run against a scripted transport returning raw PostgREST JSON arrays;
 * the real network path (JWT Bearer → Supabase/PostgREST) is covered by the
 * auth E2E + the transport contract.
 */
public class ChatRepositoryTest {

    private static final class ScriptedTransport implements CreangerHttpTransport {
        final List<TransportResponse> responses = new ArrayList<>();
        final List<ApiRequest> requests = new ArrayList<>();
        volatile IOException failAll = null;

        @Override
        public TransportResponse execute(ApiRequest request) throws IOException {
            requests.add(request);
            if (failAll != null) {
                throw failAll;
            }
            TransportResponse response = responses.remove(0);
            if (response == null) {
                throw new IOException("no canned response for " + request.method + " " + request.path);
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

    private static AuthSession freshSession(String access, String refresh, CreangerUser user) {
        return new AuthSession(access, refresh, user, System.currentTimeMillis());
    }

    private static CreangerUser user(String id, String username, String email) {
        return new CreangerUser(id, username, email, true, null, null, null);
    }

    private static DeviceInfo device() {
        return new DeviceInfo("android", "Pixel 7", new Fingerprint("android", "en_US", "UTC"));
    }

    private static ChatRepository repo(ScriptedTransport t, InMemoryStore store, CreangerAuthEngine engine) {
        return new ChatRepository(engine, new CreangerChatApiClient(t));
    }

    private static CreangerAuthEngine engineWith(ScriptedTransport t, InMemoryStore store) {
        return new CreangerAuthEngine(new SupabaseAuthClient(t), store);
    }

    // ---- fetch chats (RLS-scoped), cached for the user ----

    @Test
    public void refreshChatsListsRlsScopedChatsAndCaches() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(freshSession("acc-live", "ref", user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, chatsJson(
                "{\"id\":\"chat-1\",\"type\":\"group\",\"title\":\"Team\",\"username\":null,"
                        + "\"description\":\"our group\",\"avatar_media_id\":null,\"owner_id\":\"uuid-1\","
                        + "\"is_verified\":false,\"is_public\":false,\"is_archived\":false,"
                        + "\"created_at\":\"2026-08-15T05:00:00.000Z\",\"updated_at\":\"2026-08-15T05:01:00.000Z\",\"deleted_at\":null}",
                "{\"id\":\"chat-2\",\"type\":\"direct\",\"title\":null,\"username\":null,"
                        + "\"description\":null,\"avatar_media_id\":null,\"owner_id\":null,"
                        + "\"is_verified\":false,\"is_public\":false,\"is_archived\":false,"
                        + "\"created_at\":\"2026-08-15T06:00:00.000Z\",\"updated_at\":\"2026-08-15T06:01:00.000Z\",\"deleted_at\":null}")));
        ChatRepository chatRepo = repo(t, store, engineWith(t, store));

        List<CreangerChat> chats = chatRepo.refreshChats();

        assertEquals(2, chats.size());
        CreangerChat team = chats.get(0);
        assertEquals("chat-1", team.id);
        assertEquals(ChatType.GROUP, team.type);
        assertEquals("Team", team.title);
        assertEquals("our group", team.description);
        assertEquals("uuid-1", team.ownerId);
        assertTrue(team.isDirect() == false);
        CreangerChat direct = chats.get(1);
        assertEquals(ChatType.DIRECT, direct.type);
        assertNull(direct.title);
        assertTrue(direct.isDirect());
        // Only the chat list hit the wire; nothing else.
        assertEquals(1, t.requests.size());
        assertEquals("/rest/v1/chats", t.requests.get(0).path);
        // Cache serves without network.
        assertEquals(2, chatRepo.getCachedChats().size());
    }

    @Test
    public void refreshChatsEmptyReturnsEmptyList() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(freshSession("acc", "ref", user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "[]"));
        ChatRepository chatRepo = repo(t, store, engineWith(t, store));

        List<CreangerChat> chats = chatRepo.refreshChats();

        assertTrue(chats.isEmpty());
        assertTrue(chatRepo.getCachedChats().isEmpty());
    }

    // ---- membership listing ----

    @Test
    public void refreshMembersListsMembersOfChat() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(freshSession("acc", "ref", user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, membersJson(
                "{\"id\":\"m-1\",\"chat_id\":\"chat-1\",\"user_id\":\"uuid-1\",\"role\":\"owner\","
                        + "\"joined_at\":\"2026-08-15T05:00:00.000Z\",\"left_at\":null,\"muted_until\":null,"
                        + "\"pinned_position\":1,\"last_read_at\":\"2026-08-15T05:30:00.000Z\"}",
                "{\"id\":\"m-2\",\"chat_id\":\"chat-1\",\"user_id\":\"uuid-2\",\"role\":\"member\","
                        + "\"joined_at\":\"2026-08-15T05:03:00.000Z\",\"left_at\":null,\"muted_until\":null,"
                        + "\"pinned_position\":null,\"last_read_at\":null}")));
        ChatRepository chatRepo = repo(t, store, engineWith(t, store));

        List<ChatMember> members = chatRepo.refreshMembers("chat-1");

        assertEquals(2, members.size());
        ChatMember owner = members.get(0);
        assertEquals(ChatMemberRole.OWNER, owner.role);
        assertEquals("chat-1", owner.chatId);
        assertEquals(Integer.valueOf(1), owner.pinnedPosition);
        ChatMember member = members.get(1);
        assertEquals(ChatMemberRole.MEMBER, member.role);
        assertNull(member.pinnedPosition);
        assertNull(member.leftAt);
        assertEquals(1, t.requests.size());
        assertEquals("/rest/v1/chat_members", t.requests.get(0).path);
        assertEquals(2, chatRepo.getCachedMembers("chat-1").size());
    }

    // ---- POSTGREST error: unauthorized ----

    @Test
    public void unauthorizedFromDataPlaneSurfacesTypedError() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(freshSession("acc", "ref", user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(401, "{\"message\":\"JWT\"}"));
        ChatRepository chatRepo = repo(t, store, engineWith(t, store));

        try {
            chatRepo.refreshChats();
            fail("expected CreangerApiException for 401");
        } catch (CreangerApiException e) {
            assertTrue(e.is(ApiError.UNAUTHORIZED));
        }
    }

    @Test
    public void forbiddenFromDataPlaneSurfacesTypedError() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(freshSession("acc", "ref", user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(403, ""));
        ChatRepository chatRepo = repo(t, store, engineWith(t, store));

        try {
            chatRepo.refreshChats();
            fail("expected CreangerApiException for 403");
        } catch (CreangerApiException e) {
            assertTrue(e.is(ApiError.UNAUTHORIZED));
        }
    }

    @Test
    public void postgrestServerErrorSurfacesCode() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(freshSession("acc", "ref", user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(500, "{\"code\":\"PGRST500\",\"message\":\"failed\"}"));
        ChatRepository chatRepo = repo(t, store, engineWith(t, store));

        try {
            chatRepo.refreshChats();
            fail("expected CreangerApiException");
        } catch (CreangerApiException e) {
            assertTrue(e.is("PGRST500"));
        }
    }

    // ---- malformed body ----

    @Test
    public void malformedChatsBodyThrowsTypedError() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(freshSession("acc", "ref", user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "not-json-at-all"));
        ChatRepository chatRepo = repo(t, store, engineWith(t, store));

        try {
            chatRepo.refreshChats();
            fail("expected CreangerApiException for malformed body");
        } catch (CreangerApiException e) {
            assertTrue(e.is(ApiError.INTERNAL_ERROR));
        }
    }

    // ---- network failures keep the cache ----

    @Test
    public void networkFailureThrowsIoAndKeepsCache() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(freshSession("acc", "ref", user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, chatsJson(
                "{\"id\":\"chat-1\",\"type\":\"group\",\"title\":\"Team\",\"owner_id\":\"uuid-1\","
                        + "\"is_verified\":false,\"is_public\":false,\"is_archived\":false,"
                        + "\"created_at\":\"2026-08-15T05:00:00.000Z\",\"updated_at\":\"2026-08-15T05:01:00.000Z\",\"deleted_at\":null}")));
        ChatRepository chatRepo = repo(t, store, engineWith(t, store));
        assertEquals(1, chatRepo.refreshChats().size());

        t.failAll = new IOException("no route to host");
        try {
            chatRepo.refreshChats();
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("no route to host", e.getMessage());
        }
        // Offline fallback serves the cached list.
        assertEquals(1, chatRepo.getCachedChats().size());
    }

    // ---- logout wipes the cache ----

    @Test
    public void clearWipesChatCacheAndSession() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(freshSession("acc", "ref", user("uuid-1", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        t.responses.add(t.json(200, "[]"));
        ChatRepository chatRepo = repo(t, store, engineWith(t, store));
        chatRepo.refreshChats();
        assertEquals(AuthState.AUTHENTICATED, chatRepo.getState());

        chatRepo.clear();

        assertEquals(AuthState.UNAUTHENTICATED, chatRepo.getState());
        assertFalse(chatRepo.isAuthenticated());
        assertTrue(chatRepo.getCachedChats().isEmpty());
        assertNull(store.stored);
    }

    // ---- account switching must not leak the previous account ----

    @Test
    public void accountSwitchDoesNotLeakPreviousChats() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.store(freshSession("acc-A", "ref-A", user("uuid-A", "alice", "a@x.com")));
        ScriptedTransport t = new ScriptedTransport();
        // Account A fetches chats.
        t.responses.add(t.json(200, chatsJson(
                "{\"id\":\"chat-A\",\"type\":\"group\",\"title\":\"A-team\",\"owner_id\":\"uuid-A\","
                        + "\"is_verified\":false,\"is_public\":false,\"is_archived\":false,"
                        + "\"created_at\":\"2026-08-15T05:00:00.000Z\",\"updated_at\":\"2026-08-15T05:01:00.000Z\",\"deleted_at\":null}")));
        CreangerAuthEngine engine = engineWith(t, store);
        ChatRepository chatRepo = repo(t, store, engine);
        assertEquals(1, chatRepo.refreshChats().size());

        // Account B logs in (login/verify response carries user B).
        t.responses.add(t.json(200,
                "{\"access_token\":\"acc-B\",\"refresh_token\":\"ref-B\","
                        + "\"user\":{\"id\":\"uuid-B\",\"email\":\"b@x.com\",\"user_metadata\":{\"username\":\"bob\"}}}"));
        engine.loginWithPassword("b@x.com", "123456");

        // Account B must not see account A's cached chats; cache returns empty.
        assertTrue("previous account chats must not leak", chatRepo.getCachedChats().isEmpty());
        assertEquals("account switched to B", "uuid-B", engine.currentUser().id);

        // Account B can fetch its own chats freshly.
        t.responses.add(t.json(200, chatsJson(
                "{\"id\":\"chat-B\",\"type\":\"channel\",\"title\":\"B-news\",\"owner_id\":\"uuid-B\","
                        + "\"is_verified\":false,\"is_public\":true,\"is_archived\":false,"
                        + "\"created_at\":\"2026-08-15T07:00:00.000Z\",\"updated_at\":\"2026-08-15T07:01:00.000Z\",\"deleted_at\":null}")));
        List<CreangerChat> bChats = chatRepo.refreshChats();
        assertEquals(1, bChats.size());
        assertEquals("chat-B", bChats.get(0).id);
        assertEquals("uuid-B", bChats.get(0).ownerId);
    }

    // ---- raw PostgREST array helpers ----

    private static String chatsJson(String... rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(rows[i]);
        }
        return sb.append(']').toString();
    }

    private static String membersJson(String... rows) {
        return chatsJson(rows);
    }
}