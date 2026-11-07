package org.mcmonkey.sentinel.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaCZTickWatchdogTest {

    @Test
    void leavesNormallyTickingShootersAlone() {
        TaCZTickWatchdog watchdog = new TaCZTickWatchdog();
        assertFalse(watchdog.shouldDriveFallback(10, true));
        assertFalse(watchdog.shouldDriveFallback(20, true));
        assertFalse(watchdog.shouldDriveFallback(30, true));
    }

    @Test
    void activatesOnlyAfterTwoStagnantTicks() {
        TaCZTickWatchdog watchdog = new TaCZTickWatchdog();
        assertFalse(watchdog.shouldDriveFallback(10, true));
        assertFalse(watchdog.shouldDriveFallback(10, true));
        assertTrue(watchdog.shouldDriveFallback(10, true));
        assertTrue(watchdog.shouldDriveFallback(10, true));
    }

    @Test
    void inactiveStateResetsTheDetector() {
        TaCZTickWatchdog watchdog = new TaCZTickWatchdog();
        watchdog.shouldDriveFallback(10, true);
        watchdog.shouldDriveFallback(10, true);
        assertFalse(watchdog.shouldDriveFallback(10, false));
        assertFalse(watchdog.shouldDriveFallback(10, true));
    }
}
