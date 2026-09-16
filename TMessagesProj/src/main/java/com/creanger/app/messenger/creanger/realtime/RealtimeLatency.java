package com.creanger.app.messenger.creanger.realtime;

import androidx.annotation.Nullable;

/**
 * Realtime latency measurement for the open chat — the numbers behind the
 * chat-screen latency badge.
 *
 * Two independent samples are tracked:
 *
 *  - <b>E2E delivery latency</b>: how long ago the server says a realtime row
 *    was created versus when the frame actually rendered on this device —
 *    {@code now - created_at}. This is the number users perceive ("how long
 *    did the other side's message take to reach me") and is only meaningful
 *    when both clocks are UTC epoch (they are: the backend stores
 *    {@code timestamptz} and the device computes {@code System.currentTimeMillis()}).
 *    Clock skew between the device and the server shifts this sample; the
 *    smoothed median effect is constant and the badge is a live indicator,
 *    not an SLA audit.
 *
 *  - <b>RTT</b>: a true round-trip that needs no clock agreement — the Phoenix
 *    heartbeat is sent every {@link SocketMessageRealtimeTransport#HEARTBEAT_INTERVAL_MS}
 *    and the transport answers with the server's reply time; the reply carries
 *    the client ref, so no clock comparison is involved.
 *
 * Both series are smoothed with an exponential moving average (α = 1/4, the
 * same weight the official client gives the freshest sample) so a single
 * outlier (GC pause, frame batch burst) never whipsaws the badge. Samples
 * are clamped: negative values (clock skew / replayed rows) and absurd ones
 * (&gt; 60 s — a reconnection backlog, not latency) are discarded.
 *
 * Pure JVM and Android-free per the creanger conventions (no Looper, no
 * logging): the caller supplies wall-clock time and receives samples through
 * the {@link Sink} callback.
 */
public final class RealtimeLatency {

    /** One smoothing weight for both series: the freshest sample counts 25%. */
    static final double EMA_ALPHA = 0.25;

    /** Samples outside this window are discarded (skew negative / backlog). */
    static final long MIN_SAMPLE_MS = 0L;
    static final long MAX_SAMPLE_MS = 60_000L;

    /**
     * Receives computed samples (delivered on the realtime poster thread —
     * the UI thread on device). Implementations must be cheap: the badge
     * update is a text/color swap.
     */
    public interface Sink {
        /**
         * @param chatId the subscribed chat
         * @param kind   which series produced the sample
         * @param ms     the smoothed latency in milliseconds (&ge; 0)
         */
        void onLatencySample(String chatId, Kind kind, long ms);
    }

    /** Which latency series a sample belongs to. */
    public enum Kind {
        /** End-to-end delivery: server row timestamp → frame rendered. */
        E2E,
        /** Phoenix heartbeat round trip (socket-level, no clock agreement). */
        RTT
    }

    private volatile double e2eEmaMs = -1.0;
    private volatile double rttEmaMs = -1.0;
    private volatile long lastE2eSampleAtMs;
    private volatile long lastRttSampleAtMs;

    /**
     * Feeds an INSERT/EDIT/STATUS realtime row. Computes
     * {@code nowMs - serverCreatedAtMs}, clamps it, folds it into the E2E EMA
     * and returns the smoothed sample, or {@code -1} when the row carried no
     * usable timestamp or the sample was discarded.
     */
    public long onRealtimeRow(@Nullable String serverCreatedAtMs, long nowMs) {
        long serverMs = parseEpochMillis(serverCreatedAtMs);
        if (serverMs <= 0) {
            return -1;
        }
        long sample = nowMs - serverMs;
        if (sample < MIN_SAMPLE_MS || sample > MAX_SAMPLE_MS) {
            return -1;
        }
        e2eEmaMs = e2eEmaMs < 0 ? sample : (EMA_ALPHA * sample) + ((1.0 - EMA_ALPHA) * e2eEmaMs);
        lastE2eSampleAtMs = nowMs;
        return Math.round(e2eEmaMs);
    }

    /**
     * Feeds a socket round trip (Phoenix heartbeat sent at {@code sentAtMs},
     * server reply observed at {@code nowMs}). Returns the smoothed RTT, or
     * {@code -1} when the sample was discarded.
     */
    public long onRoundTrip(long sentAtMs, long nowMs) {
        if (sentAtMs <= 0 || nowMs < sentAtMs) {
            return -1;
        }
        long sample = nowMs - sentAtMs;
        if (sample > MAX_SAMPLE_MS) {
            return -1;
        }
        rttEmaMs = rttEmaMs < 0 ? sample : (EMA_ALPHA * sample) + ((1.0 - EMA_ALPHA) * rttEmaMs);
        lastRttSampleAtMs = nowMs;
        return Math.round(rttEmaMs);
    }

    /** Smoothed E2E delivery latency in ms, or {@code -1} before any sample. */
    public long getE2eMs() {
        double v = e2eEmaMs;
        return v < 0 ? -1 : Math.round(v);
    }

    /** Smoothed heartbeat RTT in ms, or {@code -1} before any sample. */
    public long getRttMs() {
        double v = rttEmaMs;
        return v < 0 ? -1 : Math.round(v);
    }

    /** The preferred badge number: E2E when known, else RTT, else -1. */
    public long getDisplayMs() {
        long e2e = getE2eMs();
        return e2e >= 0 ? e2e : getRttMs();
    }

    /** Epoch millis of the newest accepted E2E sample (0 before any). */
    public long getLastE2eSampleAtMs() {
        return lastE2eSampleAtMs;
    }

    /** Epoch millis of the newest accepted RTT sample (0 before any). */
    public long getLastRttSampleAtMs() {
        return lastRttSampleAtMs;
    }

