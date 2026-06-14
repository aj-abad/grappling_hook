package com.ajabad.grapplinghook;

import org.apache.logging.log4j.Logger;

import com.ajabad.grapplinghook.proxy.CommonProxy;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Main mod entry point. Registration is split across the FML lifecycle and the
 * {@link CommonProxy}/ClientProxy pair so the dedicated server never touches
 * client-only classes.
 */
@Mod(
    modid = Reference.MODID,
    name = Reference.NAME,
    version = Reference.VERSION,
    acceptedMinecraftVersions = Reference.ACCEPTED_MC_VERSIONS
)
public class ModGrapplingHook
{
    @Instance(Reference.MODID)
    public static ModGrapplingHook instance;

    @SidedProxy(clientSide = Reference.CLIENT_PROXY, serverSide = Reference.SERVER_PROXY)
    public static CommonProxy proxy;

    public static Logger log;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        log = event.getModLog();
        log.info("preInit " + Reference.NAME + " v" + Reference.VERSION);
        proxy.preInit(event);
    }

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        log.info("init");
        proxy.init(event);
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event)
    {
        log.info("postInit");
        proxy.postInit(event);
    }
}
