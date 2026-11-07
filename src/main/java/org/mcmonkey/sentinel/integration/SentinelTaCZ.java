package org.mcmonkey.sentinel.integration;

import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.ai.TargetType;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.util.NMS;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.mcmonkey.sentinel.SentinelIntegration;
import org.mcmonkey.sentinel.SentinelPlugin;
import org.mcmonkey.sentinel.SentinelTrait;
import org.mcmonkey.sentinel.events.SentinelChaseEndEvent;
import org.mcmonkey.sentinel.events.SentinelCombatStateChangeEvent;
import org.mcmonkey.sentinel.events.SentinelNoMoreTargetsEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Native Sentinel weapon integration for TaCZ on Youer 1.21.1.
 *
 * <p>Sentinel owns target selection, line of sight and navigation. This class
 * only drives TaCZ while a Sentinel has an active target. A one-tick task is
 * created lazily for active shooters and removed as soon as the last firing
 * session ends, preserving TaCZ's native RPM without increasing Sentinel's
 * global update rate.</p>
 */
public final class SentinelTaCZ extends SentinelIntegration implements Listener {

    private static final long ERROR_LOG_INTERVAL_TICKS = 200;

    private final SentinelPlugin plugin;
    private final SentinelTaCZBridge bridge;
    private final Map<UUID, FireSession> sessions = new HashMap<>();
    private final Map<UUID, TacticalMovement> tacticalMovements = new HashMap<>();
    private BukkitTask sessionTask;
    private boolean alwaysAim;
    private boolean infiniteAmmoFollowsSentinel;
    private double safeShotRadius;
    private int sessionTimeoutTicks;
    private int firingPositionSearchIntervalTicks;
    private int jumpIntervalTicks;
    private long lastGlobalErrorLogTick = Long.MIN_VALUE / 2;

    /** Returns a bound integration, or {@code null} when TaCZ is not installed. */
    public static SentinelTaCZ create(SentinelPlugin plugin) throws ReflectiveOperationException {
        SentinelTaCZBridge bridge = SentinelTaCZBridge.tryBind();
        if (bridge == null) {
            return null;
        }
        return new SentinelTaCZ(plugin, bridge);
    }

    private SentinelTaCZ(SentinelPlugin plugin, SentinelTaCZBridge bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
        reloadSettings();
    }

    /** Registers lifecycle listeners after the integration has been accepted by Sentinel. */
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Reloads the small set of global TaCZ behavior settings. */
    public void reloadSettings() {
        alwaysAim = plugin.getConfig().getBoolean("tacz.always aim", true);
        infiniteAmmoFollowsSentinel = plugin.getConfig().getBoolean(
                "tacz.infinite ammo follows sentinel", true);
        safeShotRadius = Math.max(0.0,
                plugin.getConfig().getDouble("tacz.safe shot radius", 0.35));
        sessionTimeoutTicks = Math.max(2,
                plugin.getConfig().getInt("tacz.session timeout ticks", 25));
        firingPositionSearchIntervalTicks = Math.max(5,
                plugin.getConfig().getInt("tacz.firing position search interval ticks", 20));
        jumpIntervalTicks = Math.max(4,
                plugin.getConfig().getInt("tacz.jump interval ticks", 10));
    }

    /** Stops every session and restores any temporary player ability changes. */
    public void shutdown() {
        for (FireSession session : new ArrayList<>(sessions.values())) {
            session.close();
        }
        sessions.clear();
        tacticalMovements.clear();
        if (sessionTask != null) {
            sessionTask.cancel();
            sessionTask = null;
        }
    }

    @Override
    public boolean itemIsRanged(SentinelTrait sentinel, ItemStack item) {
        if (!(sentinel.getLivingEntity() instanceof Player)) {
            return false;
        }
        try {
            return bridge.isGun(item);
        }
        catch (ReflectiveOperationException ex) {
            reportFailure(sentinel, "识别 TaCZ 枪械", ex);
            return false;
        }
    }

