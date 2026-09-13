package com.creanger.app.messenger.creanger.api;

/**
 * Minimal configuration surface required by {@link HttpsUrlConnectionTransport}.
 * Implemented by {@code CreangerAuthConfig}; stubbed directly in unit tests so
 * the real transport can be exercised against a local HTTP server without
 * Android dependencies.
 */
public interface TransportConfig {

    int getConnectTimeoutMillis();

    int getReadTimeoutMillis();

    String getBaseUrl();

    String getSupabaseAnonKey();

    String getUserAgent();
}
