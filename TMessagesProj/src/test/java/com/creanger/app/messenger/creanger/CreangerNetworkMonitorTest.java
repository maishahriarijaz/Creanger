package com.creanger.app.messenger.creanger;

import org.junit.Test;

import com.creanger.app.messenger.creanger.realtime.CreangerNetworkMonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure decision-core tests for the Creanger connectivity watcher. The Android
 * shell (callback registration) is device-only; these tests pin the rules the
 * realtime layer depends on: the usable-network predicate, the short-drop
 * fast-retry ladder, and the no-framework degrade path.
 */
public class CreangerNetworkMonitorTest {

    @Test
    public void networkWithoutInternetCapabilityIsNotUsable() {
        assertFalse(CreangerNetworkMonitor.isUsable(false, true, 34));
    }

    @Test
    public void unvalidatedNetworkIsRejectedOnModernAndroid() {
        // Captive portals advertise NET_CAPABILITY_INTERNET but never validate.
        assertFalse(CreangerNetworkMonitor.isUsable(true, false, 34));
        assertTrue(CreangerNetworkMonitor.isUsable(true, true, 34));
    }

    @Test
    public void preMNetworksSkipValidationRequirement() {
        // API 21–22 has no NET_CAPABILITY_VALIDATED — the internet flag alone is enough.
        assertTrue(CreangerNetworkMonitor.isUsable(true, false, 22));
    }

    @Test
    public void shortConnectionDropsRetryInAboutASecond() {
        long delay = CreangerNetworkMonitor.initialBackoffForMs(1_000L);
        assertTrue("short drop should retry fast, got " + delay, delay <= 1_100L);
    }

    @Test
    public void mediumDropsGetSmallCourtesyDelay() {
        assertEquals(2_000L, CreangerNetworkMonitor.initialBackoffForMs(8_000L));
    }

    @Test
    public void longLivedConnectionsGetModerateDelay() {
        assertEquals(5_000L, CreangerNetworkMonitor.initialBackoffForMs(60_000L));
    }

    @Test
    public void sharedMonitorIsNullWithoutAndroidFramework() {
        // JVM tests have no ApplicationLoader context; shared() must degrade to
        // null instead of throwing — callers treat null as "no monitor".
        assertNull(com.creanger.app.messenger.creanger.realtime.CreangerNetworkMonitor.shared());
    }
}
