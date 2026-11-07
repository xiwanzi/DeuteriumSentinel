package org.mcmonkey.sentinel.integration;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Reflection-only binding to TaCZ.
 *
 * <p>Sentinel remains a normal Bukkit plugin and can still load on servers that
 * do not have NeoForge or TaCZ. Youer exposes the modded Minecraft runtime to
 * plugin class loaders, so the exact TaCZ 1.21.1 API is bound only after the
 * mod has already loaded.</p>
 */
final class SentinelTaCZBridge {

    private final Method asNmsCopy;
    private final Method fromLivingEntity;
    private final Method getAbilities;
    private final Method getDataHolder;
    private final Method getHandle;
    private final Method getIGunOrNull;
    private final Method getMainHandItem;
    private final Method tickBolt;
    private final Method tickReload;
    private final Field aimingTimestamp;
    private final Field nativeBolt;
    private final Field nativeReload;
    private final Field instabuild;
    private final Field isBolting;
    private final Field reloadTimestamp;
    private final Field sprintTime;
    private final Method aim;
    private final Method bolt;
    private final Method draw;
    private final Method reload;
    private final Method shoot;

    /**
     * Attempts to bind TaCZ. Returns {@code null} when TaCZ is not installed.
     * An installed but incompatible TaCZ build throws, so startup can report a
     * useful API mismatch instead of silently treating guns as melee weapons.
     */
    static SentinelTaCZBridge tryBind() throws ReflectiveOperationException {
        try {
            runtimeClass("com.tacz.guns.api.entity.IGunOperator");
        }
        catch (ClassNotFoundException ex) {
            return null;
        }
        return new SentinelTaCZBridge();
    }

    private SentinelTaCZBridge() throws ReflectiveOperationException {
        Class<?> nmsItemStack = runtimeClass("net.minecraft.world.item.ItemStack");
        Class<?> nmsLivingEntity = runtimeClass("net.minecraft.world.entity.LivingEntity");
        Class<?> nmsPlayer = runtimeClass("net.minecraft.world.entity.player.Player");
        Class<?> abilities = runtimeClass("net.minecraft.world.entity.player.Abilities");
        Class<?> craftPlayer = runtimeClass("org.bukkit.craftbukkit.entity.CraftPlayer");
        Class<?> craftItemStack = runtimeClass("org.bukkit.craftbukkit.inventory.CraftItemStack");
        Class<?> gun = runtimeClass("com.tacz.guns.api.item.IGun");
        Class<?> gunOperator = runtimeClass("com.tacz.guns.api.entity.IGunOperator");
        Class<?> dataHolder = runtimeClass("com.tacz.guns.entity.shooter.ShooterDataHolder");
        Class<?> reload = runtimeClass("com.tacz.guns.entity.shooter.LivingEntityReload");
        Class<?> bolt = runtimeClass("com.tacz.guns.entity.shooter.LivingEntityBolt");

        getHandle = craftPlayer.getMethod("getHandle");
        asNmsCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
        getMainHandItem = nmsLivingEntity.getMethod("getMainHandItem");
        getIGunOrNull = gun.getMethod("getIGunOrNull", nmsItemStack);
        fromLivingEntity = gunOperator.getMethod("fromLivingEntity", nmsLivingEntity);
        getDataHolder = gunOperator.getMethod("getDataHolder");
        draw = gunOperator.getMethod("draw", Supplier.class);
        aim = gunOperator.getMethod("aim", boolean.class);
        shoot = gunOperator.getMethod("shoot", Supplier.class, Supplier.class);
        this.bolt = gunOperator.getMethod("bolt");
        this.reload = gunOperator.getMethod("reload");
        tickReload = reload.getMethod("tickReloadState");
        tickBolt = bolt.getMethod("tickBolt");
        nativeReload = nmsLivingEntity.getDeclaredField("tacz$reload");
        nativeReload.setAccessible(true);
        nativeBolt = nmsLivingEntity.getDeclaredField("tacz$bolt");
        nativeBolt.setAccessible(true);
        aimingTimestamp = dataHolder.getField("aimingTimestamp");
        reloadTimestamp = dataHolder.getField("reloadTimestamp");
        isBolting = dataHolder.getField("isBolting");
        sprintTime = dataHolder.getField("sprintTimeS");
        getAbilities = nmsPlayer.getMethod("getAbilities");
        instabuild = abilities.getField("instabuild");
    }

