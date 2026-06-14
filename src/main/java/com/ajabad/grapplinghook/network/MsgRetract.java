package com.ajabad.grapplinghook.network;

import com.ajabad.grapplinghook.entity.EntityGrapplingHook;
import com.ajabad.grapplinghook.event.ServerEventHandler;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Client -> server: the player left-clicked while the hook is still in flight.
 * The server flips the hook into its retracting state.
 */
public class MsgRetract implements IMessage
{
    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<MsgRetract, IMessage>
    {
        @Override
        public IMessage onMessage(MsgRetract msg, MessageContext ctx)
        {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            ServerEventHandler.schedule(new Runnable()
            {
                @Override
                public void run()
                {
                    EntityGrapplingHook hook = EntityGrapplingHook.findForPlayer(player);
                    if (hook != null && hook.getState() == EntityGrapplingHook.STATE_FLYING)
                    {
                        hook.setState(EntityGrapplingHook.STATE_RETRACTING);
                    }
                }
            });
            return null;
        }
    }
}
