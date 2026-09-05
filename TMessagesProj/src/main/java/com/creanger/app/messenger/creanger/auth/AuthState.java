package com.creanger.app.messenger.creanger.auth;

/** Lifecycle states surfaced by {@link CreangerAuthEngine}. */
public enum AuthState {
    /** No persisted session, nothing in progress. */
    UNINITIALIZED,
    /** An email->OTP or registration flow is in progress. */
    AUTHENTICATING,
    /** Valid access+refresh session is available in memory. */
    AUTHENTICATED,
    /** A token refresh is currently in-flight (single-flight). */
    REFRESHING,
    /** No usable session (logged out, expired, or failed refresh). */
    UNAUTHENTICATED
}