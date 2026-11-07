package org.mcmonkey.sentinel.integration;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaCZAimTest {

    private static final float EPSILON = 0.0001f;

    @Test
    void convertsCardinalDirectionsToMinecraftRotations() {
        assertRotation(new Vector(0, 0, 1), 0, 0);
        assertRotation(new Vector(1, 0, 0), 0, -90);
        assertRotation(new Vector(-1, 0, 0), 0, 90);
        assertRotation(new Vector(0, 0, -1), 0, -180);
    }

    @Test
    void convertsVerticalComponentToPitch() {
        assertRotation(new Vector(0, 1, 1), -45, 0);
        assertRotation(new Vector(0, -1, 1), 45, 0);
    }

    private static void assertRotation(Vector direction, float pitch, float yaw) {
        TaCZAim rotation = TaCZAim.fromDirection(direction);
        assertEquals(pitch, rotation.pitch, EPSILON);
        assertEquals(yaw, rotation.yaw, EPSILON);
    }
}
