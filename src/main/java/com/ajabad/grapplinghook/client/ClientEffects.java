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
 * <p>Applied via {@link FOVUpdateEvent}, whose value vanilla feeds through a
 * 0.5/tick smoothing filter (then clamps to 1.5x). That filter only swells the
 * FoV if the punch stays high for several ticks, so {@link Tuning#FOV_PUNCH_DECAY}
 * is deliberately slow: a punch that decays faster than the filter can climb
 * collapses before it's ever visible (which is exactly what made an earlier,
 * fast-decaying version look like it did nothing).
 */
@SideOnly(Side.CLIENT)
public final class ClientEffects
{
    public static final ClientEffects INSTANCE = new ClientEffects();

    private double fovPunch; // current FoV-multiplier bonus

    private ClientEffects() {}

    /** Trigger the yank FoV punch, scaled by yank speed but never below a floor. */
    public void onYank(double speed)
    {
        double frac = Math.min(1.0D, speed / Tuning.FOV_PUNCH_REF_SPEED);
        this.fovPunch = Tuning.FOV_PUNCH_MIN + frac * (Tuning.FOV_PUNCH_MAX - Tuning.FOV_PUNCH_MIN);
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
