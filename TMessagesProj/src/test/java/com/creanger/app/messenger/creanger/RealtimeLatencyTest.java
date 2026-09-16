package com.creanger.app.messenger.creanger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.creanger.app.messenger.creanger.realtime.RealtimeLatency;

import org.junit.Test;

/**
 * Unit tests for the realtime latency meter behind the chat-screen badge:
 * ISO-8601 server timestamp parsing (Postgres timestamptz shapes), E2E
 * sample computation and clamping, heartbeat RTT, EMA smoothing and the
 * badge color buckets.
 */
public class RealtimeLatencyTest {

    // ---- timestamp parsing ----

    @Test
    public void parseUtcZTimestamp() {
        long ms = RealtimeLatency.parseEpochMillis("2026-09-16T12:00:00Z");
        long expected = RealtimeLatency.daysFromCivil(2026, 9, 16) * 86_400_000L
                + 12 * 3_600_000L;
        assertEquals(expected, ms);
    }

    @Test
    public void parseFractionalSecondsAndLowercaseZ() {
        long a = RealtimeLatency.parseEpochMillis("2026-09-16T12:00:00.123Z");
        long b = RealtimeLatency.parseEpochMillis("2026-09-16T12:00:00.123z");
        assertEquals(a, b);
        assertEquals(123, a % 1000);
    }

    @Test
    public void parseMicrosecondPrecisionTruncatesToMillis() {
        long ms = RealtimeLatency.parseEpochMillis("2026-09-16T12:00:00.123456+00:00");
        assertEquals(123, ms % 1000);
    }

    @Test
    public void parseOffsetTimestampConvertsToUtc() {
        // 12:00 at +02:00 == 10:00 UTC
        long offset = RealtimeLatency.parseEpochMillis("2026-09-16T12:00:00+02:00");
        long utc = RealtimeLatency.parseEpochMillis("2026-09-16T10:00:00Z");
        assertEquals(utc, offset);
    }

    @Test
    public void parseNegativeOffsetTimestamp() {
        // 12:00 at -03:30 == 15:30 UTC
        long offset = RealtimeLatency.parseEpochMillis("2026-09-16T12:00:00-03:30");
        long utc = RealtimeLatency.parseEpochMillis("2026-09-16T15:30:00Z");
        assertEquals(utc, offset);
    }

    @Test
    public void parseCompactOffsetAndSpaceSeparator() {
        long compact = RealtimeLatency.parseEpochMillis("2026-09-16T12:00:00+0200");
        long spaced = RealtimeLatency.parseEpochMillis("2026-09-16 12:00:00+02");
        long utc = RealtimeLatency.parseEpochMillis("2026-09-16T10:00:00Z");
        assertEquals(utc, compact);
        assertEquals(utc, spaced);
    }

    @Test
    public void parseInvalidTimestampsReturnMinusOne() {
        assertEquals(-1, RealtimeLatency.parseEpochMillis(null));
        assertEquals(-1, RealtimeLatency.parseEpochMillis(""));
        assertEquals(-1, RealtimeLatency.parseEpochMillis("not-a-timestamp"));
        assertEquals(-1, RealtimeLatency.parseEpochMillis("2026-09-16"));
        assertEquals(-1, RealtimeLatency.parseEpochMillis("2026-09-16T:00:00Z"));
    }

    @Test
    public void daysFromCivilKnownAnchors() {
        assertEquals(0L, RealtimeLatency.daysFromCivil(1970, 1, 1));
        assertEquals(1L, RealtimeLatency.daysFromCivil(1970, 1, 2));
        // 2000-03-01 was 11017 days after the epoch.
        assertEquals(11017L, RealtimeLatency.daysFromCivil(2000, 3, 1));
        // Leap day: 2024-02-29 = 19782 days after the epoch.
        assertEquals(19782L, RealtimeLatency.daysFromCivil(2024, 2, 29));
    }

    // ---- E2E series ----

    @Test
    public void e2eFirstSampleIsReturnedUnsmoothed() {
        RealtimeLatency latency = new RealtimeLatency();
        // Server created the row 300ms before the device received the frame:
        // nowMs (a real epoch instant) minus the parsed created_at.
        long nowMs = System.currentTimeMillis();
        long sample = latency.onRealtimeRow(isoAt(nowMs - 300), nowMs);
        assertEquals(300, sample);
        assertEquals(300, latency.getE2eMs());
        assertEquals(300, latency.getDisplayMs());
    }

    @Test
    public void e2eSmoothsOutliersWithEma() {
        RealtimeLatency latency = new RealtimeLatency();
        long base = System.currentTimeMillis();
        latency.onRealtimeRow(isoAt(base - 100), base); // 100ms
        // A 5-second spike (GC pause / batch burst) must not whip the value:
        // the row created 5s before receipt (sample = 5000).
        long spikeNow = base + 9_900;
        long after = latency.onRealtimeRow(isoAt(spikeNow - 5_000), spikeNow);
        // EMA: 0.25 * 5000 + 0.75 * 100 = 1325
        assertEquals(1325, after);
    }

    @Test
    public void e2eDiscardsNegativeSamples() {
        RealtimeLatency latency = new RealtimeLatency();
        // Row timestamp in the device's future (clock skew) → negative → dropped.
        long serverMs = RealtimeLatency.parseEpochMillis("2026-09-16T12:00:00Z");
        assertEquals(-1, latency.onRealtimeRow("2026-09-16T12:00:00Z", serverMs - 5_000));
        assertEquals(-1, latency.getE2eMs());
        assertEquals(-1, latency.getDisplayMs());
    }

