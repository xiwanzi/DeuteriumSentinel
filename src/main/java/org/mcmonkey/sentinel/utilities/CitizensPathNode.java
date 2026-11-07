package org.mcmonkey.sentinel.utilities;

import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.astar.pathfinder.VectorGoal;
import net.citizensnpcs.api.astar.pathfinder.VectorNode;
import org.bukkit.Location;

import java.lang.reflect.Constructor;

/** Bridges the Citizens path-node constructor change without selecting a server version by name. */
public final class CitizensPathNode {
    private static final Constructor<VectorNode> CONSTRUCTOR = constructor();

    private CitizensPathNode() { }

    private static Constructor<VectorNode> constructor() {
        try {
            return VectorNode.class.getConstructor(VectorGoal.class, Location.class, BlockSource.class, NavigatorParameters.class);
        }
        catch (NoSuchMethodException olderCitizens) {
            try {
                return VectorNode.class.getConstructor(VectorGoal.class, Location.class, BlockSource.class, BlockExaminer[].class);
            }
            catch (NoSuchMethodException unsupported) {
                throw new ExceptionInInitializerError(unsupported);
            }
        }
    }

    public static VectorNode create(VectorGoal goal, Location start, BlockSource blocks,
                                    NavigatorParameters parameters, BlockExaminer... examiners) {
        Object policy = CONSTRUCTOR.getParameterTypes()[3] == NavigatorParameters.class ? parameters : examiners;
        try {
            return CONSTRUCTOR.newInstance(goal, start, blocks, policy);
        }
        catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot construct Citizens pathfinding node", failure);
        }
    }
}
