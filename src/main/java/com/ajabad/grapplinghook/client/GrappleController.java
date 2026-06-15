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
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import org.lwjgl.input.Keyboard;

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
    private boolean prevOnGround;
    private boolean prevDisconnectDown;
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
        boolean jumpEdge = jumpDown && !this.prevJumpDown;
        this.prevJumpDown = jumpDown;

        // "Mid-air" means airborne this tick AND last tick. This rejects the tick a
        // jump leaves the ground (where onGround is already false but we were just
        // standing), so a normal ground jump never triggers a swing/wall jump.
        boolean midAir = !player.onGround && !this.prevOnGround;
        this.prevOnGround = player.onGround;

        // Disconnect on a fresh key press (tracked every tick so holding the key
        // before firing doesn't instantly drop the new hook).
        boolean disconnectDown = mc.inGameHasFocus
                && Keyboard.isKeyDown(ModKeys.DISCONNECT_KEY);
        boolean disconnectEdge = disconnectDown && !this.prevDisconnectDown;
        this.prevDisconnectDown = disconnectDown;

        // Yank flight: the hook is already gone; the only special input left is the
        // jump-launch. Firing a fresh hook cancels the flight so the new grapple
        // takes over at once.
        if (this.yanking)
        {
            EntityGrapplingHook fresh = EntityGrapplingHook.findForPlayer(player);
            if (fresh == null || fresh.getEntityId() == this.suppressedHookId)
            {
                tickYank(player, jumpEdge);
                return;
            }
            endYank(); // a new hook exists; fall through to normal handling
        }

        this.hook = EntityGrapplingHook.findForPlayer(player);
        if (this.hook == null) { reset(); return; }

        // A hook we just released from: do nothing until it despawns.
        if (this.hook.getEntityId() == this.suppressedHookId)
        {
            this.hook.renderPivots = null;
            return;
        }

        // Clean instant drop (no added momentum), in any state — good for stepping
        // off onto a ledge while scaling a wall.
        if (disconnectEdge)
        {
            disconnect(player);
            return;
        }

        if (this.hook.getState() != EntityGrapplingHook.STATE_STUCK)
        {
            this.stuckInitialized = false;
            this.hook.renderPivots = null;
            return;
        }

        ensureStuckCable(player);

        // Jump handling (mid-air only). The player's facing disambiguates intent:
        // looking into any adjacent wall -- in a corner, either of them, not just the
        // anchor's -- => wall jump, kicking off that wall with the cable still
        // attached; looking away while actually swinging => swing jump (release with a
        // boost). A jump in between does nothing -- use the Disconnect key to drop a
        // taut, motionless rope.
        boolean wallJumped = false;
        if (jumpEdge && midAir)
        {
            double[] wallNormal = facedWallNormal(player);
            if (wallNormal != null)
            {
                wallJump(player, wallNormal[0], wallNormal[1]); // kick off the faced wall
                wallJumped = true;
            }
            else if (isTaut(player) && swingSpeed(player) >= Tuning.SWING_JUMP_MIN_SPEED)
            {
                swingJump(player); // release with a boost off a real swing
                return;
            }
        }

        if (mc.gameSettings.keyBindUseItem.getIsKeyPressed())
        {
            applyReel();
        }
        else if (mc.inGameHasFocus && Keyboard.isKeyDown(ModKeys.EXTEND.getKeyCode()))
        {
            // Read the physical key (respecting any rebind) rather than the keybind's
            // pressed flag, which another mod's keycode collision can swallow.
            applyExtend(player);
        }

        if (!wallJumped)
        {
            // Re-shape the cord around ledges/corners, then enforce it.
            this.cable.update(player.worldObj, tetherPoint(player));
            applyConstraintAndSwing(player);
        }

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
        else if (state == EntityGrapplingHook.STATE_STUCK)
        {
            // Yank works from the ground as well as mid-air; the upward aim
            // (YANK_RISE) gives it enough lift to leave the ground.
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

    private void applyExtend(EntityPlayer player)
    {
        if (this.cable.cableLength >= Tuning.MAX_CABLE_LENGTH) return;

        // Only rappel (actively pay the player out) while hanging taut in the air
        // under gravity — not on the ground, and not while creative-flying (a flying
        // player shouldn't be rope-driven). Otherwise just bank the extra length as
        // slack instead of shoving the player away from the anchor.
        boolean rappel = isTaut(player) && !player.onGround && !player.capabilities.isFlying;
        this.cable.cableLength = Math.min(Tuning.MAX_CABLE_LENGTH,
                this.cable.cableLength + Tuning.EXTEND_SPEED);
        if (!rappel) return;

        Vec3 p = this.cable.activePivot();
        double offY = player.height * 0.5D;
        double dx = player.posX - p.xCoord;
        double dy = (player.posY + offY) - p.yCoord;
        double dz = player.posZ - p.zCoord;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-4D) return;

        double nx = dx / dist, ny = dy / dist, nz = dz / dist;

        // Only pay out when it actually lowers the player (the rope points downward).
        // If they're at or above the anchor, don't shove them up/out — leave the slack.
        if (ny >= 0.0D) return;

        // Pay the player out onto the new, larger sphere and damp the outward velocity
        // so the descent is a smooth fixed rate (mirrors reel-in, which the constraint
        // pulls inward; the constraint itself never pushes outward).
        double length = this.cable.activeLength();
        player.moveEntity(p.xCoord + nx * length - player.posX,
                p.yCoord + ny * length - offY - player.posY,
                p.zCoord + nz * length - player.posZ);

        double outward = player.motionX * nx + player.motionY * ny + player.motionZ * nz;
        if (outward > 0.0D)
        {
            player.motionX -= nx * outward;
            player.motionY -= ny * outward;
            player.motionZ -= nz * outward;
        }
        player.fallDistance = 0.0F;
    }

    private void applyConstraintAndSwing(EntityPlayer player)
    {
        boolean onGround = player.onGround;
        Vec3 p = this.cable.activePivot();
        double length = this.cable.activeLength();
        double offY = player.height * 0.5D; // tether point is the body centre
        double dx = player.posX - p.xCoord;
        double dy = (player.posY + offY) - p.yCoord;
        double dz = player.posZ - p.zCoord;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist <= 1.0E-4D || dist <= length) return; // slack rope: free movement

        double nx = dx / dist, ny = dy / dist, nz = dz / dist;
        // Clamp the body centre back onto the sphere (converted to a feet target).
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

        // Airborne: this is a swing. On the ground the rope simply caps how far the
        // player can move (no swing pump, and don't disturb their grounded state).
        if (!onGround)
        {
            player.onGround = false;
            player.fallDistance = 0.0F;
            applySwing(player, nx, ny, nz);
        }
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
        // Aim a little above the anchor so the player arcs up and over lips/ledges.
        double dy = (p.yCoord + Tuning.YANK_RISE) - tp.yCoord;
        double dz = p.zCoord - tp.zCoord;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-4D) return;

        // Velocity points at the (raised) anchor; force scales with the TAUT length
        // only -- the cable pulled straight from the anchor to the player (the
        // already-wrapped segments, always taut, plus the active span's real
        // straight-line extent). Spooled-out slack is excluded, so paying cable out
        // without moving away from the anchor doesn't inflate the yank.
        double tautLength = this.cable.consumedLength() + CableModel.dist(tp, p);
        double speed = Math.min(Tuning.YANK_K * tautLength, Tuning.YANK_MAX_SPEED);
        player.motionX = dx / dist * speed;
        player.motionY = dy / dist * speed;
        player.motionZ = dz / dist * speed;
        player.fallDistance = 0.0F;
        ClientEffects.INSTANCE.onYank(speed); // cosmetic FoV punch, scaled by force

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
            launchBoost(player);
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

    /** Is the rope at (near) full extension for the active span? */
    private boolean isTaut(EntityPlayer player)
    {
        double activeDist = CableModel.dist(tetherPoint(player), this.cable.activePivot());
        return activeDist >= this.cable.activeLength() - Tuning.TAUT_EPSILON;
    }

    /**
     * The outward normal {nx, nz} of the solid wall the player is looking into, or
     * null if they aren't facing an adjacent wall. Probes all four cardinal
     * directions so that in a corner the player can kick off whichever wall they
     * face -- not only the one the anchor sits on. When more than one qualifies
     * (looking straight into the corner) the most directly-faced wall wins.
     */
    private double[] facedWallNormal(EntityPlayer player)
    {
        // Horizontal forward look (matches the moveFlying decomposition in applySwing).
        double yaw = Math.toRadians(player.rotationYaw);
        double lookX = -Math.sin(yaw);
        double lookZ = Math.cos(yaw);

        int[][] inward = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        double bestDot = Tuning.WALL_JUMP_FACING_DOT;
        double outX = 0.0D, outZ = 0.0D;
        boolean found = false;
        for (int[] d : inward)
        {
            double dot = lookX * d[0] + lookZ * d[1];
            if (dot < bestDot) continue;                     // not looking into this wall
            if (!wallAdjacent(player, d[0], d[1])) continue; // no solid block there
            bestDot = dot;
            outX = -d[0]; outZ = -d[1];                      // push points away from it
            found = true;
        }
        return found ? new double[] {outX, outZ} : null;
    }

    /** True if a solid block sits just past the player's box in the (dx,dz) direction. */
    private boolean wallAdjacent(EntityPlayer player, double dx, double dz)
    {
        // Probe a third of a block past the box edge so a wall the player is pressed
        // against (or held just shy of by the rope) registers. func_147461_a returns
        // only block collision boxes, so a passing entity can't be wall-jumped off.
        double probe = 0.3D;
        AxisAlignedBB box = player.boundingBox.getOffsetBoundingBox(dx * probe, 0.0D, dz * probe);
        return !player.worldObj.func_147461_a(box).isEmpty();
    }

    /** The player's speed tangential to the rope -- i.e. how fast they're swinging. */
    private double swingSpeed(EntityPlayer player)
    {
        Vec3 p = this.cable.activePivot();
        double offY = player.height * 0.5D;
        double dx = player.posX - p.xCoord;
        double dy = (player.posY + offY) - p.yCoord;
        double dz = player.posZ - p.zCoord;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-4D) return 0.0D;

        double nx = dx / dist, ny = dy / dist, nz = dz / dist;
        double radial = player.motionX * nx + player.motionY * ny + player.motionZ * nz;
        double tx = player.motionX - nx * radial;
        double ty = player.motionY - ny * radial;
        double tz = player.motionZ - nz * radial;
        return Math.sqrt(tx * tx + ty * ty + tz * tz);
    }

    /** Jump off a taut swing: launch with a boost and disconnect the hook. */
    private void swingJump(EntityPlayer player)
    {
        launchBoost(player);
        primeHeldHookLocally(player);
        ModNetwork.CHANNEL.sendToServer(new MsgRelease());
        this.suppressedHookId = this.hook.getEntityId();
        this.hook.renderPivots = null;
        this.stuckInitialized = false;
    }

    /** Drop the hook immediately, leaving the player's momentum untouched. */
    private void disconnect(EntityPlayer player)
    {
        primeHeldHookLocally(player);
        ModNetwork.CHANNEL.sendToServer(new MsgRelease());
        this.suppressedHookId = this.hook.getEntityId();
        this.hook.renderPivots = null;
        this.stuckInitialized = false;
    }

    /** Add momentum along the current heading plus an upward pop. */
    private void launchBoost(EntityPlayer player)
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

    /**
     * Kick off the faced wall along its outward normal (nx, nz): an away-from-wall +
     * up impulse. The cable stays attached, so the constraint turns this into an arc
     * around the fulcrum (the up component slackens the rope so the arc is free). The
     * normal is a unit cardinal vector, so it needs no scaling.
     */
    private void wallJump(EntityPlayer player, double nx, double nz)
    {
        player.motionX = nx * Tuning.WALL_JUMP_H;
        player.motionZ = nz * Tuning.WALL_JUMP_H;
        player.motionY = Tuning.WALL_JUMP_UP;
        player.onGround = false;
        player.fallDistance = 0.0F;
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
