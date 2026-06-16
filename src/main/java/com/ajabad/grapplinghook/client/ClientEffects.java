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
 * <p>Applied via {@link FOVUpdateEvent}, whose value vanilla treats as the
 * <em>target</em> of a 0.5/tick smoothing filter (the rendered FoV chases it,
 * halving the gap each tick) and clamps to 1.5x. A one-tick spike barely moves
 * the rendered FoV, so the punch is <b>held</b> at full strength for
 * {@link Tuning#FOV_PUNCH_HOLD_TICKS} ticks -- long enough for the filter to climb
 * to the clamp -- and only then decayed ({@link Tuning#FOV_PUNCH_DECAY}) for a
 * smooth fade. (Decaying from the first tick raced the filter and collapsed the
 * punch before it was ever visible, which is what made the effect look dead.)
 */
@SideOnly(Side.CLIENT)
public final class ClientEffects
{
    public static final ClientEffects INSTANCE = new ClientEffects();

    private double fovPunch; // current FoV-multiplier bonus
    private int holdTicks;   // ticks remaining to pin fovPunch at its peak before decaying

    private ClientEffects() {}

    /** Trigger the yank FoV punch, scaled by yank speed but never below a floor. */
    public void onYank(double speed)
    {
        double frac = Math.min(1.0D, speed / Tuning.FOV_PUNCH_REF_SPEED);
        this.fovPunch = Tuning.FOV_PUNCH_MIN + frac * (Tuning.FOV_PUNCH_MAX - Tuning.FOV_PUNCH_MIN);
        this.holdTicks = Tuning.FOV_PUNCH_HOLD_TICKS;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || this.fovPunch == 0.0D) return;
        // Hold the punch at its peak so the 0.5/tick FoV filter has time to climb to it,
        // then decay for a smooth fade-out.
        if (this.holdTicks > 0)
        {
            this.holdTicks--;
            return;
        }
        this.fovPunch *= Tuning.FOV_PUNCH_DECAY;
        if (this.fovPunch < 0.001D) this.fovPunch = 0.0D;
    }

    @SubscribeEvent
    public void onFOV(FOVUpdateEvent event)
    {
        if (this.fovPunch != 0.0D) event.newfov += (float) this.fovPunch;
    }
}
