package org.mcmonkey.sentinel.integration;

/** TaCZ shoot results reduced to the follow-up Sentinel needs to perform. */
enum TaCZShotResult {
    SUCCESS(FollowUp.NONE),
    UNKNOWN_FAIL(FollowUp.BACK_OFF),
    COOL_DOWN(FollowUp.NONE),
    NO_AMMO(FollowUp.RELOAD),
    NOT_DRAW(FollowUp.DRAW),
    NOT_GUN(FollowUp.STOP),
    ID_NOT_EXIST(FollowUp.STOP),
    NEED_BOLT(FollowUp.BOLT),
    IS_RELOADING(FollowUp.NONE),
    IS_DRAWING(FollowUp.NONE),
    IS_BOLTING(FollowUp.NONE),
    IS_MELEE(FollowUp.NONE),
    IS_SPRINTING(FollowUp.NONE),
    NETWORK_FAIL(FollowUp.BACK_OFF),
    FORGE_EVENT_CANCEL(FollowUp.BACK_OFF),
    OVERHEATED(FollowUp.NONE),
    UNKNOWN(FollowUp.BACK_OFF);

    enum FollowUp {
        NONE,
        DRAW,
        BOLT,
        RELOAD,
        BACK_OFF,
        STOP
    }

    private final FollowUp followUp;

    TaCZShotResult(FollowUp followUp) {
        this.followUp = followUp;
    }

    FollowUp followUp() {
        return followUp;
    }

    static TaCZShotResult fromRuntimeName(String name) {
        try {
            return valueOf(name);
        }
        catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
