package org.mcmonkey.sentinel.integration;

/**
 * Detects whether TaCZ's native LivingEntity tick has stopped advancing.
 * Kept independent from Bukkit and TaCZ so the fallback decision is testable.
 */
final class TaCZTickWatchdog {

    private static final int STAGNANT_TICKS_BEFORE_FALLBACK = 2;

    private long lastMarker = Long.MIN_VALUE;
    private int stagnantTicks;

    boolean shouldDriveFallback(long nativeTickMarker, boolean transientStateActive) {
        if (!transientStateActive) {
            stagnantTicks = 0;
            lastMarker = nativeTickMarker;
            return false;
        }
        if (nativeTickMarker != lastMarker) {
            lastMarker = nativeTickMarker;
            stagnantTicks = 0;
            return false;
        }
        stagnantTicks++;
        return stagnantTicks >= STAGNANT_TICKS_BEFORE_FALLBACK;
    }

    void reset() {
        lastMarker = Long.MIN_VALUE;
        stagnantTicks = 0;
    }
}
