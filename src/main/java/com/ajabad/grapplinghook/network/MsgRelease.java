package com.ajabad.grapplinghook.network;

import com.ajabad.grapplinghook.entity.EntityGrapplingHook;
import com.ajabad.grapplinghook.event.ServerEventHandler;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Client -> server: the player jumped off a swing to release the hook. The
 * momentum boost is applied client-side; the server just removes the hook entity
 * and restores the stack to its primed state.
 */
public class MsgRelease implements IMessage
{
    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<MsgRelease, IMessage>
    {
        @Override
        public IMessage onMessage(MsgRelease msg, MessageContext ctx)
        {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            ServerEventHandler.schedule(new Runnable()
            {
                @Override
                public void run()
                {
                    EntityGrapplingHook hook = EntityGrapplingHook.findForPlayer(player);
                    if (hook != null)
                    {
                        hook.killAndResetStack();
                    }
                }
            });
            return null;
        }
    }
}
