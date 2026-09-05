package com.creanger.app.messenger.creanger;

import android.content.Context;
import android.content.SharedPreferences;

import com.creanger.app.messenger.BuildConfig;

import androidx.annotation.Nullable;

/**
 * Central configuration for the Creanger Android Auth Foundation.
 *
 * Safety rails:
 *  - {@link #isEnabled()} gates ALL creanger auth behaviour on the
 *    {@code USE_CREANGER_AUTH} BuildConfig flag (default OFF). When disabled,
 *    none of the auth client code runs.
 *  - {@link #isValidBaseUrl(String)} rejects anything but HTTPS: to fail fast
 *    before any token, OTP or email can be transmitted in the clear.
 *  - A debug-only override allows pointing the client at a local/test server;
 *    the value is stored in SharedPreferences (never committed to the repo).
 *    In release builds the override is ignored entirely.
 */
public class CreangerAuthConfig {

    static final String PREFS_NAME = "creanger_auth";
    static final String KEY_BASE_URL = "base_url_override";
    static final String KEY_CHAT_BASE_URL = "chat_base_url_override";

    @Nullable
    private static volatile CreangerAuthConfig INSTANCE;

    private final Context context;

    private CreangerAuthConfig(Context context) {
        this.context = context.getApplicationContext();
    }

    public static CreangerAuthConfig getInstance(Context context) {
        CreangerAuthConfig local = INSTANCE;
        if (local == null) {
            synchronized (CreangerAuthConfig.class) {
                local = INSTANCE;
                if (local == null) {
                    INSTANCE = local = new CreangerAuthConfig(context);
                }
            }
        }
        return local;
    }

    /**
     * Master kill-switch. Defaults to the BuildConfig value (false) but may be
     * flipped at runtime to false only — safety: it can never be turned ON by
     * accident at runtime, only OFF for testing shells.
     */
    public boolean isEnabled() {
        return BuildConfig.USE_CREANGER_AUTH;
    }

    /** Timeout for establishing the TLS connection. */
    public int getConnectTimeoutMillis() {
        return 15_000;
    }

    /** Timeout waiting for response data. */
    public int getReadTimeoutMillis() {
        return 30_000;
    }

    /** Full request timeout for auth-critical calls (refresh, verify). */
    public int getRequestTimeoutMillis() {
        return 30_000;
    }

    /**
     * Effective auth base URL = the Supabase PROJECT URL (GoTrue lives under
     * {@code /auth/v1}, PostgREST under {@code /rest/v1}). HTTPS only. In
     * DEBUG, an override stored in prefs (set via
     * {@link #setBaseUrlOverride(String)}) is used if present; otherwise the
     * compile-time {@link BuildConfig#CREANGER_AUTH_BASE_URL} is used. In
     * release builds the override is ignored.
     *
     * Debug builds additionally accept plain-HTTP loopback URLs
     * (localhost/127.0.0.1/10.0.2.2) so the client can be pointed at a local
     * Supabase stack during real E2E testing. Release builds are HTTPS-only,
     * so credentials can never be sent in the clear on a release APK.
     */
    public String getBaseUrl() {
        if (BuildConfig.DEBUG_VERSION) {
            String override = getPreferences().getString(KEY_BASE_URL, null);
            if (override != null && isValidBaseUrl(override)) {
                return stripTrailingSlash(override);
            }
        }
        String compiled = BuildConfig.CREANGER_AUTH_BASE_URL;
        if (compiled == null || compiled.isEmpty()) {
            return "";
        }
        return stripTrailingSlash(compiled);
    }

    public boolean hasBaseUrl() {
        return isValidBaseUrl(getBaseUrl());
    }

    /**
     * Supabase anon (public) key. Sent as the {@code apikey} header on every
     * request to the project URL; required by GoTrue and PostgREST. Never a
     * secret by design — RLS is the security boundary.
     */
    @Nullable
    public String getSupabaseAnonKey() {
        return BuildConfig.CREANGER_SUPABASE_ANON_KEY;
    }

    /**
     * Effective PostgREST/Supabase chat data-plane base URL. Defaults to the
     * auth server base URL when no chat base URL is configured (dev drop-in),
     * otherwise uses the compile-time {@link BuildConfig#CREANGER_CHAT_BASE_URL}.
     */
    public String getChatBaseUrl() {
        if (BuildConfig.DEBUG_VERSION) {
            String override = getPreferences().getString(KEY_CHAT_BASE_URL, null);
            if (override != null && isValidBaseUrl(override)) {
                return stripTrailingSlash(override);
            }
        }
        String compiled = BuildConfig.CREANGER_CHAT_BASE_URL;
        if (compiled == null || compiled.isEmpty()) {
            return getBaseUrl();
        }
        return stripTrailingSlash(compiled);
    }

    public boolean hasChatBaseUrl() {
        return isValidBaseUrl(getChatBaseUrl());
    }

    /**
     * Debug/test-only override for the chat data plane (HTTPS). No effect in
     * release builds.
     */
    public void setChatBaseUrlOverride(@Nullable String url) {
        if (!BuildConfig.DEBUG_VERSION) {
            return;
        }
        if (url == null || url.isEmpty()) {
            getPreferences().edit().remove(KEY_CHAT_BASE_URL).apply();
        } else if (isValidBaseUrl(url)) {
            getPreferences().edit().putString(KEY_CHAT_BASE_URL, stripTrailingSlash(url)).apply();
        }
    }

    /**
     * Debug/test-only override (HTTPS). No effect in release builds.
     */
    public void setBaseUrlOverride(@Nullable String url) {
        if (!BuildConfig.DEBUG_VERSION) {
            return;
        }
        if (url == null || url.isEmpty()) {
            getPreferences().edit().remove(KEY_BASE_URL).apply();
        } else if (isValidBaseUrl(url)) {
            getPreferences().edit().putString(KEY_BASE_URL, stripTrailingSlash(url)).apply();
        }
    }

    public void clearBaseUrlOverride() {
        if (BuildConfig.DEBUG_VERSION) {
            getPreferences().edit().remove(KEY_BASE_URL).apply();
        }
    }

    /**
     * Base URL policy:
     *  - Always: HTTPS (fail fast before any token/OTP/email can go in clear).
     *  - Debug builds only: plain-HTTP loopback (localhost/127.0.0.1/10.0.2.2)
     *    so real E2E against a local dev Auth Server is possible. Never in
     *    release, where {@code http://} is rejected outright.
     */
    public static boolean isValidBaseUrl(@Nullable String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        if (url.startsWith("https://")) {
            return true;
        }
        if (BuildConfig.DEBUG_VERSION && isLoopbackHttp(url)) {
            return true;
        }
        return false;
    }

    private static boolean isLoopbackHttp(String url) {
        return url.startsWith("http://localhost")
                || url.startsWith("http://127.0.0.1")
                || url.startsWith("http://10.0.2.2")
                || url.startsWith("http://[::1]");
    }

    @Nullable
    public String getUserAgent() {
        return buildUserAgent(context);
    }

    public static String buildUserAgent(Context context) {
        try {
            String label = BuildConfig.BUILD_VERSION_STRING;
            if (label == null || label.isEmpty()) {
                label = String.valueOf(BuildConfig.VERSION_NUM);
            }
            return "Creanger-" + label + " (Android; " + context.getPackageName() + ")";
        } catch (Throwable t) {
            return "Creanger (Android)";
        }
    }

    private SharedPreferences getPreferences() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String stripTrailingSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}