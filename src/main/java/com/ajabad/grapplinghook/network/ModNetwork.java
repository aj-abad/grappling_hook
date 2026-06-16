package com.ajabad.grapplinghook.network;

import com.ajabad.grapplinghook.Reference;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * The mod's network channel (FML {@link SimpleNetworkWrapper}).
 */
public final class ModNetwork
{
    public static SimpleNetworkWrapper CHANNEL;

    public static void init()
    {
        CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(Reference.MODID);

        // Client -> server intents (discrete tether actions).
        CHANNEL.registerMessage(MsgRetract.Handler.class, MsgRetract.class, 0, Side.SERVER);
        CHANNEL.registerMessage(MsgRelease.Handler.class, MsgRelease.class, 1, Side.SERVER);
        // Mob-latch intents: reel toggle (hold use) and the left-click mob yank. Both
        // act on another entity, so they run server-side rather than client-predicted.
        CHANNEL.registerMessage(MsgReel.Handler.class, MsgReel.class, 2, Side.SERVER);
        CHANNEL.registerMessage(MsgYankMob.Handler.class, MsgYankMob.class, 3, Side.SERVER);
        // NOTE: remote players already see a correct straight cord (the renderer
        // falls back to hand->anchor when it has no local pivot list, and a stuck
        // hook's entity position is the anchor). A C->S->C wrapped-cord sync would
        // only add the *wrapped* bends to that remote view; it's intentionally
        // omitted for now (cosmetic, LAN-only, and awkward given the threading model).
    }

    private ModNetwork() {}
}
