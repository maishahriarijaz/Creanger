package com.creanger.app.messenger.creanger.model;

import androidx.annotation.Nullable;

public final class AuthModels {

    private AuthModels() {
    }

    public static final class CreangerUser {
        public final String id;
        @Nullable
        public final String username;
        @Nullable
        public final String email;
        public final boolean emailVerified;
        @Nullable
        public final String displayName;
        @Nullable
        public final String avatarUrl;
        @Nullable
        public final String createdAt;

        public CreangerUser(String id, @Nullable String username, @Nullable String email, boolean emailVerified,
                            @Nullable String displayName, @Nullable String avatarUrl, @Nullable String createdAt) {
            this.id = id;
            this.username = username;
            this.email = email;
            this.emailVerified = emailVerified;
            this.displayName = displayName;
            this.avatarUrl = avatarUrl;
            this.createdAt = createdAt;
        }
    }

    public static final class AuthSession {
        public final String accessToken;
        public final String refreshToken;
        @Nullable
        public final CreangerUser user;
        public final long refreshedAtEpochMs;

        public AuthSession(String accessToken, String refreshToken, @Nullable CreangerUser user, long refreshedAtEpochMs) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.user = user;
            this.refreshedAtEpochMs = refreshedAtEpochMs;
        }

        public AuthSession copyWithAccessToken(String newAccessToken) {
            return new AuthSession(newAccessToken, refreshToken, user, refreshedAtEpochMs);
        }

        public AuthSession copyWithUser(@Nullable CreangerUser newUser) {
            return new AuthSession(accessToken, refreshToken, newUser, refreshedAtEpochMs);
        }
    }

    /**
     * Result of Supabase signup: a full session when auto-confirm is enabled,
     * or null when an email confirmation OTP must be verified first.
     */
    public static final class SignupResult {
        @Nullable
        public final AuthSession session;

        public SignupResult(@Nullable AuthSession session) {
            this.session = session;
        }

        public boolean isEmailConfirmationRequired() {
            return session == null;
        }
    }

    /** Fingerprint object used by every auth verify request. */
    public static final class Fingerprint {
        public final String platform;
        public final String locale;
        public final String timezone;

        public Fingerprint(String platform, String locale, String timezone) {
            this.platform = platform;
            this.locale = locale;
            this.timezone = timezone;
        }
    }

    /** device object attached to verify requests. */
    public static final class DeviceInfo {
        public final String platform; // "android"
        @Nullable
        public final String deviceName;
        public final Fingerprint fingerprint;

        public DeviceInfo(String platform, @Nullable String deviceName, Fingerprint fingerprint) {
            this.platform = platform;
            this.deviceName = deviceName;
            this.fingerprint = fingerprint;
        }
    }

    public static final class PushTokenInfo {
        public final String token;
        public final String provider; // "fcm"

        public PushTokenInfo(String token, String provider) {
            this.token = token;
            this.provider = provider;
        }
    }

    /** Result of POST /auth/v1/recover (forgot password) — tells client to await OTP. */
    public static final class ForgotPasswordResult {
        public final boolean sent;
        public final long resendAfterSeconds;

        public ForgotPasswordResult(boolean sent, long resendAfterSeconds) {
            this.sent = sent;
            this.resendAfterSeconds = resendAfterSeconds;
        }
    }

    /**
     * Result of Google Sign-In against Supabase Auth. The session is ALWAYS
     * adopted (GoTrue auto-provisions new accounts); {@code needsRegistration}
     * means the profiles row has no username yet and the account setup screen
     * must be shown. {@code registrationToken} is a legacy custom-auth concept
     * and is always null under Supabase Auth.
     */
    public static final class GoogleAuthResult {
        public final boolean needsRegistration;
        @Nullable
        public final AuthSession session;
        @Nullable
        public final String registrationToken;

        public GoogleAuthResult(boolean needsRegistration,
                                @Nullable AuthSession session,
                                @Nullable String registrationToken) {
            this.needsRegistration = needsRegistration;
            this.session = session;
            this.registrationToken = registrationToken;
        }
    }
}