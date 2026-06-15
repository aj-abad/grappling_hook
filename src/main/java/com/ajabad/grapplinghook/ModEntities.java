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
        // trackingRange 64, updateFrequency 1, sendVelocityUpdates true. The client
        // runs the flight physics itself (prediction) like a vanilla arrow, and a
        // per-tick position correction keeps client and server on the same path so
        // they detect the block impact on the same tick (no mid-air anchoring).
        EntityRegistry.registerModEntity(EntityGrapplingHook.class, "grappling_hook",
                ID_GRAPPLING_HOOK, ModGrapplingHook.instance, 64, 1, true);
    }

    private ModEntities() {}
}
