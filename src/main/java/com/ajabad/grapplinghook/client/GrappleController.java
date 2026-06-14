package com.ajabad.grapplinghook.client;

import java.util.ArrayList;

import com.ajabad.grapplinghook.Tuning;
import com.ajabad.grapplinghook.entity.EntityGrapplingHook;
import com.ajabad.grapplinghook.item.ItemGrapplingHook;
import com.ajabad.grapplinghook.network.ModNetwork;
import com.ajabad.grapplinghook.network.MsgRelease;
import com.ajabad.grapplinghook.network.MsgRetract;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Vec3;

/**
 * Client-side tether physics for the local player. Runs each client tick after
 * the player has moved, then corrects the player against the rope:
 * <ul>
 *   <li>constrains the player to a sphere of {@code activeLength} around the
 *       active pivot, removing only the outward velocity (so gravity turns into a
 *       swing);</li>
 *   <li>lets WASD add a tangential push to pump/steer the swing;</li>
 *   <li>reels the cable in while the use key is held;</li>
 *   <li>yanks toward the anchor on a left click, or retracts a still-flying
 *       hook;</li>
 *   <li>releases with a momentum boost on a mid-air jump.</li>
 * </ul>
 * The hook entity itself is server-authoritative; this only moves the local
 * player and reports release/retract intents back over the network.
 */
@SideOnly(Side.CLIENT)
public final class GrappleController
{
    public static final GrappleController INSTANCE = new GrappleController();

    private final CableModel cable = new CableModel();
    private EntityGrapplingHook hook;
    private boolean stuckInitialized;
    private boolean prevJumpDown;
    private int suppressedHookId = -1; // a released hook awaiting despawn

    // Yank flight: after a left-click yank the hook is gone and the player is
    // sailing toward the anchor; during this window jump performs the launch.
    private boolean yanking;
    private int yankTicks;

    private GrappleController() {}

    public boolean hasActiveHook() { return this.hook != null && !this.hook.isDead; }

    public void onClientTick()
    {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null) { reset(); return; }

        boolean jumpDown = mc.gameSettings.keyBindJump.getIsKeyPressed();

        // Yank flight: the hook is already gone; the only special input left is the
        // jump-launch. (This is the ONLY state where jump launches.) Firing a fresh
        // hook cancels the flight so the new grapple takes over at once.
        if (this.yanking)
        {
            EntityGrapplingHook fresh = EntityGrapplingHook.findForPlayer(player);
            if (fresh == null || fresh.getEntityId() == this.suppressedHookId)
            {
                boolean jumpEdge = jumpDown && !this.prevJumpDown;
                this.prevJumpDown = jumpDown;
                tickYank(player, jumpEdge);
                return;
            }
            endYank(); // a new hook exists; fall through to normal handling
        }
        this.prevJumpDown = jumpDown;

        this.hook = EntityGrapplingHook.findForPlayer(player);
        if (this.hook == null) { reset(); return; }

        // A hook we just released from: do nothing until it despawns.
        if (this.hook.getEntityId() == this.suppressedHookId)
        {
            this.hook.renderPivots = null;
            return;
        }

        if (this.hook.getState() != EntityGrapplingHook.STATE_STUCK)
        {
            this.stuckInitialized = false;
            this.hook.renderPivots = null;
            return;
        }

        ensureStuckCable(player);

        if (mc.gameSettings.keyBindUseItem.getIsKeyPressed())
        {
            applyReel();
        }

        // Re-shape the cord around ledges/corners, then enforce it.
        this.cable.update(player.worldObj, tetherPoint(player));

        applyConstraintAndSwing(player);

