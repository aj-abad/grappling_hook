package com.ajabad.grapplinghook.proxy;

import com.ajabad.grapplinghook.client.ClientInputHandler;
import com.ajabad.grapplinghook.client.ModKeys;
import com.ajabad.grapplinghook.client.render.RenderGrapplingHook;
import com.ajabad.grapplinghook.entity.EntityGrapplingHook;

import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;

import net.minecraftforge.common.MinecraftForge;

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
        ModKeys.register();

        ClientInputHandler input = new ClientInputHandler();
        FMLCommonHandler.instance().bus().register(input); // ClientTickEvent
        MinecraftForge.EVENT_BUS.register(input);          // MouseEvent
    }

    @Override
    public void registerRenderers()
    {
        RenderingRegistry.registerEntityRenderingHandler(EntityGrapplingHook.class,
                new RenderGrapplingHook());
    }
}
