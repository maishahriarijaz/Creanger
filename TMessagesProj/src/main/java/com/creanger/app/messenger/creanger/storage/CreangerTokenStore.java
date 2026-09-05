package com.creanger.app.messenger.creanger.storage;

import com.creanger.app.messenger.creanger.model.AuthModels.AuthSession;

/**
 * Persistence boundary for tokens. Splits access vs refresh handling:
 * the refresh token is stored durably (Keystore-encrypted) while the short
 * lived access token is kept in memory and rebuilt via {@code /refresh}.
 */
public interface CreangerTokenStore {

    /** @return the persisted session or null when signed out. */
    AuthSession load();

    /** Persists the session's refresh token (and whatever the impl chooses). */
    void store(AuthSession session);

    /** Clears all persisted tokens. Keep the implementation best-effort. */
    void clear();
}