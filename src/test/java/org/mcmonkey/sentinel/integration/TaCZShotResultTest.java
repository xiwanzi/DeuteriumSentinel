package org.mcmonkey.sentinel.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaCZShotResultTest {

    @Test
    void mapsActionableResultsToExactlyOneFollowUp() {
        assertEquals(TaCZShotResult.FollowUp.DRAW,
                TaCZShotResult.NOT_DRAW.followUp());
        assertEquals(TaCZShotResult.FollowUp.BOLT,
                TaCZShotResult.NEED_BOLT.followUp());
        assertEquals(TaCZShotResult.FollowUp.RELOAD,
                TaCZShotResult.NO_AMMO.followUp());
        assertEquals(TaCZShotResult.FollowUp.STOP,
                TaCZShotResult.NOT_GUN.followUp());
        assertEquals(TaCZShotResult.FollowUp.STOP,
                TaCZShotResult.ID_NOT_EXIST.followUp());
    }

    @Test
    void passiveStatesLetTaczOwnTheirCooldown() {
        assertEquals(TaCZShotResult.FollowUp.NONE,
                TaCZShotResult.SUCCESS.followUp());
        assertEquals(TaCZShotResult.FollowUp.NONE,
                TaCZShotResult.COOL_DOWN.followUp());
        assertEquals(TaCZShotResult.FollowUp.NONE,
                TaCZShotResult.IS_RELOADING.followUp());
        assertEquals(TaCZShotResult.FollowUp.NONE,
                TaCZShotResult.IS_DRAWING.followUp());
        assertEquals(TaCZShotResult.FollowUp.NONE,
                TaCZShotResult.IS_BOLTING.followUp());
        assertEquals(TaCZShotResult.FollowUp.NONE,
                TaCZShotResult.OVERHEATED.followUp());
    }

    @Test
    void unknownFutureResultFailsClosedWithBackoff() {
        assertEquals(TaCZShotResult.UNKNOWN,
                TaCZShotResult.fromRuntimeName("A_FUTURE_TACZ_RESULT"));
        assertEquals(TaCZShotResult.FollowUp.BACK_OFF,
                TaCZShotResult.fromRuntimeName("A_FUTURE_TACZ_RESULT").followUp());
    }
}
