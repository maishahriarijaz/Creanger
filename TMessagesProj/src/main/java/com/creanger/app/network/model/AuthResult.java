package com.creanger.app.network.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Authentication result from custom backend.
 */
public class AuthResult {

    @NonNull
    public final String accessToken;
    @Nullable
    public final String refreshToken;
    @NonNull
    public final UserModel user;
    public final long expiresInSeconds;
    public final long issuedAt;

    public AuthResult(@NonNull String accessToken, @Nullable String refreshToken, @NonNull UserModel user, long expiresInSeconds) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.user = user;
        this.expiresInSeconds = expiresInSeconds;
        this.issuedAt = System.currentTimeMillis();
    }

    public boolean isAccessTokenExpired() {
        return System.currentTimeMillis() >= issuedAt + (expiresInSeconds * 1000L);
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isEmpty();
    }

    @Override
    @NonNull
    public String toString() {
        return "AuthResult{" +
                "accessToken='[REDACTED]'" +
                ", refreshToken=" + (refreshToken != null ? "[REDACTED]" : "null") +
                ", user=" + user +
                ", expiresInSeconds=" + expiresInSeconds +
                '}';
    }
}