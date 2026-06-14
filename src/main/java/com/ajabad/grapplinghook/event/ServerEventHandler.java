package com.ajabad.grapplinghook.event;

import java.util.concurrent.ConcurrentLinkedQueue;

import com.ajabad.grapplinghook.entity.EntityGrapplingHook;
import com.ajabad.grapplinghook.item.ItemGrapplingHook;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

/**
 * Server-side glue. Network message handlers run on the Netty thread and must not
 * touch world state directly (this 1.7.10 build predates {@code IThreadListener}),
 * so they enqueue work here via {@link #schedule} and it is drained on the main
 * server thread each {@link TickEvent.ServerTickEvent}.
 *
 * <p>Tear-down event hooks (death/toss/disconnect) are also registered on this
 * object; see the {@code @SubscribeEvent} methods.
 */
public final class ServerEventHandler
{
    private static final ConcurrentLinkedQueue<Runnable> TASKS = new ConcurrentLinkedQueue<Runnable>();

    /** Queue an action to run on the main server thread next tick. Thread-safe. */
    public static void schedule(Runnable task)
    {
        TASKS.add(task);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;
        Runnable task;
        while ((task = TASKS.poll()) != null)
        {
            task.run();
        }
    }

    // --- Tear-down: drop / death / disconnect all kill the tether ------------

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event)
    {
        if (event.entityLiving instanceof EntityPlayer)
        {
            tearDown((EntityPlayer) event.entityLiving);
        }
    }

    @SubscribeEvent
    public void onItemToss(ItemTossEvent event)
    {
        ItemStack stack = event.entityItem.getEntityItem();
        if (stack == null || !(stack.getItem() instanceof ItemGrapplingHook)) return;

        tearDown(event.player);
        // The dropped item should land in the world primed, not stuck "fired".
        if (stack.getItemDamage() == ItemGrapplingHook.META_FIRED)
        {
            stack.setItemDamage(ItemGrapplingHook.META_PRIMED);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        tearDown(event.player);
    }

    /** Remove the player's hook and restore the surviving stack to primed. */
    private static void tearDown(EntityPlayer player)
    {
        if (player == null || player.worldObj == null || player.worldObj.isRemote) return;
        EntityGrapplingHook hook = EntityGrapplingHook.findForPlayer(player);
        if (hook != null) hook.killAndResetStack();
    }
}
