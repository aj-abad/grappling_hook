package com.ajabad.grapplinghook.network;

import com.ajabad.grapplinghook.Reference;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;

/**
 * The mod's network channel (FML {@link SimpleNetworkWrapper}). Register messages
 * with {@code CHANNEL.registerMessage(...)} once you add IMessage / IMessageHandler types.
 */
public final class ModNetwork
{
    public static SimpleNetworkWrapper CHANNEL;

    public static void init()
    {
        CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(Reference.MODID);
        // Register messages here, e.g. (discriminator 0, handled on the server):
        //   CHANNEL.registerMessage(MyMessage.Handler.class, MyMessage.class, 0, Side.SERVER);
    }

    private ModNetwork() {}
}
