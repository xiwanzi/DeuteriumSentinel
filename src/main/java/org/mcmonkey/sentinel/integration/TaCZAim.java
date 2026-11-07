package org.mcmonkey.sentinel.integration;

import org.bukkit.util.Vector;

/** Converts a Bukkit direction vector to the rotations expected by TaCZ. */
final class TaCZAim {

    final float pitch;
    final float yaw;

    private TaCZAim(float pitch, float yaw) {
        this.pitch = pitch;
        this.yaw = yaw;
    }

    static TaCZAim fromDirection(Vector direction) {
        double horizontal = Math.sqrt(direction.getX() * direction.getX()
                + direction.getZ() * direction.getZ());
        float yaw = (float) -Math.toDegrees(Math.atan2(direction.getX(), direction.getZ()));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.getY(), horizontal));
        return new TaCZAim(pitch, yaw);
    }
}