    /** Clears both series (logout / chat switch). */
    public void reset() {
        e2eEmaMs = -1.0;
        rttEmaMs = -1.0;
        lastE2eSampleAtMs = 0;
        lastRttSampleAtMs = 0;
    }

    /**
     * Badge color bucket for a latency in ms:
     * {@code < 500} excellent, {@code < 1500} good, {@code < 4000} slow,
     * otherwise very slow. Aligned with typical websocket chat budgets.
     */
    public static int bucketOf(long ms) {
        if (ms < 0) {
            return BUCKET_OFFLINE;
        }
        if (ms < 500) {
            return BUCKET_EXCELLENT;
        }
        if (ms < 1500) {
            return BUCKET_GOOD;
        }
        if (ms < 4000) {
            return BUCKET_SLOW;
        }
        return BUCKET_VERY_SLOW;
    }

    public static final int BUCKET_OFFLINE = -1;
    public static final int BUCKET_EXCELLENT = 0;
    public static final int BUCKET_GOOD = 1;
    public static final int BUCKET_SLOW = 2;
    public static final int BUCKET_VERY_SLOW = 3;

    /**
     * Parses a server timestamp into UTC epoch millis. The backend delivers
     * ISO-8601 {@code timestamptz} strings ({@code 2026-09-16T12:34:56.789+00:00}
     * or {@code ...Z}) over both REST and Realtime; this tolerates the
     * fractional-second precision variants Postgres emits without pulling
     * {@code java.time} (min SDK 21 / Android-free core per the conventions).
     */
    public static long parseEpochMillis(@Nullable String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isEmpty()) {
            return -1;
        }
        try {
            return parseIsoMillis(isoTimestamp.trim());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static long parseIsoMillis(String value) {
        // Split date and time; the date part is always calendar-formatted.
        int tIndex = value.indexOf('T');
        if (tIndex < 0) {
            tIndex = value.indexOf(' ');
        }
        if (tIndex <= 0) {
            return -1;
        }
        String datePart = value.substring(0, tIndex);
        String timePart = value.substring(tIndex + 1);

        // Extract the zone offset from the tail: Z, +hh:mm, -hh:mm, +hhmm, +hh.
        int zoneMillis = 0;
        int zoneIndex = -1;
        for (int i = timePart.length() - 1; i >= 0; i--) {
            char c = timePart.charAt(i);
            if (c == 'Z' || c == 'z') {
                zoneIndex = i;
                break;
            }
            if (c == '+' || c == '-') {
                if (i == 0) {
                    return -1; // negative date — unsupported shape
                }
                String offset = timePart.substring(i + 1);
                zoneIndex = i;
                long sign = c == '-' ? -1 : 1;
                // Compact ISO offset (+HHMM / -HHMM) → colon form for one parser.
                if (offset.length() == 4 && offset.indexOf(':') < 0) {
                    offset = offset.substring(0, 2) + ":" + offset.substring(2);
                }
                String[] parts = offset.split(":");
                try {
                    long hours = 0;
                    long minutes = 0;
                    if (parts.length >= 1 && !parts[0].isEmpty()) {
                        hours = Long.parseLong(parts[0]);
                    }
                    if (parts.length >= 2 && !parts[1].isEmpty()) {
                        minutes = Long.parseLong(parts[1]);
                    }
                    zoneMillis = (int) (sign * (hours * 3600L + minutes * 60L) * 1000L);
                } catch (NumberFormatException e) {
                    return -1;
                }
                break;
            }
        }
        if (zoneIndex >= 0) {
            timePart = timePart.substring(0, zoneIndex);
        }

        // Strip fractional seconds (".123", ".123456") — millis only.
        int fractionMillis = 0;
        int dotIndex = timePart.indexOf('.');
        if (dotIndex >= 0) {
            String fraction = timePart.substring(dotIndex + 1);
            timePart = timePart.substring(0, dotIndex);
            if (fraction.length() > 3) {
                fraction = fraction.substring(0, 3);
            }
            while (fraction.length() < 3) {
                fraction += "0";
            }
            try {
                fractionMillis = Integer.parseInt(fraction);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        String[] hms = timePart.split(":");
        if (hms.length < 2 || hms.length > 3) {
            return -1;
        }
        try {
            int hours = Integer.parseInt(hms[0]);
            int minutes = Integer.parseInt(hms[1]);
            int seconds = hms.length == 3 ? Integer.parseInt(hms[2]) : 0;

            String[] ymd = datePart.split("-");
            if (ymd.length != 3) {
                return -1;
            }
            int year = Integer.parseInt(ymd[0]);
            int month = Integer.parseInt(ymd[1]);
            int day = Integer.parseInt(ymd[2]);
            long utcMillis = daysFromCivil(year, month, day) * 86_400_000L
                    + (hours * 3600L + minutes * 60L + seconds) * 1000L
                    + fractionMillis;
            return utcMillis - zoneMillis;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Days since 1970-01-01 for a proleptic-Gregorian calendar date (Howard
     * Hinnant's {@code days_from_civil} algorithm) — no {@code java.util.Calendar},
     * no timezone objects, Android-free. Public so the epoch anchors are
     * unit-testable from the creanger test package.
     */
    public static long daysFromCivil(int year, int month, int day) {
        year -= month <= 2 ? 1 : 0;
        long era = (year >= 0 ? year : year - 399) / 400L;
        long yearOfEra = year - era * 400L;
        long dayOfYear = (153L * (month + (month > 2 ? -3 : 9)) + 2L) / 5L + day - 1L;
        long dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear;
        return era * 146_097L + dayOfEra - 719_468L;
    }
}
