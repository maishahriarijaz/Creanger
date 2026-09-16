package com.creanger.app.messenger.creanger;

import androidx.annotation.Nullable;

/**
 * Presence helpers for the Creanger chat header: resolves the header subtitle
 * ("online", "last seen …", "typing…") from {@code user_presence} rows and
 * parses the ephemeral Phoenix presence diff payloads that arrive on the
 * realtime channel.
 *
 * The {@code user_presence} table (migration 008) carries
 * {@code presence_status} enum values exactly — online / away / offline /
 * invisible — and is in the realtime publication, so peer transitions arrive
 * live as presence diffs; the {@code getPresence} REST read (migration 036
 * companion) is the cold-start/reconnect fallback.
 *
 * Pure JVM (no Android imports, no {@code java.time}) per the creanger
 * conventions; no logging.
 */
public final class CreangerPresence {

    public static final String ONLINE = "online";
    public static final String AWAY = "away";
    public static final String OFFLINE = "offline";
    public static final String INVISIBLE = "invisible";

    private CreangerPresence() {
    }

    /**
     * Resolves the header subtitle for the peer.
     *
     * @param typing        true when the peer is currently typing (typing
     *                      wins over presence, matching Telegram behavior)
     * @param presenceStatus the peer's presence_status, or null when unknown
     * @param lastSeenAtMs   the peer's last_seen_at as UTC epoch millis, or
     *                       &le; 0 when unknown
     * @param nowMs          current wall-clock (UTC epoch millis)
     */
    @Nullable
    public static String subtitleFor(boolean typing, @Nullable String presenceStatus,
                                     long lastSeenAtMs, long nowMs) {
        if (typing) {
            return "typing...";
        }
        if (presenceStatus == null) {
            return null;
        }
        switch (presenceStatus) {
            case ONLINE:
                return "online";
            case AWAY:
                return lastSeenAtMs > 0 ? formatLastSeen(lastSeenAtMs, nowMs) : "away";
            case OFFLINE:
            case INVISIBLE:
                return lastSeenAtMs > 0 ? formatLastSeen(lastSeenAtMs, nowMs) : null;
            default:
                return null;
        }
    }

    /**
     * Formats a last-seen instant the way Telegram does: "last seen just now"
     * (&lt; 60s), "last seen X minutes ago" (&lt; 1h), "last seen at HH:mm"
     * (today), "last seen yesterday", otherwise "last seen DD/MM/YYYY".
     */
    public static String formatLastSeen(long lastSeenAtMs, long nowMs) {
        long diff = nowMs - lastSeenAtMs;
        if (diff < 0) {
            diff = 0; // clock skew: treat as just now
        }
        if (diff < 60_000L) {
            return "last seen just now";
        }
        if (diff < 3_600_000L) {
            long minutes = diff / 60_000L;
            return minutes == 1 ? "last seen 1 minute ago" : "last seen " + minutes + " minutes ago";
        }
        long days = floorDiv(diff, 86_400_000L);
        boolean today = floorDiv(nowMs, 86_400_000L) == floorDiv(lastSeenAtMs, 86_400_000L);
        boolean yesterday = floorDiv(nowMs - 86_400_000L, 86_400_000L) == floorDiv(lastSeenAtMs, 86_400_000L);
        if (today) {
            return "last seen at " + formatClock(lastSeenAtMs);
        }
        if (yesterday) {
            return "last seen yesterday at " + formatClock(lastSeenAtMs);
        }
        if (days < 7) {
            return "last seen " + days + (days == 1 ? " day ago" : " days ago");
        }
        return "last seen " + formatDate(lastSeenAtMs);
    }

    /** Local-wall-clock HH:mm (no java.time per min-SDK conventions). */
    static String formatClock(long epochMs) {
        long localMs = epochMs + localZoneOffsetMs(epochMs);
        long secondsOfDay = floorMod(localMs, 86_400_000L) / 1000L;
        return pad2(secondsOfDay / 3600L) + ":" + pad2((secondsOfDay / 60L) % 60L);
    }

    /** Local-wall-clock DD/MM/YYYY. */
    static String formatDate(long epochMs) {
        long localMs = epochMs + localZoneOffsetMs(epochMs);
        long days = floorDiv(localMs, 86_400_000L);
        // Civil-from-days (Howard Hinnant), mirrors RealtimeLatency.
        long z = days + 719_468L;
        long era = Math.floorDiv(z, 146_097L);
        long doe = z - era * 146_097L;
        long yoe = (doe - doe / 1460L + doe / 36524L - doe / 146_096L) / 365L;
        long y = yoe + era * 400L;
        long doy = doe - (365L * yoe + yoe / 4L - yoe / 100L);
        long mp = (5L * doy + 2L) / 153L;
        long d = doy - (153L * mp + 2L) / 5L + 1L;
        long m = mp < 10L ? mp + 3L : mp - 9L;
        long year = y + (m <= 2L ? 1L : 0L);
        return pad2(d) + "/" + pad2(m) + "/" + year;
    }

    /** True when the presence row is fresh enough to trust as a live state. */
    public static boolean isRowFresh(@Nullable String status, long lastSeenAtMs, long nowMs) {
        if (status == null) {
            return false;
        }
        if (ONLINE.equals(status)) {
            // Online rows expire visually after 5 minutes without a heartbeat.
            return lastSeenAtMs > 0 && nowMs - lastSeenAtMs < 300_000L;
        }
        return true;
    }

    /**
     * Parses one Phoenix presence diff payload
     * {@code {"joins":{"<uuid>":{...presence meta...}},"leaves":{...}}}.
     * Returns the user id for the first join, the first leave, or null.
     */
    @Nullable
    public static String[] parsePresenceDiff(@Nullable String payloadJson) {
        if (payloadJson == null || payloadJson.isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject payload = new org.json.JSONObject(payloadJson);
            org.json.JSONObject joins = payload.optJSONObject("joins");
            if (joins != null && joins.length() > 0) {
                return new String[]{joins.keys().next(), "join"};
            }
            org.json.JSONObject leaves = payload.optJSONObject("leaves");
            if (leaves != null && leaves.length() > 0) {
                return new String[]{leaves.keys().next(), "leave"};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static long localZoneOffsetMs(long epochMs) {
        // TimeZone is Android-available and JVM-available; keeping only this
        // call platform-neutral avoids java.time (API < 26).
        return java.util.TimeZone.getDefault().getOffset(epochMs);
    }

    private static long floorDiv(long a, long b) {
        return Math.floorDiv(a, b);
    }

    private static long floorMod(long a, long b) {
        return Math.floorMod(a, b);
    }

    private static String pad2(long v) {
        return v < 10 ? "0" + v : Long.toString(v);
    }
}
