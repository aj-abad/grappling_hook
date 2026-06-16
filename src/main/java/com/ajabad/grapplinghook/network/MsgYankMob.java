package com.ajabad.grapplinghook.network;

import com.ajabad.grapplinghook.entity.EntityGrapplingHook;
import com.ajabad.grapplinghook.event.ServerEventHandler;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Client -&gt; server: the player left-clicked while a hook is latched onto a mob.
 * The server flings the latched mob toward the player and then drops the hook,
 * reverting the item to primed -- mirroring the player-to-hook yank's fling and
 * disconnect, but with the mob-yank tuning constants.
 */
public class MsgYankMob implements IMessage
{
    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<MsgYankMob, IMessage>
    {
        @Override
        public IMessage onMessage(MsgYankMob msg, MessageContext ctx)
        {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            ServerEventHandler.schedule(new Runnable()
            {
                @Override
                public void run()
                {
                    EntityGrapplingHook hook = EntityGrapplingHook.findForPlayer(player);
                    if (hook != null && hook.isLatched())
                    {
                        hook.yankTarget();
                    }
                }
            });
            return null;
        }
    }
}
