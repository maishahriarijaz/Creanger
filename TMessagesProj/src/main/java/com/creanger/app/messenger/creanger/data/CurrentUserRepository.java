package com.creanger.app.messenger.creanger.data;

import androidx.annotation.Nullable;

import com.creanger.app.messenger.creanger.api.CreangerApiException;
import com.creanger.app.messenger.creanger.auth.AuthState;
import com.creanger.app.messenger.creanger.auth.CreangerAuthEngine;
import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;
import com.creanger.app.messenger.creanger.model.AuthModels.CreangerUser;
import com.creanger.app.messenger.creanger.storage.CreangerTokenStore;

import java.io.IOException;

/**
 * Minimal current-user/profile foundation for the Creanger auth layer.
 *
 * Responsibilities:
 *  - expose the authenticated {@link CreangerUser} (from the in-memory session)
 *  - serve a cached copy persisted by {@link CreangerTokenStore} when the app
 *    is restarted (auth-state restoration without a network round-trip)
 *  - refresh the current user from GoTrue {@code GET /auth/v1/user}
 *  - clear the user + session on logout
 *
 * Deliberately isolated from TLRPC: this only models the fields actually
 * returned by the Custom Auth Server (id/username/email/emailVerified/
 * displayName/avatarUrl/createdAt). Future Telegram UI mapping is a later
 * concern and lives outside this class.
 */
public final class CurrentUserRepository {

    private final CreangerAuthEngine engine;
    private final CreangerTokenStore tokenStore;

    public CurrentUserRepository(CreangerAuthEngine engine, CreangerTokenStore tokenStore) {
        this.engine = engine;
        this.tokenStore = tokenStore;
    }

    public boolean isAuthenticated() {
        return engine.getState() == AuthState.AUTHENTICATED;
    }

    public AuthState getState() {
        return engine.getState();
    }

    /**
     * Current user from the live session, or null when unauthenticated.
     */
    @Nullable
    public CreangerUser getCurrentUser() {
        return engine.currentUser();
    }

    /**
     * User available without any network call: falls back to the persisted
     * token-store copy so app-restart restoration works offline.
     */
    @Nullable
    public CreangerUser getCachedUser() {
        CreangerUser live = engine.currentUser();
        if (live != null) {
            return live;
        }
        AuthSession stored = tokenStore.load();
        return stored != null ? stored.user : null;
    }

    /**
     * Fetches the current user from GoTrue ({@code GET /auth/v1/user}),
     * refreshing the access token first if needed. The returned user is also
     * persisted via the token store for offline restoration.
     */
    public CreangerUser refreshCurrentUser() throws IOException, CreangerApiException {
        return engine.fetchMe();
    }

    /** Clears the local user state and persisted tokens (local logout). */
    public void clear() {
        engine.clearLocalSession();
    }
}
