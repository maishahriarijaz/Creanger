package com.creanger.app.messenger.creanger.data;

import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.api.CreangerChatApiClient;
import com.creanger.app.messenger.creanger.auth.AuthState;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.model.ChatModels.ChatMember;
import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat foundation repository for the Creanger layer.
 *
 * Responsibilities:
 *  - list the authenticated user's chats (RLS-scoped on the server)
 *  - list membership records of a chat
 *  - cache minimal metadata for fast UI rendering / offline restarts
 *  - clear everything on local logout / account switch
 *
 * The data plane is PostgREST/Supabase REST (see {@link CreangerChatApiClient});
 * the access-token JWT is obtained through {@link CreangerAuthEngine} so the
 * server-side RLS authorization model is respected end to end. Deliberately
 * isolated from TLRPC: chat IDs are Creanger UUID strings only.
 */
public final class ChatRepository {

    private final CreangerAuthEngine engine;
    private final CreangerChatApiClient chatApi;
    // Minimal metadata cache, keyed by the owning user id so logs out / account
    // switches never leak a previous account's chats.
    private final Map<String, List<CreangerChat>> chatsCache = new ConcurrentHashMap<>();
    private final Map<String, List<ChatMember>> membersCache = new ConcurrentHashMap<>();
    private final Map<String, String> chatUsers = new ConcurrentHashMap<>();

    public ChatRepository(CreangerAuthEngine engine, CreangerChatApiClient chatApi) {
        this.engine = engine;
        this.chatApi = chatApi;
    }

    public boolean isAuthenticated() {
        return engine.getState() == AuthState.AUTHENTICATED;
    }

    public AuthState getState() {
        return engine.getState();
    }

    /** Cached chats for the current user, or an empty list when none cached. */
    public List<CreangerChat> getCachedChats() {
        List<CreangerChat> chats = chatsCache.get(key());
        return chats != null ? chats : Collections.emptyList();
    }

    /**
     * Fetches the authenticated user's chats from the chat data plane and
     * caches them. Requires a usable access token (single-flight refresh when
     * needed, via the engine).
     */
    public List<CreangerChat> refreshChats() throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
        List<CreangerChat> chats = chatApi.listChats(access);
        chatsCache.put(key(), immutableCopy(chats));
        return chats;
    }

    /** Cached members of a chat for the current user, or an empty list. */
    public List<ChatMember> getCachedMembers(String chatId) {
        String cacheKey = chatId + "|" + key();
        List<ChatMember> members = membersCache.get(cacheKey);
        return members != null ? members : Collections.emptyList();
    }

    /**
     * Fetches membership records of a chat. The caller must already be able to
     * see the chat (the server enforces this via RLS on the chat_members
     * select policy).
     */
    public List<ChatMember> refreshMembers(String chatId) throws IOException, CreangerApiException {
        String access = engine.requireAccessToken();
List<ChatMember> members = chatApi.listMembers(access, chatId);
        membersCache.put(chatId + "|" + key(), immutableCopy(members));
        return members;
    }

    /** Clears the local chat cache (local logout / account isolation). */
    public void clearCachedChats() {
        chatsCache.clear();
        membersCache.clear();
        chatUsers.clear();
    }

    /** Logs out locally: wipes tokens + cache via the engine. */
    public void clear() {
        clearCachedChats();
        engine.clearLocalSession();
    }

    // ---- helpers ----

    /**
     * Cache key for the current account. Uses the profile user id when known;
     * otherwise falls back to a tag derived from the refresh token so the cache
     * is never shared across accounts even before the profile is fetched.
     */
    private String key() {
        if (engine.currentUser() != null && engine.currentUser().id != null) {
            return engine.currentUser().id;
        }
        String refresh = engine.currentRefreshToken();
        return refresh != null ? "tok:" + refresh : "";
    }

    private static <T> List<T> immutableCopy(List<T> src) {
        return Collections.unmodifiableList(new ArrayList<>(src));
    }
}