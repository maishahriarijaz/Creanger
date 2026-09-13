package com.creanger.app.messenger.creanger.auth;

import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes;

/**
 * Maps Google Sign-In failures to UI outcomes. Pure JVM so it is unit
 * testable; the activity turns the result into a user-visible message.
 */
public final class GoogleSignInErrorMapper {

    public enum Outcome {
        /** User dismissed the account picker; stay silent. */
        CANCELLED,
        /** No network; tell the user to check the connection. */
        NETWORK_ERROR,
        /** DEVELOPER_ERROR etc.; the build is misconfigured. */
        CONFIG_ERROR,
        /** Anything else; generic retry message. */
        SIGN_IN_FAILED
    }

    private GoogleSignInErrorMapper() {}

    public static Outcome mapStatusCode(int statusCode) {
        if (statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
            return Outcome.CANCELLED;
        }
        if (statusCode == GoogleSignInStatusCodes.NETWORK_ERROR) {
            return Outcome.NETWORK_ERROR;
        }
        if (statusCode == GoogleSignInStatusCodes.DEVELOPER_ERROR
                || statusCode == GoogleSignInStatusCodes.INVALID_ACCOUNT
                || statusCode == GoogleSignInStatusCodes.SIGN_IN_FAILED) {
            return Outcome.CONFIG_ERROR;
        }
        return Outcome.SIGN_IN_FAILED;
    }
}
