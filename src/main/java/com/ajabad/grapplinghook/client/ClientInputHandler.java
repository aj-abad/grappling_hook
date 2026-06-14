package com.ajabad.grapplinghook.client;

import com.ajabad.grapplinghook.item.ItemGrapplingHook;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.MouseEvent;

/**
 * Routes client input into the {@link GrappleController}: the per-tick physics
 * step on {@link TickEvent.ClientTickEvent}, and the left-click intent (yank /
 * retract) on Forge's {@link MouseEvent}.
 *
 * <p>Reel (hold use key) and mid-air jump-release are read directly from key-bind
 * state inside the controller's tick, so they aren't handled here.
 */
@SideOnly(Side.CLIENT)
public class ClientInputHandler
{
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;
        GrappleController.INSTANCE.onClientTick();
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event)
    {
        if (event.button != 0 || !event.buttonstate) return; // left-button press only

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || !GrappleController.INSTANCE.hasActiveHook()) return;

        ItemStack held = mc.thePlayer.getCurrentEquippedItem();
        if (held == null || !(held.getItem() instanceof ItemGrapplingHook)) return;

        GrappleController.INSTANCE.onLeftClick();
        event.setCanceled(true); // suppress the vanilla attack swing
    }
}