    boolean isGun(ItemStack stack) throws ReflectiveOperationException {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        Object nativeStack = invoke(asNmsCopy, null, stack);
        return invoke(getIGunOrNull, null, nativeStack) != null;
    }

    Shooter getShooter(Player player) throws ReflectiveOperationException {
        Object handle = invoke(getHandle, player);
        Object nativeStack = invoke(getMainHandItem, handle);
        if (invoke(getIGunOrNull, null, nativeStack) == null) {
            return null;
        }
        Object operator = invoke(fromLivingEntity, null, handle);
        return new Shooter(handle, nativeStack, operator);
    }

    final class Shooter {

        private final Object handle;
        private final Object itemStack;
        private final Object operator;

        private Shooter(Object handle, Object itemStack, Object operator) {
            this.handle = handle;
            this.itemStack = itemStack;
            this.operator = operator;
        }

        Object handleIdentity() {
            return handle;
        }

        Object itemIdentity() {
            return itemStack;
        }

        void draw() throws ReflectiveOperationException {
            Supplier<Object> currentMainHand = () -> {
                try {
                    return invoke(getMainHandItem, handle);
                }
                catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Unable to read the TaCZ shooter's main hand", ex);
                }
            };
            invoke(SentinelTaCZBridge.this.draw, operator, currentMainHand);
        }

        void aim(boolean aiming) throws ReflectiveOperationException {
            invoke(SentinelTaCZBridge.this.aim, operator, aiming);
        }

        TaCZShotResult shoot(float pitch, float yaw) throws ReflectiveOperationException {
            Supplier<Float> pitchSupplier = () -> pitch;
            Supplier<Float> yawSupplier = () -> yaw;
            Object result = invoke(SentinelTaCZBridge.this.shoot, operator, pitchSupplier, yawSupplier);
            return TaCZShotResult.fromRuntimeName(((Enum<?>) result).name());
        }

        void bolt() throws ReflectiveOperationException {
            invoke(SentinelTaCZBridge.this.bolt, operator);
        }

        void reload() throws ReflectiveOperationException {
            invoke(SentinelTaCZBridge.this.reload, operator);
        }

        /** Allows an explicitly configured NPC to fire while its entity is sprinting. */
        void clearSprintShootLock() throws ReflectiveOperationException {
            Object data = invoke(getDataHolder, operator);
            sprintTime.setFloat(data, 0.0f);
        }

        long nativeTickMarker() throws ReflectiveOperationException {
            Object data = invoke(getDataHolder, operator);
            return aimingTimestamp.getLong(data);
        }

        boolean hasTransientState() throws ReflectiveOperationException {
            Object data = invoke(getDataHolder, operator);
            return reloadTimestamp.getLong(data) != -1L || isBolting.getBoolean(data);
        }

        /**
         * Advances TaCZ's time-based reload and manual-bolt state for Citizens
         * fake players whose normal LivingEntity mixin tick is not running.
         * The caller guards this with a native-tick watchdog, so a normal
         * server player never receives duplicate state ticks.
         */
        void tickTransientState() throws ReflectiveOperationException {
            Object data = invoke(getDataHolder, operator);
            // TaCZ 1.2 owns completion events and tracking packets in its shooter
            // components. Calling only the item tick would skip those transitions.
            if (reloadTimestamp.getLong(data) != -1L) {
                invoke(tickReload, nativeReload.get(handle));
            }
            if (isBolting.getBoolean(data)) {
                invoke(tickBolt, nativeBolt.get(handle));
            }
        }

        boolean isInfiniteAmmo() throws ReflectiveOperationException {
            Object abilities = invoke(getAbilities, handle);
            return instabuild.getBoolean(abilities);
        }

        void setInfiniteAmmo(boolean enabled) throws ReflectiveOperationException {
            Object abilities = invoke(getAbilities, handle);
            instabuild.setBoolean(abilities, enabled);
        }
    }

    private static Class<?> runtimeClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, SentinelTaCZBridge.class.getClassLoader());
    }

    private static Object invoke(Method method, Object owner, Object... arguments)
            throws ReflectiveOperationException {
        try {
            return method.invoke(owner, arguments);
        }
        catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ReflectiveOperationException) {
                throw (ReflectiveOperationException) cause;
            }
            throw new TaCZInvocationException(method.getName(), cause == null ? ex : cause);
        }
    }

    private static final class TaCZInvocationException extends ReflectiveOperationException {

        private TaCZInvocationException(String operation, Throwable cause) {
            super("TaCZ 操作失败：" + operation, cause);
        }
    }
}
