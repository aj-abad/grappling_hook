package com.ajabad.grapplinghook;

import com.ajabad.grapplinghook.entity.EntityGrapplingHook;

import cpw.mods.fml.common.registry.EntityRegistry;

/**
 * Entity registration. Invoked from
 * {@link com.ajabad.grapplinghook.proxy.CommonProxy#preInit}. Register entities
 * with {@code EntityRegistry.registerModEntity}, which also wires FML's spawn
 * packet, so the client reconstructs the entity via its (World) constructor.
 */
public final class ModEntities
{
    /** Mod-local entity id (unique within this mod, not globally). */
    private static final int ID_GRAPPLING_HOOK = 0;

    public static void register()
    {
        // trackingRange 64, updateFrequency 20, sendVelocityUpdates false -- matching a
        // vanilla arrow. The client predicts the flight itself from the exact launch
        // velocity (sent unclamped via EntityGrapplingHook's IEntityAdditionalSpawnData;
        // the normal spawn/velocity packets clamp to +/-3.9 b/t, below LAUNCH_SPEED) and
        // runs the same physics as the server, so it needs no per-tick correction. Those
        // per-tick corrections -- snapping to a quantized, network-lagged server position
        // every tick at 4.5 b/t -- were the flight jitter. The authoritative STUCK anchor
        // still syncs the instant it forms: the tracker flushes DataWatcher changes as
        // soon as they happen, independent of updateFrequency.
        EntityRegistry.registerModEntity(EntityGrapplingHook.class, "grappling_hook",
                ID_GRAPPLING_HOOK, ModGrapplingHook.instance, 64, 20, false);
    }

    private ModEntities() {}
}
