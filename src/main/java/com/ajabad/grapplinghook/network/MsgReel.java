package com.ajabad.grapplinghook.network;

import com.ajabad.grapplinghook.entity.EntityGrapplingHook;
import com.ajabad.grapplinghook.event.ServerEventHandler;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Client -&gt; server: the player started or stopped holding the use key while a hook
 * is latched onto a mob. The latch drag runs server-side (it moves another entity),
 * so the reel intent has to be reported rather than read from local key state. Sent
 * only on transitions (and once when a latch forms with the key already held), so the
 * server's per-mob reeling flag stays in sync without a packet every tick.
 */
public class MsgReel implements IMessage
{
    private boolean reeling;

    public MsgReel() {}

    public MsgReel(boolean reeling) { this.reeling = reeling; }

    @Override
    public void fromBytes(ByteBuf buf) { this.reeling = buf.readBoolean(); }

    @Override
    public void toBytes(ByteBuf buf) { buf.writeBoolean(this.reeling); }

    public static class Handler implements IMessageHandler<MsgReel, IMessage>
    {
        @Override
        public IMessage onMessage(final MsgReel msg, MessageContext ctx)
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
                        hook.setReeling(msg.reeling);
                    }
                }
            });
            return null;
        }
    }
}
