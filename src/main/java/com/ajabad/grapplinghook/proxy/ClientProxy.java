package com.ajabad.grapplinghook.proxy;

import cpw.mods.fml.common.event.FMLInitializationEvent;

/**
 * Client-only proxy. The safe home for rendering and any net.minecraft.client.*
 * / LWJGL references that must not load on a dedicated server.
 */
public class ClientProxy extends CommonProxy
{
    @Override
    public void init(FMLInitializationEvent event)
    {
        super.init(event);
        registerRenderers();
    }

    @Override
    public void registerRenderers()
    {
        // Register entity / tile-entity renderers here, e.g.:
        //   RenderingRegistry.registerEntityRenderingHandler(MyEntity.class, new MyRender());
    }
}
