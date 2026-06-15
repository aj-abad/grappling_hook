package com.ajabad.grapplinghook.client;

import com.ajabad.grapplinghook.Tuning;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import net.minecraftforge.client.event.FOVUpdateEvent;

/**
 * Cosmetic client-side camera feedback: a brief FoV widening ("punch") on a
 * left-click yank, scaled by the yank's force and decaying back to zero.
 *
 * <p>It's applied via {@link FOVUpdateEvent}, whose value vanilla runs through a
 * smoothed (~0.5/tick lerp), clamped multiplier ({@code fovModifierHand}). The
 * punch is therefore intentionally over-driven so the smoothed result reads
 * clearly rather than washing out.
 */
@SideOnly(Side.CLIENT)
public final class ClientEffects
{
    public static final ClientEffects INSTANCE = new ClientEffects();

    private double fovPunch; // current FoV-multiplier bonus

    private ClientEffects() {}

    /** Trigger the yank FoV punch, scaled by the yank speed. */
    public void onYank(double speed)
    {
        double frac = Math.min(1.0D, speed / Tuning.YANK_MAX_SPEED);
        this.fovPunch = frac * Tuning.FOV_PUNCH_MAX;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;
        this.fovPunch *= Tuning.FOV_PUNCH_DECAY;
        if (this.fovPunch < 0.001D) this.fovPunch = 0.0D;
    }

    @SubscribeEvent
    public void onFOV(FOVUpdateEvent event)
    {
        if (this.fovPunch != 0.0D) event.newfov += (float) this.fovPunch;
    }
}
