package org.mcmonkey.sentinel.targeting;

import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.util.BoundingBox;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CitizensPathStartTest {
    @Test
    void constructsThePathStartUsingTheInstalledCitizensApi() {
        Location start = new Location(null, 4, 64, 8);
        BlockSource blocks = new BlockSource() {
            @Override public BoundingBox getCollisionBox(int x, int y, int z) { return null; }
            @Override public Material getMaterialAt(int x, int y, int z) { return Material.AIR; }
            @Override public World getWorld() { return null; }
        };
        // Node construction preserves coordinates before world-dependent goal/path expansion begins.
        assertEquals(start.toVector(), org.mcmonkey.sentinel.utilities.CitizensPathNode.create(
                null, start, blocks, new net.citizensnpcs.api.ai.NavigatorParameters()).getVector());
    }
}