    @Test
    public void e2eDiscardsBacklogSizedSamples() {
        RealtimeLatency latency = new RealtimeLatency();
        long serverMs = RealtimeLatency.parseEpochMillis("2026-09-16T12:00:00Z");
        // Two minutes after creation: a reconnection backlog, not latency.
        assertEquals(-1, latency.onRealtimeRow("2026-09-16T12:00:00Z", serverMs + 120_000));
        assertEquals(-1, latency.getE2eMs());
    }

    @Test
    public void e2eRowsWithoutTimestampAreSkipped() {
        RealtimeLatency latency = new RealtimeLatency();
        assertEquals(-1, latency.onRealtimeRow(null, 1_000L));
        assertEquals(-1, latency.onRealtimeRow("", 1_000L));
        assertEquals(-1, latency.onRealtimeRow("garbage", 1_000L));
        assertEquals(-1, latency.getE2eMs());
    }

    // ---- RTT series ----

    @Test
    public void rttFirstSampleSetsValue() {
        RealtimeLatency latency = new RealtimeLatency();
        assertEquals(45, latency.onRoundTrip(1_000_000L, 1_000_045L));
        assertEquals(45, latency.getRttMs());
        // Display prefers E2E once present, falls back to RTT.
        assertEquals(45, latency.getDisplayMs());
    }

    @Test
    public void rttDiscardsInvertedAndHugeSamples() {
        RealtimeLatency latency = new RealtimeLatency();
        assertEquals(-1, latency.onRoundTrip(2_000L, 1_000L)); // inverted clocks
        assertEquals(-1, latency.onRoundTrip(1_000L, 1_000L + 120_000)); // absurd
        assertEquals(-1, latency.onRoundTrip(0, 5_000L)); // no heartbeat in flight
        assertEquals(-1, latency.getRttMs());
    }

    @Test
    public void rttAndE2eSeriesAreIndependent() {
        RealtimeLatency latency = new RealtimeLatency();
        latency.onRoundTrip(1_000L, 1_050L);
        long serverMs = RealtimeLatency.parseEpochMillis("2026-09-16T12:00:00Z");
        long e2e = latency.onRealtimeRow("2026-09-16T12:00:00Z", serverMs + 400);
        assertEquals(400, e2e);
        assertEquals(50, latency.getRttMs());
        assertEquals(400, latency.getDisplayMs());
        assertEquals(serverMs + 400, latency.getLastE2eSampleAtMs());
        assertEquals(1_050, latency.getLastRttSampleAtMs());
    }

    @Test
    public void resetClearsBothSeries() {
        RealtimeLatency latency = new RealtimeLatency();
        latency.onRoundTrip(1_000L, 1_050L);
        long serverMs = RealtimeLatency.parseEpochMillis("2026-09-16T12:00:00Z");
        latency.onRealtimeRow("2026-09-16T12:00:00Z", serverMs + 400);
        latency.reset();
        assertEquals(-1, latency.getE2eMs());
        assertEquals(-1, latency.getRttMs());
        assertEquals(-1, latency.getDisplayMs());
        assertEquals(0, latency.getLastE2eSampleAtMs());
        assertEquals(0, latency.getLastRttSampleAtMs());
    }

    // ---- badge buckets ----

    @Test
    public void bucketsMatchQualityTiers() {
        assertEquals(RealtimeLatency.BUCKET_OFFLINE, RealtimeLatency.bucketOf(-1));
        assertEquals(RealtimeLatency.BUCKET_EXCELLENT, RealtimeLatency.bucketOf(120));
        assertEquals(RealtimeLatency.BUCKET_EXCELLENT, RealtimeLatency.bucketOf(499));
        assertEquals(RealtimeLatency.BUCKET_GOOD, RealtimeLatency.bucketOf(500));
        assertEquals(RealtimeLatency.BUCKET_GOOD, RealtimeLatency.bucketOf(1499));
        assertEquals(RealtimeLatency.BUCKET_SLOW, RealtimeLatency.bucketOf(1500));
        assertEquals(RealtimeLatency.BUCKET_SLOW, RealtimeLatency.bucketOf(3999));
        assertEquals(RealtimeLatency.BUCKET_VERY_SLOW, RealtimeLatency.bucketOf(4000));
        assertEquals(RealtimeLatency.BUCKET_VERY_SLOW, RealtimeLatency.bucketOf(59_000));
    }

    // ---- timestamp probe (transport RTT matching) ----

    @Test
    public void e2eEmaConvergesToConstantLatency() {
        RealtimeLatency latency = new RealtimeLatency();
        long base = System.currentTimeMillis();
        long value = -1;
        // A constant ~250ms delivery delay must converge near 250 within 12 samples:
        // each row was created 250ms before its frame arrived.
        for (int i = 0; i < 12; i++) {
            value = latency.onRealtimeRow(isoAt(base + i - 250), base + i);
        }
        assertTrue("EMA should converge near 250, got " + value, value >= 250 && value <= 260);
        assertTrue(latency.getDisplayMs() >= 0);
    }

    /** Formats an epoch instant as the ISO-8601 Z timestamp Postgres emits. */
    private static String isoAt(long epochMs) {
        long days = Math.floorDiv(epochMs, 86_400_000L);
        long millisOfDay = Math.floorMod(epochMs, 86_400_000L);
        long secondsOfDay = millisOfDay / 1000L;
        long millis = millisOfDay % 1000L;
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
        return String.format("%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
                year, m, d,
                secondsOfDay / 3600L, (secondsOfDay / 60L) % 60L, secondsOfDay % 60L, millis);
    }
}
