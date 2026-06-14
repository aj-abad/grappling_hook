package com.ajabad.grapplinghook;

/**
 * Entity registration. Invoked from
 * {@link com.ajabad.grapplinghook.proxy.CommonProxy#preInit}. Register entities
 * with {@code EntityRegistry.registerModEntity}, which also wires FML's spawn
 * packet, so the client reconstructs the entity via its (World) constructor.
 */
public final class ModEntities
{
    public static void register()
    {
        // No entities yet. Example:
        //   EntityRegistry.registerModEntity(MyEntity.class, "my_entity", 1,
        //       ModGrapplingHook.instance, 80, 1, true);
    }

    private ModEntities() {}
}
