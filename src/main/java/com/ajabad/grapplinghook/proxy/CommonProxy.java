package com.ajabad.grapplinghook.proxy;

import com.ajabad.grapplinghook.ModEntities;
import com.ajabad.grapplinghook.ModItems;
import com.ajabad.grapplinghook.event.ServerEventHandler;
import com.ajabad.grapplinghook.network.ModNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

import net.minecraftforge.common.MinecraftForge;

/**
 * Server-safe proxy (also the dedicated-server proxy).
 *
 * IMPORTANT: nothing in this class — or anything it transitively loads — may
 * reference client-only types (net.minecraft.client.*, Minecraft, Render*,
 * org.lwjgl.input.Keyboard, etc.). Those belong in {@link ClientProxy}.
 */
public class CommonProxy
{
    public void preInit(FMLPreInitializationEvent event)
    {
        ModItems.register();
        ModEntities.register();
        ModNetwork.init();
    }

    public void init(FMLInitializationEvent event)
    {
        ServerEventHandler handler = new ServerEventHandler();
        FMLCommonHandler.instance().bus().register(handler); // ServerTickEvent, PlayerLoggedOutEvent
        MinecraftForge.EVENT_BUS.register(handler);          // ItemTossEvent, LivingDeathEvent
    }

    public void postInit(FMLPostInitializationEvent event) {}

    /** No-op on the server; overridden on the client. */
    public void registerRenderers() {}
}