        // Report slack (max distance minus current distance) so the cord sags only
        // when the rope isn't taut, simulating one consistent cable length.
        double activeDist = CableModel.dist(tetherPoint(player), this.cable.activePivot());
        this.hook.renderSlack = Math.max(0.0D, this.cable.activeLength() - activeDist);
        this.hook.renderPivots = new ArrayList<Vec3>(this.cable.pivots);
    }

    /** Left mouse while a hook is active: yank toward the anchor, or retract a flying hook. */
    public void onLeftClick()
    {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;
        this.hook = EntityGrapplingHook.findForPlayer(player);
        if (this.hook == null) return;

        byte state = this.hook.getState();
        if (state == EntityGrapplingHook.STATE_FLYING)
        {
            ModNetwork.CHANNEL.sendToServer(new MsgRetract());
        }
        else if (state == EntityGrapplingHook.STATE_STUCK && !player.onGround)
        {
            // Yank only works mid-air.
            ensureStuckCable(player);
            yank(player);
        }
    }

    private void ensureStuckCable(EntityPlayer player)
    {
        Vec3 anchor = Vec3.createVectorHelper(this.hook.posX, this.hook.posY, this.hook.posZ);
        if (!this.stuckInitialized)
        {
            Vec3 tp = tetherPoint(player);
            double len = CableModel.dist(tp, anchor) + Tuning.LEEWAY;
            this.cable.init(anchor, len);
            this.stuckInitialized = true;
        }
        else
        {
            this.cable.pivots.set(0, anchor); // anchor is static, but keep it exact
        }
    }

    /** The point on the player the rope effectively attaches to (body centre). */
    private static Vec3 tetherPoint(EntityPlayer player)
    {
        return Vec3.createVectorHelper(player.posX, player.posY + player.height * 0.5D, player.posZ);
    }

    private void applyReel()
    {
        double min = this.cable.consumedLength() + Tuning.MIN_ACTIVE_LENGTH;
        if (this.cable.cableLength > min)
        {
            this.cable.cableLength = Math.max(min, this.cable.cableLength - Tuning.REEL_SPEED);
        }
    }

    private void applyConstraintAndSwing(EntityPlayer player)
    {
        Vec3 p = this.cable.activePivot();
        double length = this.cable.activeLength();
        double offY = player.height * 0.5D; // tether point is the body centre
        double dx = player.posX - p.xCoord;
        double dy = (player.posY + offY) - p.yCoord;
        double dz = player.posZ - p.zCoord;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist <= 1.0E-4D || dist <= length) return; // slack rope: free movement

        double nx = dx / dist, ny = dy / dist, nz = dz / dist;
        // Move the body centre back onto the sphere, then convert to a feet target.
        // Use moveEntity (not setPosition) so block collision is respected and the
        // pull can't drag the player into a wall when scaling it vertically.
        double targetX = p.xCoord + nx * length;
        double targetY = p.yCoord + ny * length - offY;
        double targetZ = p.zCoord + nz * length;
        player.moveEntity(targetX - player.posX, targetY - player.posY, targetZ - player.posZ);

        double outward = player.motionX * nx + player.motionY * ny + player.motionZ * nz;
        if (outward > 0.0D)
        {
            player.motionX -= nx * outward;
            player.motionY -= ny * outward;
            player.motionZ -= nz * outward;
        }
        player.onGround = false;
        player.fallDistance = 0.0F;

        applySwing(player, nx, ny, nz);
    }

    /** Add a tangential push from WASD so the player can pump and steer the swing. */
    private void applySwing(EntityPlayer player, double nx, double ny, double nz)
    {
        float forward = player.moveForward;
        float strafe = player.moveStrafing;
        if (forward == 0.0F && strafe == 0.0F) return;

        double yaw = Math.toRadians(player.rotationYaw);
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        // World displacement of (strafe, forward) input, matching vanilla moveFlying.
        double dx = strafe * cos - forward * sin;
        double dz = forward * cos + strafe * sin;
        double dlen = Math.sqrt(dx * dx + dz * dz);
        if (dlen < 1.0E-4D) return;
        dx /= dlen; dz /= dlen;

        // Remove the radial component so only the tangential push remains.
        double radial = dx * nx + dz * nz; // input has no vertical component
        double tx = dx - nx * radial;
        double ty = -ny * radial;
        double tz = dz - nz * radial;
        double tlen = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tlen < 1.0E-4D) return;
        tx /= tlen; ty /= tlen; tz /= tlen;

        player.motionX += tx * Tuning.SWING_ACCEL;
        player.motionY += ty * Tuning.SWING_ACCEL;
        player.motionZ += tz * Tuning.SWING_ACCEL;
    }

    /** Forcefully fling the player toward the anchor and disconnect the hook. */
    private void yank(EntityPlayer player)
    {
        Vec3 p = this.cable.activePivot();
        Vec3 tp = tetherPoint(player);
        double dx = p.xCoord - tp.xCoord;
        double dy = p.yCoord - tp.yCoord;
        double dz = p.zCoord - tp.zCoord;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-4D) return;

        // Velocity points straight at the anchor; force scales with cable length.
        double speed = Math.min(Tuning.YANK_K * this.cable.cableLength, Tuning.YANK_MAX_SPEED);
        player.motionX = dx / dist * speed;
        player.motionY = dy / dist * speed;
        player.motionZ = dz / dist * speed;
        player.fallDistance = 0.0F;

        // Instantly disconnect: prime the item now, have the server drop the hook,
        // and enter the free yank-flight state (where jump can launch).
        primeHeldHookLocally(player);
        ModNetwork.CHANNEL.sendToServer(new MsgRelease());
        this.suppressedHookId = this.hook.getEntityId();
        this.hook.renderPivots = null;
        this.stuckInitialized = false;
        this.yanking = true;
        this.yankTicks = 0;
    }

    /** During yank flight, jump launches; otherwise the flight just times out. */
    private void tickYank(EntityPlayer player, boolean jumpEdge)
    {
        if (jumpEdge)
        {
            double hx = player.motionX, hz = player.motionZ;
            double hlen = Math.sqrt(hx * hx + hz * hz);
            if (hlen > 1.0E-4D)
            {
                player.motionX += hx / hlen * Tuning.JUMP_BOOST;
                player.motionZ += hz / hlen * Tuning.JUMP_BOOST;
            }
            player.motionY += Tuning.JUMP_UP;
            player.fallDistance = 0.0F;
            endYank();
            return;
        }

        player.fallDistance = 0.0F; // the yank itself shouldn't cause fall damage
        if (++this.yankTicks > Tuning.YANK_FLIGHT_TICKS || player.onGround)
        {
            endYank();
        }
    }

    private void endYank()
    {
        this.yanking = false;
        this.yankTicks = 0;
    }

    private static void primeHeldHookLocally(EntityPlayer player)
    {
        ItemStack held = player.getCurrentEquippedItem();
        if (held != null && held.getItem() instanceof ItemGrapplingHook
                && held.getItemDamage() == ItemGrapplingHook.META_FIRED)
        {
            held.setItemDamage(ItemGrapplingHook.META_PRIMED);
        }
    }

    private void reset()
    {
        this.hook = null;
        this.stuckInitialized = false;
        this.suppressedHookId = -1;
        this.yanking = false;
        this.yankTicks = 0;
        this.cable.pivots.clear();
    }
}
