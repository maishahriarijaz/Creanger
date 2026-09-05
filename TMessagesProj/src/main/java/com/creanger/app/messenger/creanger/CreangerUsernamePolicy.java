package com.creanger.app.messenger.creanger;

/**
 * Instagram-style username policy for Creanger accounts. Pure JVM (no Android
 * imports) so it is unit-testable and shared by every screen that accepts a
 * username.
 *
 * Rules:
 *  - lowercase letters a-z, digits 0-9, underscore "_" and period "."
 *  - length 3..30
 *  - no spaces, no unsupported characters
 *  - must not start or end with a period; no two consecutive periods
 *  - normalization lower-cases input and strips unsupported characters so the
 *    same value is always sent to the availability check and to registration
 */
public final class CreangerUsernamePolicy {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 30;

    private CreangerUsernamePolicy() {}

    /** Validation outcome; {@link #code} matches the strings shown inline. */
    public static final class Result {
        public static final int OK = 0;
        public static final int EMPTY = 1;
        public static final int TOO_SHORT = 2;
        public static final int TOO_LONG = 3;
        public static final int INVALID_CHARACTERS = 4;
        public static final int BAD_PERIOD_PLACEMENT = 5;

        public final int code;

        Result(int code) {
            this.code = code;
        }

        public boolean ok() {
            return code == OK;
        }
    }

    /**
     * Lower-cases and removes characters outside [a-z0-9._]; spaces become
     * underscores. Idempotent: normalize(normalize(x)) == normalize(x).
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = Character.toLowerCase(raw.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /** Validates an already-normalized username. */
    public static Result validate(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return new Result(Result.EMPTY);
        }
        if (normalized.length() < MIN_LENGTH) {
            return new Result(Result.TOO_SHORT);
        }
        if (normalized.length() > MAX_LENGTH) {
            return new Result(Result.TOO_LONG);
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.';
            if (!allowed) {
                return new Result(Result.INVALID_CHARACTERS);
            }
        }
        if (normalized.startsWith(".") || normalized.endsWith(".") || normalized.contains("..")) {
            return new Result(Result.BAD_PERIOD_PLACEMENT);
        }
        return new Result(Result.OK);
    }

    /** Normalize + validate convenience used by UI before hitting the network. */
    public static Result validateNormalized(String raw) {
        return validate(normalize(raw));
    }
}