    @Override
    public boolean tryAttack(SentinelTrait sentinel, LivingEntity target) {
        if (!(sentinel.getLivingEntity() instanceof Player)) {
            return false;
        }
        try {
            SentinelTaCZBridge.Shooter shooter = bridge.getShooter((Player) sentinel.getLivingEntity());
            if (shooter == null) {
                return false;
            }
            refreshSession(sentinel, target, shooter);
            if (!sentinel.taczMoveWhileShooting && sentinel.getNPC().getNavigator().isNavigating()) {
                sentinel.getNPC().getNavigator().cancelNavigation();
            }
            else if (sentinel.rangedChase && !hasActiveFiringPositionNavigation(sentinel, target)) {
                sentinel.attackHelper.rechase();
            }
            return true;
        }
        catch (ReflectiveOperationException ex) {
            // Fail closed: a confirmed TaCZ runtime error must never turn the gun
            // into Sentinel's fallback melee weapon for this attack.
            reportFailure(sentinel, "启动 TaCZ 射击", ex);
            return true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChaseEnd(SentinelChaseEndEvent event) {
        stop(event.getNPC());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCombatStateChange(SentinelCombatStateChangeEvent event) {
        if (!event.isInCombat) {
            stop(event.getNPC());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onNoMoreTargets(SentinelNoMoreTargetsEvent event) {
        stop(event.getNPC());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDespawn(NPCDespawnEvent event) {
        stop(event.getNPC());
    }

    private void refreshSession(SentinelTrait sentinel, LivingEntity target,
                                SentinelTaCZBridge.Shooter shooter) {
        UUID npcId = sentinel.getNPC().getUniqueId();
        FireSession session = sessions.get(npcId);
        if (session == null) {
            session = new FireSession(sentinel, target, shooter);
            sessions.put(npcId, session);
        }
        else {
            session.refresh(target, shooter);
        }
        ensureTaskRunning();
    }

    private void ensureTaskRunning() {
        if (sessionTask == null) {
            sessionTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSessions, 1L, 1L);
        }
    }

    private void tickSessions() {
        for (FireSession session : new ArrayList<>(sessions.values())) {
            boolean keep;
            try {
                keep = session.tick();
            }
            catch (ReflectiveOperationException | RuntimeException ex) {
                session.backOff();
                reportFailure(session.sentinel, "执行 TaCZ 射击状态机", ex);
                keep = !session.hasExpired();
            }
            if (!keep && sessions.remove(session.npcId, session)) {
                session.close();
            }
        }
        if (sessions.isEmpty() && sessionTask != null) {
            sessionTask.cancel();
            sessionTask = null;
        }
    }

    private void stop(NPC npc) {
        FireSession session = sessions.remove(npc.getUniqueId());
        if (session != null) {
            session.close();
        }
        tacticalMovements.remove(npc.getUniqueId());
    }

    /** Returns whether the selected Sentinel is currently holding a TaCZ gun. */
    public boolean isTaCZWeapon(SentinelTrait sentinel) {
        if (!(sentinel.getLivingEntity() instanceof Player)) {
            return false;
        }
        try {
            return bridge.isGun(((Player) sentinel.getLivingEntity()).getInventory().getItemInMainHand());
        }
        catch (ReflectiveOperationException ex) {
            reportFailure(sentinel, "识别 TaCZ 枪械", ex);
            return false;
        }
    }

    /**
     * Handles a blocked TaCZ shot by navigating to a nearby valid firing
     * position. Returns true when the normal no-line-of-sight path has been
     * replaced by this tactical behavior.
     */
    public boolean handleObstructedShot(SentinelTrait sentinel, LivingEntity target) {
        if (!sentinel.taczRelocateForShot || !isTaCZWeapon(sentinel)) {
            return false;
        }
        requestFiringPosition(sentinel, target);
        return true;
    }

    private void requestFiringPosition(SentinelTrait sentinel, LivingEntity target) {
        UUID npcId = sentinel.getNPC().getUniqueId();
        TacticalMovement movement = tacticalMovements.computeIfAbsent(npcId, ignored -> new TacticalMovement());
        long now = plugin.tickTimeTotal;
        if (movement.targetId != null && movement.targetId.equals(target.getUniqueId())
                && now - movement.lastSearchTick < firingPositionSearchIntervalTicks) {
            return;
        }
        movement.targetId = target.getUniqueId();
        movement.lastSearchTick = now;
        movement.lastPosition = null;
        Location firingPosition = findFiringPosition(sentinel, target);
        if (firingPosition == null) {
            sentinel.attackHelper.chase(target);
            return;
        }
        sentinel.pauseWaypoints();
        sentinel.chased = true;
        sentinel.needsSafeReturn = true;
        sentinel.getNPC().getNavigator().setTarget(firingPosition);
        sentinel.getNPC().getNavigator().getLocalParameters().distanceMargin(0.8);
        sentinel.autoSpeedModifier();
        movement.lastPosition = firingPosition;
    }

    private boolean hasActiveFiringPositionNavigation(SentinelTrait sentinel, LivingEntity target) {
        TacticalMovement movement = tacticalMovements.get(sentinel.getNPC().getUniqueId());
        if (movement == null || movement.lastPosition == null
                || !target.getUniqueId().equals(movement.targetId)
                || !sentinel.getNPC().getNavigator().isNavigating()
                || sentinel.getNPC().getNavigator().getTargetType() != TargetType.LOCATION) {
            return false;
        }
        Location navigationTarget = sentinel.getNPC().getNavigator().getTargetAsLocation();
        return navigationTarget != null && navigationTarget.getWorld().equals(movement.lastPosition.getWorld())
                && navigationTarget.distanceSquared(movement.lastPosition) < 1.0;
    }

    private Location findFiringPosition(SentinelTrait sentinel, LivingEntity target) {
        Location shooter = sentinel.getLivingEntity().getLocation();
        Location targetEye = target.getEyeLocation();
        if (!shooter.getWorld().equals(targetEye.getWorld())) {
            return null;
        }
        Location best = null;
        double bestScore = Double.MAX_VALUE;
        double[] radii = {3.0, 6.0, 10.0};
        Location[] centers = {shooter, target.getLocation()};
        for (Location center : centers) {
            for (double radius : radii) {
                for (int direction = 0; direction < 12; direction++) {
                    double angle = direction * Math.PI * 2.0 / 12.0;
                    for (int yOffset = -1; yOffset <= 1; yOffset++) {
                        Location candidate = new Location(shooter.getWorld(),
                                center.getX() + Math.cos(angle) * radius,
                                center.getBlockY() + yOffset,
                                center.getZ() + Math.sin(angle) * radius);
                        candidate.setX(Math.floor(candidate.getX()) + 0.5);
                        candidate.setZ(Math.floor(candidate.getZ()) + 0.5);
                        if (!isStandable(candidate)) {
                            continue;
                        }
                        Location candidateEye = candidate.clone().add(0, 1.62, 0);
                        if (candidateEye.distanceSquared(targetEye)
                                > sentinel.projectileRange * sentinel.projectileRange
                                || !hasClearBlockShot(candidateEye, targetEye)
                                || !sentinel.canPathTo(candidate)) {
                            continue;
                        }
                        double score = shooter.distanceSquared(candidate)
                                + candidate.distanceSquared(target.getLocation()) * 0.05;
                        if (score < bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
        }
        return best;
    }

    private boolean isStandable(Location location) {
        World world = location.getWorld();
        Block feet = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        Block head = feet.getRelative(0, 1, 0);
        Block floor = feet.getRelative(0, -1, 0);
        return feet.isPassable() && head.isPassable() && floor.getType().isSolid();
    }

    private boolean hasClearBlockShot(Location from, Location to) {
        Vector ray = to.toVector().subtract(from.toVector());
        double distance = ray.length();
        if (distance < 0.001) {
            return true;
        }
        return from.getWorld().rayTraceBlocks(from, ray.normalize(), distance,
                FluidCollisionMode.NEVER, true) == null;
    }

    private void reportFailure(SentinelTrait sentinel, String operation, Throwable error) {
        long now = plugin.tickTimeTotal;
        FireSession session = sessions.get(sentinel.getNPC().getUniqueId());
        if (session != null) {
            if (now - session.lastErrorLogTick < ERROR_LOG_INTERVAL_TICKS) {
                return;
            }
            session.lastErrorLogTick = now;
        }
        else {
            if (now - lastGlobalErrorLogTick < ERROR_LOG_INTERVAL_TICKS) {
                return;
            }
            lastGlobalErrorLogTick = now;
        }
        plugin.getLogger().warning("NPC " + sentinel.getNPC().getId() + " " + operation
                + "失败：" + error.getClass().getSimpleName() + ": " + error.getMessage());
        if (SentinelPlugin.debugMe) {
            error.printStackTrace();
        }
    }

    private final class FireSession {

        private final UUID npcId;
        private final SentinelTrait sentinel;
        private final TaCZTickWatchdog tickWatchdog = new TaCZTickWatchdog();
        private LivingEntity target;
        private SentinelTaCZBridge.Shooter shooter;
        private Object drawnHandle;
        private Object drawnItem;
        private boolean aiming;
        private boolean infiniteAmmoApplied;
        private boolean previousInfiniteAmmo;
        private long lastRefreshTick;
        private long nextAttemptTick;
        private long suspendedUntilTick;
        private long lastJumpTick = Long.MIN_VALUE / 2;
        private long lastErrorLogTick = Long.MIN_VALUE / 2;

        private FireSession(SentinelTrait sentinel, LivingEntity target,
                            SentinelTaCZBridge.Shooter shooter) {
            this.npcId = sentinel.getNPC().getUniqueId();
            this.sentinel = sentinel;
            this.target = target;
            this.shooter = shooter;
            this.lastRefreshTick = plugin.tickTimeTotal;
        }

        private void refresh(LivingEntity newTarget, SentinelTaCZBridge.Shooter currentShooter) {
            target = newTarget;
            lastRefreshTick = plugin.tickTimeTotal;
            if (shooter.handleIdentity() != currentShooter.handleIdentity()) {
                releaseInfiniteAmmo();
                drawnHandle = null;
                drawnItem = null;
                tickWatchdog.reset();
            }
            shooter = currentShooter;
        }

        private boolean tick() throws ReflectiveOperationException {
            if (hasExpired() || !sentinel.getNPC().isSpawned() || target == null
                    || !target.isValid() || target.isDead()) {
                return false;
            }
            if (plugin.tickTimeTotal < suspendedUntilTick) {
                return true;
            }
            LivingEntity livingShooter = sentinel.getLivingEntity();
            if (!(livingShooter instanceof Player)
                    || !livingShooter.getWorld().equals(target.getWorld())
                    || sentinel.chasing == null
                    || !sentinel.chasing.getUniqueId().equals(target.getUniqueId())
                    || !sentinel.targetingHelper.shouldTarget(target)) {
                return false;
            }

            SentinelTaCZBridge.Shooter currentShooter = bridge.getShooter((Player) livingShooter);
            if (currentShooter == null) {
                return false;
            }
            if (shooter.handleIdentity() != currentShooter.handleIdentity()) {
                releaseInfiniteAmmo();
                drawnHandle = null;
                drawnItem = null;
                tickWatchdog.reset();
            }
            shooter = currentShooter;
            updateInfiniteAmmo();
            advanceStalledTaCZState();
            boolean moving = updateCombatMovement((Player) livingShooter);

            if (drawnHandle != shooter.handleIdentity() || drawnItem != shooter.itemIdentity()) {
                shooter.draw();
                drawnHandle = shooter.handleIdentity();
                drawnItem = shooter.itemIdentity();
                tickWatchdog.reset();
                nextAttemptTick = plugin.tickTimeTotal + 1;
                return true;
            }

            double distanceSquared = livingShooter.getEyeLocation()
                    .distanceSquared(target.getEyeLocation());
            if (distanceSquared > sentinel.projectileRange * sentinel.projectileRange
                    || !sentinel.targetingHelper.canSee(target)) {
                setAim(false);
                if (sentinel.taczRelocateForShot) {
                    requestFiringPosition(sentinel, target);
                }
                return true;
            }

            Vector direction = target.getEyeLocation().toVector()
                    .subtract(livingShooter.getEyeLocation().toVector());
            if (direction.lengthSquared() < 0.000001) {
                return true;
            }
            direction.normalize();
            direction = sentinel.fixForAcc(direction);
            if (direction.lengthSquared() < 0.000001) {
                return true;
            }
            direction.normalize();

            if (sentinel.safeShot && !hasSafeShot(livingShooter, direction)) {
                setAim(false);
                return true;
            }

            Location facing = livingShooter.getEyeLocation().clone().add(direction.clone().multiply(10));
            sentinel.faceLocation(facing);
            setAim(alwaysAim && !(moving && sentinel.taczSprintWhileMoving));

            if (plugin.tickTimeTotal < nextAttemptTick) {
                return true;
            }
            if (moving && sentinel.taczMoveWhileShooting && sentinel.taczSprintWhileMoving) {
                // TaCZ normally refuses all fire while sprintTimeS is positive.
                // This opt-in NPC-only combination intentionally permits moving
                // sprint fire without changing TaCZ behavior for real players.
                shooter.clearSprintShootLock();
            }
            TaCZAim rotation = TaCZAim.fromDirection(direction);
            TaCZShotResult result = shooter.shoot(rotation.pitch, rotation.yaw);
            switch (result.followUp()) {
                case DRAW:
                    shooter.draw();
                    drawnHandle = shooter.handleIdentity();
                    drawnItem = shooter.itemIdentity();
                    break;
                case BOLT:
                    shooter.bolt();
                    break;
                case RELOAD:
                    shooter.reload();
                    nextAttemptTick = plugin.tickTimeTotal + 10;
                    break;
                case BACK_OFF:
                    nextAttemptTick = plugin.tickTimeTotal + 5;
                    break;
                case STOP:
                    return false;
                case NONE:
                default:
                    break;
            }
            return true;
        }

        private void advanceStalledTaCZState() throws ReflectiveOperationException {
            boolean transientStateActive = shooter.hasTransientState();
            if (tickWatchdog.shouldDriveFallback(shooter.nativeTickMarker(), transientStateActive)) {
                shooter.tickTransientState();
            }
        }

        private boolean updateCombatMovement(Player player) throws ReflectiveOperationException {
            boolean moving = sentinel.getNPC().getNavigator().isNavigating();
            boolean sprint = moving && sentinel.taczSprintWhileMoving && !shooter.hasTransientState();
            if (player.isSprinting() != sprint) {
                player.setSprinting(sprint);
            }
            if (moving && sentinel.taczJumpWhileMoving && player.isOnGround()
                    && plugin.tickTimeTotal - lastJumpTick >= jumpIntervalTicks) {
                NMS.setShouldJump(player);
                lastJumpTick = plugin.tickTimeTotal;
            }
            return moving;
        }

        private boolean hasSafeShot(LivingEntity livingShooter, Vector direction) {
            double range = Math.max(1.0, sentinel.projectileRange);
            Predicate<Entity> livingNotSelf = entity -> entity instanceof LivingEntity
                    && !entity.getUniqueId().equals(livingShooter.getUniqueId());
            RayTraceResult trace = livingShooter.getWorld().rayTraceEntities(
                    livingShooter.getEyeLocation(), direction, range, safeShotRadius, livingNotSelf);
            if (trace == null || !(trace.getHitEntity() instanceof LivingEntity)) {
                return true;
            }
            LivingEntity firstHit = (LivingEntity) trace.getHitEntity();
            return firstHit.getUniqueId().equals(target.getUniqueId())
                    || sentinel.targetingHelper.shouldTarget(firstHit);
        }

        private void updateInfiniteAmmo() throws ReflectiveOperationException {
            boolean shouldBeInfinite = infiniteAmmoFollowsSentinel && !sentinel.needsAmmo;
            if (shouldBeInfinite && !infiniteAmmoApplied) {
                previousInfiniteAmmo = shooter.isInfiniteAmmo();
                shooter.setInfiniteAmmo(true);
                infiniteAmmoApplied = true;
            }
            else if (shouldBeInfinite && !shooter.isInfiniteAmmo()) {
                // Citizens or another game-mode synchronizer may refresh player
                // abilities while the NPC is alive. Keep only this flag pinned
                // for the duration of the active firing session.
                shooter.setInfiniteAmmo(true);
            }
            else if (!shouldBeInfinite && infiniteAmmoApplied) {
                shooter.setInfiniteAmmo(previousInfiniteAmmo);
                infiniteAmmoApplied = false;
            }
        }

        private void setAim(boolean shouldAim) throws ReflectiveOperationException {
            if (aiming != shouldAim) {
                shooter.aim(shouldAim);
                aiming = shouldAim;
            }
        }

        private boolean hasExpired() {
            return plugin.tickTimeTotal - lastRefreshTick > sessionTimeoutTicks;
        }

        private void backOff() {
            suspendedUntilTick = plugin.tickTimeTotal + 20;
        }

        private void close() {
            try {
                if (aiming) {
                    shooter.aim(false);
                    aiming = false;
                }
                if (sentinel.getNPC().isSpawned()) {
                    LivingEntity living = sentinel.getLivingEntity();
                    if (living instanceof Player && ((Player) living).isSprinting()) {
                        ((Player) living).setSprinting(false);
                    }
                }
            }
            catch (ReflectiveOperationException ignored) {
                // Entity teardown can make the old handle unreachable.
            }
            releaseInfiniteAmmo();
        }

        private void releaseInfiniteAmmo() {
            if (!infiniteAmmoApplied) {
                return;
            }
            try {
                shooter.setInfiniteAmmo(previousInfiniteAmmo);
            }
            catch (ReflectiveOperationException ignored) {
                // Entity teardown can make the old handle unreachable.
            }
            infiniteAmmoApplied = false;
        }
    }

    private static final class TacticalMovement {
        private UUID targetId;
        private long lastSearchTick = Long.MIN_VALUE / 2;
        private Location lastPosition;
    }
}
