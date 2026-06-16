package com.ajabad.grapplinghook.entity;

import java.util.List;

import com.ajabad.grapplinghook.Tuning;
import com.ajabad.grapplinghook.item.ItemGrapplingHook;

import cpw.mods.fml.common.registry.IEntityAdditionalSpawnData;
import io.netty.buffer.ByteBuf;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/**
 * The fired grappling-hook projectile and the server-authoritative anchor of the
 * tether. Flight, block impact, and the return-to-hand retract all run on the
 * server; clients receive the position through ordinary entity tracking.
 *
 * <p>The actual swing/constraint physics for the local player live client-side
 * (see {@code com.ajabad.grapplinghook.client.GrappleController}); this entity is
 * the shared, synced anchor those physics hang off of.
 */
public class EntityGrapplingHook extends Entity implements IEntityAdditionalSpawnData
{
    public static final byte STATE_FLYING = 0;
    public static final byte STATE_STUCK = 1;
    public static final byte STATE_RETRACTING = 2;
    /** Hook bit into a living mob and is now a leash dragging it (see {@link #tickLatched}). */
    public static final byte STATE_LATCHED = 3;

    private static final int DW_STATE = 16;
    private static final int DW_OWNER = 17;
    private static final int DW_ANCHOR_X = 18;
    private static final int DW_ANCHOR_Y = 19;
    private static final int DW_ANCHOR_Z = 20;
    private static final int DW_TARGET = 21; // latched mob's entity id, or -1

    private int ticksInAir;

    /**
     * Server-side leash budget while {@link #STATE_LATCHED}: the mob is kept within
     * this straight-line distance of the player, and holding the use key shrinks it.
     * Not synced -- the latch physics are server-authoritative.
     */
    private double cableLength;

    /** Server-side: the player is holding the use key to reel the latched mob in. */
    private boolean reeling;

    /**
     * Client-only render data: the cord pivots from anchor (index 0) to the
     * active pivot (last). {@code null} or empty means draw a straight cord from
     * the hand to the hook. Populated by the controller (local player) or by
     * rope-sync packets (remote players).
     */
    public List<Vec3> renderPivots;

    /**
     * Client-only: slack in the active (player-side) span, in blocks. Drives how
     * much the cord sags. Set by the controller while the local player is tethered;
     * ignored when {@link #renderPivots} is null (in-flight or remote cord).
     */
    public double renderSlack;

    /**
     * Ticks spent in {@link #STATE_RETRACTING}, reset to 0 in any other state. Lets
     * the renderer ease the in-flight cord from drooped to taut as the hook reels in,
     * rather than snapping it straight the instant retract begins.
     */
    public int retractTicks;

    /**
     * Ticks spent in {@link #STATE_STUCK}, reset to 0 in any other state. Lets the
     * renderer ease the cord from its loose in-flight droop to the settled,
     * slack-derived sag when the hook first anchors, rather than popping it.
     */
    public int stuckTicks;

    public EntityGrapplingHook(World world)
    {
        super(world);
        this.setSize(0.4F, 0.4F);
        this.renderDistanceWeight = 10.0D;
        this.ignoreFrustumCheck = true; // anchor is often off-screen while swinging
    }

    public EntityGrapplingHook(World world, EntityPlayer owner)
    {
        this(world);
        this.setLocationAndAngles(owner.posX, owner.posY + owner.getEyeHeight() - 0.1D,
                owner.posZ, owner.rotationYaw, owner.rotationPitch);
        // Nudge out of the player's face, like the vanilla arrow spawn.
        this.posX -= MathHelper.cos(this.rotationYaw / 180.0F * (float) Math.PI) * 0.16F;
        this.posZ -= MathHelper.sin(this.rotationYaw / 180.0F * (float) Math.PI) * 0.16F;
        this.setPosition(this.posX, this.posY, this.posZ);
        this.dataWatcher.updateObject(DW_OWNER, Integer.valueOf(owner.getEntityId()));

        double mx = -MathHelper.sin(this.rotationYaw / 180.0F * (float) Math.PI)
                * MathHelper.cos(this.rotationPitch / 180.0F * (float) Math.PI);
        double mz = MathHelper.cos(this.rotationYaw / 180.0F * (float) Math.PI)
                * MathHelper.cos(this.rotationPitch / 180.0F * (float) Math.PI);
        double my = -MathHelper.sin(this.rotationPitch / 180.0F * (float) Math.PI);
        setHeading(mx, my, mz, Tuning.LAUNCH_SPEED);
    }

    private void setHeading(double x, double y, double z, double speed)
    {
        double mag = Math.sqrt(x * x + y * y + z * z);
        if (mag < 1.0E-7D) return;
        this.motionX = x / mag * speed;
        this.motionY = y / mag * speed;
        this.motionZ = z / mag * speed;
        updateRotationFromMotion();
        this.prevRotationYaw = this.rotationYaw;
        this.prevRotationPitch = this.rotationPitch;
    }

    @Override
    protected void entityInit()
    {
        this.dataWatcher.addObject(DW_STATE, Byte.valueOf(STATE_FLYING));
        this.dataWatcher.addObject(DW_OWNER, Integer.valueOf(-1));
        // Authoritative anchor, synced on stick. A motionless stuck entity sends no
        // position packets, so we cannot rely on entity tracking for the anchor.
        this.dataWatcher.addObject(DW_ANCHOR_X, Float.valueOf(0.0F));
        this.dataWatcher.addObject(DW_ANCHOR_Y, Float.valueOf(0.0F));
        this.dataWatcher.addObject(DW_ANCHOR_Z, Float.valueOf(0.0F));
        // Synced so every client can hang the cord's far end on the mob locally.
        this.dataWatcher.addObject(DW_TARGET, Integer.valueOf(-1));
    }

    public byte getState() { return this.dataWatcher.getWatchableObjectByte(DW_STATE); }

    public void setState(byte state) { this.dataWatcher.updateObject(DW_STATE, Byte.valueOf(state)); }

    public int getOwnerId() { return this.dataWatcher.getWatchableObjectInt(DW_OWNER); }

    public EntityPlayer getOwner()
    {
        Entity e = this.worldObj.getEntityByID(getOwnerId());
        return (e instanceof EntityPlayer) ? (EntityPlayer) e : null;
    }

    public boolean isLatched() { return getState() == STATE_LATCHED; }

    /** Server-side: set whether the player is reeling the latched mob in. */
    public void setReeling(boolean reeling) { this.reeling = reeling; }

    public int getTargetId() { return this.dataWatcher.getWatchableObjectInt(DW_TARGET); }

    /** The mob this hook is latched onto, or {@code null}. Works on either side. */
    public EntityLivingBase getTarget()
    {
        Entity e = this.worldObj.getEntityByID(getTargetId());
        return (e instanceof EntityLivingBase) ? (EntityLivingBase) e : null;
    }

    /**
     * The live hook belonging to {@code player}, or {@code null}. Works on either
     * side; only call from the main thread (it iterates the loaded-entity list).
     */
    public static EntityGrapplingHook findForPlayer(EntityPlayer player)
    {
        if (player == null) return null;
        List list = player.worldObj.loadedEntityList;
        for (int i = 0; i < list.size(); ++i)
        {
            Object o = list.get(i);
            if (o instanceof EntityGrapplingHook)
            {
                EntityGrapplingHook h = (EntityGrapplingHook) o;
                if (!h.isDead && h.getOwnerId() == player.getEntityId()) return h;
            }
        }
        return null;
    }

    /** Remove this hook and restore the owner's stack to primed (server side). */
    public void killAndResetStack()
    {
        resetOwnerStack(getOwner());
        this.setDead();
    }

    @Override
    public void onUpdate()
    {
        super.onUpdate();

        // Teardown checks are server-authoritative.
        if (!this.worldObj.isRemote)
        {
            EntityPlayer owner = getOwner();
            if (!isOwnerValid(owner))
            {
                resetOwnerStack(owner);
                this.setDead();
                return;
            }
        }

        // Flight/retract physics run on BOTH sides (like a vanilla arrow): the
        // client predicts motion for smooth rendering, the server stays the source
        // of truth for the STUCK transition and entity removal.
        switch (getState())
        {
            case STATE_FLYING:
                tickFlying();
                break;
            case STATE_STUCK:
                // Anchor is fixed; the owning client drives its own swing physics.
                this.motionX = this.motionY = this.motionZ = 0.0D;
                if (this.worldObj.isRemote)
                {
                    // Snap to the authoritative anchor: client flight prediction can
                    // stop a touch off, and the motionless entity won't be re-synced.
                    this.setPosition(
                            this.dataWatcher.getWatchableObjectFloat(DW_ANCHOR_X),
                            this.dataWatcher.getWatchableObjectFloat(DW_ANCHOR_Y),
                            this.dataWatcher.getWatchableObjectFloat(DW_ANCHOR_Z));
                }
                break;
            case STATE_RETRACTING:
                tickRetracting();
                break;
            case STATE_LATCHED:
                tickLatched();
                break;
            default:
                break;
        }

        // Count ticks spent retracting so the renderer can ease the cord taut.
        this.retractTicks = (getState() == STATE_RETRACTING) ? this.retractTicks + 1 : 0;
        // Count ticks spent stuck so the renderer can ease the cord from its loose
        // in-flight droop to the settled sag when the hook first anchors.
        this.stuckTicks = (getState() == STATE_STUCK) ? this.stuckTicks + 1 : 0;
    }

    /**
     * Client entity-tracking applies server position updates through here. While
     * STUCK the hook is deliberately embedded in the block it bit into, but vanilla
     * {@code setPositionAndRotation2} runs a collision-resolution step that ejects
     * the box up onto the block's surface. The tracker force-resyncs a motionless
     * entity periodically, so that ejection -- undone the next tick by the anchor
     * snap in {@link #onUpdate} -- shows up as a recurring vertical jitter. Pin a
     * stuck hook to its authoritative anchor instead and skip the vanilla handling;
     * the anchor is already synced via the DataWatcher.
     */
    @Override
    public void setPositionAndRotation2(double x, double y, double z, float yaw, float pitch, int inc)
    {
        if (getState() == STATE_STUCK)
        {
            this.setPosition(
                    this.dataWatcher.getWatchableObjectFloat(DW_ANCHOR_X),
                    this.dataWatcher.getWatchableObjectFloat(DW_ANCHOR_Y),
                    this.dataWatcher.getWatchableObjectFloat(DW_ANCHOR_Z));
            return;
        }
        super.setPositionAndRotation2(x, y, z, yaw, pitch, inc);
    }

    private boolean isOwnerValid(EntityPlayer owner)
    {
        if (owner == null || owner.isDead || !owner.isEntityAlive()) return false;
        ItemStack held = owner.getCurrentEquippedItem();
        if (held == null || !(held.getItem() instanceof ItemGrapplingHook)) return false;
        if (this.getDistanceSqToEntity(owner) > Tuning.MAX_RANGE_SQ) return false;
        return true;
    }

    private void tickFlying()
    {
        ++this.ticksInAir;

        Vec3 from = Vec3.createVectorHelper(this.posX, this.posY, this.posZ);
        Vec3 to = Vec3.createVectorHelper(this.posX + this.motionX, this.posY + this.motionY, this.posZ + this.motionZ);
        // Same raycast vanilla arrows use: stopOnLiquid=false, ignoreBlockWithout
        // BoundingBox=true (so grass/flowers/torches don't catch the hook mid-air),
        // returnLastUncollidableBlock=false.
        MovingObjectPosition hit = this.worldObj.func_147447_a(from, to, false, true, false);

        // Scan for a mob along the same path, but only up to a block hit (so a mob
        // standing behind a wall can't be grabbed through it). A mob beats the block.
        Vec3 scanFrom = Vec3.createVectorHelper(this.posX, this.posY, this.posZ);
        Vec3 scanTo = (hit != null)
                ? Vec3.createVectorHelper(hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord)
                : Vec3.createVectorHelper(this.posX + this.motionX, this.posY + this.motionY, this.posZ + this.motionZ);
        EntityLivingBase mob = findHitMob(scanFrom, scanTo);
        if (mob != null)
        {
            onHitMob(mob);
            return;
        }

        if (hit != null)
        {
            this.posX = hit.hitVec.xCoord;
            this.posY = hit.hitVec.yCoord;
            this.posZ = hit.hitVec.zCoord;
            this.motionX = this.motionY = this.motionZ = 0.0D;
            this.setPosition(this.posX, this.posY, this.posZ);
            if (!this.worldObj.isRemote)
            {
                // Publish the exact anchor before flipping state so they sync together.
                this.dataWatcher.updateObject(DW_ANCHOR_X, Float.valueOf((float) this.posX));
                this.dataWatcher.updateObject(DW_ANCHOR_Y, Float.valueOf((float) this.posY));
                this.dataWatcher.updateObject(DW_ANCHOR_Z, Float.valueOf((float) this.posZ));
                setState(STATE_STUCK);
                // Latch with the bite of the block cracking: play that block's break
                // sound at vanilla's break volume/pitch (see RenderGlobal case 2001).
                Block.SoundType anchorSound = this.worldObj.getBlock(hit.blockX, hit.blockY, hit.blockZ).stepSound;
                this.playSound(anchorSound.getBreakSound(),
                        (anchorSound.getVolume() + 1.0F) / 2.0F, anchorSound.getPitch() * 0.8F);
            }
            return;
        }

        this.posX += this.motionX;
        this.posY += this.motionY;
        this.posZ += this.motionZ;
        this.motionX *= Tuning.AIR_DRAG;
        this.motionY *= Tuning.AIR_DRAG;
        this.motionZ *= Tuning.AIR_DRAG;
        this.motionY -= Tuning.ARROW_GRAVITY;
        updateFlightRotation();
        this.setPosition(this.posX, this.posY, this.posZ);

        if (!this.worldObj.isRemote)
        {
            // A miss that flies past the grapple range (or never lands at all) is
            // cleaned up at once and the stack returns to primed.
            EntityPlayer owner = getOwner();
            double maxSq = Tuning.MAX_GRAPPLE_RANGE * Tuning.MAX_GRAPPLE_RANGE;
            if (this.ticksInAir > Tuning.MAX_FLIGHT_TICKS
                    || (owner != null && this.getDistanceSqToEntity(owner) > maxSq))
            {
                resetOwnerStack(owner);
                this.setDead();
            }
        }
    }

    private void tickRetracting()
    {
        EntityPlayer owner = getOwner();
        if (owner == null) return;

        double tx = owner.posX;
        double ty = owner.posY + owner.getEyeHeight() - 0.1D;
        double tz = owner.posZ;
        double dx = tx - this.posX;
        double dy = ty - this.posY;
        double dz = tz - this.posZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < Tuning.RETRACT_CATCH_DIST)
        {
            if (!this.worldObj.isRemote)
            {
                resetOwnerStack(owner);
                this.setDead();
            }
            return;
        }

        if (Tuning.RETRACT_SPEED >= dist)
        {
            this.posX = tx;
            this.posY = ty;
            this.posZ = tz;
        }
        else
        {
            this.motionX = dx / dist * Tuning.RETRACT_SPEED;
            this.motionY = dy / dist * Tuning.RETRACT_SPEED;
            this.motionZ = dz / dist * Tuning.RETRACT_SPEED;
            this.posX += this.motionX;
            this.posY += this.motionY;
            this.posZ += this.motionZ;
        }
        // Aim the head away from the player so the hook reels in tail-first: the cord
        // ties to the tail (see RenderGrapplingHook), so the tail should lead. dx,dy,dz
        // point at the player, hence the negation. Also pulls a stuck hook straight out
        // of its surface instead of flipping it around to dive head-first at the player.
        updateRotationFromHeading(-dx, -dy, -dz);
        this.setPosition(this.posX, this.posY, this.posZ);
    }

    /**
     * The closest mob whose (slightly expanded) box the segment {@code from -> to}
     * passes through, excluding the owner and other players, or {@code null}. Mirrors
     * the entity scan a vanilla arrow does, restricted to draggable living mobs.
     */
    private EntityLivingBase findHitMob(Vec3 from, Vec3 to)
    {
        EntityPlayer owner = getOwner();
        AxisAlignedBB sweep = this.boundingBox.addCoord(this.motionX, this.motionY, this.motionZ).expand(1.0D, 1.0D, 1.0D);
        List list = this.worldObj.getEntitiesWithinAABBExcludingEntity(this, sweep);

        EntityLivingBase best = null;
        double bestDist = 0.0D;
        for (int i = 0; i < list.size(); ++i)
        {
            Object o = list.get(i);
            if (!(o instanceof EntityLivingBase)) continue;     // only living mobs latch
            EntityLivingBase e = (EntityLivingBase) o;
            if (e == owner || e instanceof EntityPlayer) continue; // not the shooter, not players
            if (!e.canBeCollidedWith()) continue;

            AxisAlignedBB box = e.boundingBox.expand(0.3D, 0.3D, 0.3D);
            MovingObjectPosition m = box.calculateIntercept(from, to);
            if (m == null) continue;
            double d = from.distanceTo(m.hitVec);
            if (best == null || d < bestDist) { best = e; bestDist = d; }
        }
        return best;
    }

    /**
     * The hook struck a mob: stop it on both sides; the server then deals the impact
     * damage and either latches (mob survived) or resets like a miss (mob died).
     */
    private void onHitMob(EntityLivingBase mob)
    {
        this.motionX = this.motionY = this.motionZ = 0.0D;
        if (this.worldObj.isRemote) return; // damage & state are server-authoritative

        EntityPlayer owner = getOwner();
        if (owner == null) { this.setDead(); return; }

        mob.attackEntityFrom(DamageSource.causeThrownDamage(this, owner), Tuning.MOB_HIT_DAMAGE);

        if (!mob.isEntityAlive())
        {
            // The hit killed it; nothing left to latch onto, so reset to primed.
            resetOwnerStack(owner);
            this.setDead();
            return;
        }

        // Latch: leash length is the current straight player->mob gap plus a little slack.
        double mobCenterY = mob.posY + mob.height * 0.5D;
        double dx = mob.posX - owner.posX;
        double dy = mobCenterY - (owner.posY + owner.height * 0.5D);
        double dz = mob.posZ - owner.posZ;
        this.cableLength = Math.sqrt(dx * dx + dy * dy + dz * dz) + Tuning.LEEWAY;
        this.reeling = false;
        this.dataWatcher.updateObject(DW_TARGET, Integer.valueOf(mob.getEntityId()));
        this.setPosition(mob.posX, mobCenterY, mob.posZ);
        setState(STATE_LATCHED);
        this.playSound("random.bowhit", 0.6F, 1.0F / (this.rand.nextFloat() * 0.2F + 0.9F));
    }

    /**
     * Drag the latched mob: keep it within {@link #cableLength} of the player, reel
     * that length in while the use key is held, and snap the cable (reset to primed)
     * if terrain stops the mob from being pulled toward the player. The hook entity
     * rides the mob so the rendered cord ends on it.
     */
    private void tickLatched()
    {
        this.motionX = this.motionY = this.motionZ = 0.0D;

        EntityLivingBase mob = getTarget();
        if (mob == null || !mob.isEntityAlive())
        {
            // Target died or unloaded. Server tears down; clients await the sync.
            if (!this.worldObj.isRemote) breakCable();
            return;
        }

        if (this.worldObj.isRemote)
        {
            // Hang the cord's far end on the mob without waiting on tracking lag.
            this.setPosition(mob.posX, mob.posY + mob.height * 0.5D, mob.posZ);
            return;
        }

        EntityPlayer owner = getOwner();
        if (owner == null) { breakCable(); return; }

        // Hold use to reel the mob in, down to the floor length.
        if (this.reeling)
        {
            this.cableLength = Math.max(Tuning.MIN_ACTIVE_LENGTH, this.cableLength - Tuning.REEL_SPEED);
        }

        // Tether point on the player (body centre, matching the swing tether).
        double tx = owner.posX;
        double ty = owner.posY + owner.height * 0.5D;
        double tz = owner.posZ;
        double offY = mob.height * 0.5D;

        double dx = mob.posX - tx;
        double dy = (mob.posY + offY) - ty;
        double dz = mob.posZ - tz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double need = dist - this.cableLength;
        if (need > 0.0D && dist > 1.0E-4D)
        {
            // Pull the mob back onto the leash sphere, respecting block collision.
            double nx = dx / dist, ny = dy / dist, nz = dz / dist;
            double targetX = tx + nx * this.cableLength;
            double targetY = ty + ny * this.cableLength - offY;
            double targetZ = tz + nz * this.cableLength;
            mob.moveEntity(targetX - mob.posX, targetY - mob.posY, targetZ - mob.posZ);
            mob.velocityChanged = true;

            // Strip the outward velocity so the mob settles on the sphere instead of
            // straining against it (mirrors the player swing constraint).
            double outward = mob.motionX * nx + mob.motionY * ny + mob.motionZ * nz;
            if (outward > 0.0D)
            {
                mob.motionX -= nx * outward;
                mob.motionY -= ny * outward;
                mob.motionZ -= nz * outward;
            }

            // Obstruction snap: the pull demanded real inward travel, but if terrain
            // held the mob (it closed almost none of the gap) the cable breaks.
            double ndx = mob.posX - tx;
            double ndy = (mob.posY + offY) - ty;
            double ndz = mob.posZ - tz;
            double progress = dist - Math.sqrt(ndx * ndx + ndy * ndy + ndz * ndz);
            if (need >= Tuning.MOB_DRAG_MIN_PULL && progress < need * Tuning.MOB_DRAG_MIN_PROGRESS)
            {
                breakCable();
                return;
            }
        }

        this.setPosition(mob.posX, mob.posY + offY, mob.posZ);
    }

    /**
     * Fling the latched mob toward the player, mirroring the player-to-hook yank: the
     * aim is raised by {@link Tuning#MOB_YANK_RISE} and the speed scales with the
     * straight player-to-mob distance. The latch stays attached.
     */
    public void yankTarget()
    {
        if (this.worldObj.isRemote) return;
        EntityLivingBase mob = getTarget();
        EntityPlayer owner = getOwner();
        if (mob == null || owner == null || !mob.isEntityAlive()) return;

        double tx = owner.posX;
        double ty = owner.posY + owner.height * 0.5D;
        double tz = owner.posZ;
        double mobCenterY = mob.posY + mob.height * 0.5D;

        // Force scales with the straight player->mob gap (no wrapping while latched).
        double rdx = tx - mob.posX, rdy = ty - mobCenterY, rdz = tz - mob.posZ;
        double realDist = Math.sqrt(rdx * rdx + rdy * rdy + rdz * rdz);

        // Aim a little above the player so the mob arcs up and over lips/ledges.
        double ax = tx - mob.posX;
        double ay = (ty + Tuning.MOB_YANK_RISE) - mobCenterY;
        double az = tz - mob.posZ;
        double aLen = Math.sqrt(ax * ax + ay * ay + az * az);
        if (aLen < 1.0E-4D) return;

        double speed = Math.min(Tuning.MOB_YANK_K * realDist, Tuning.MOB_YANK_MAX_SPEED);
        mob.motionX = ax / aLen * speed;
        mob.motionY = ay / aLen * speed;
        mob.motionZ = az / aLen * speed;
        mob.velocityChanged = true;
        mob.fallDistance = 0.0F;
    }

    /** Snap the cable: reset the owner's stack to primed and remove the hook (server). */
    private void breakCable()
    {
        resetOwnerStack(getOwner());
        this.playSound("random.break", 0.7F, 0.8F + this.rand.nextFloat() * 0.4F);
        this.setDead();
    }

    /** Reset the owner's fired hook back to its primed state, if it still exists. */
    private void resetOwnerStack(EntityPlayer owner)
    {
        if (owner == null) return;
        ItemStack[] inv = owner.inventory.mainInventory;
        for (int i = 0; i < inv.length; ++i)
        {
            ItemStack s = inv[i];
            if (s != null && s.getItem() instanceof ItemGrapplingHook
                    && s.getItemDamage() == ItemGrapplingHook.META_FIRED)
            {
                s.setItemDamage(ItemGrapplingHook.META_PRIMED);
            }
        }
    }

    private void updateRotationFromMotion()
    {
        updateRotationFromHeading(this.motionX, this.motionY, this.motionZ);
    }

    /** Aim the rendered arrow's head along the given heading vector. */
    private void updateRotationFromHeading(double x, double y, double z)
    {
        float horiz = MathHelper.sqrt_double(x * x + z * z);
        this.rotationYaw = (float) (Math.atan2(x, z) * 180.0D / Math.PI);
        this.rotationPitch = (float) (Math.atan2(y, horiz) * 180.0D / Math.PI);
    }

    /**
     * Ease the rendered arrow toward its motion heading each flight tick, the way a
     * vanilla arrow does ({@code EntityArrow.onUpdate}): unwrap the previous angle
     * across the +/-180 seam so a heading that crosses it doesn't whip the model
     * through a full turn during render interpolation, then lerp a fifth of the way.
     * Replaces the old direct assignment, which snapped the model and, combined with
     * the per-tick network correction, read as flight jitter.
     */
    private void updateFlightRotation()
    {
        float horiz = MathHelper.sqrt_double(this.motionX * this.motionX + this.motionZ * this.motionZ);
        float yaw = (float) (Math.atan2(this.motionX, this.motionZ) * 180.0D / Math.PI);
        float pitch = (float) (Math.atan2(this.motionY, horiz) * 180.0D / Math.PI);

        // Freshly spawned, before prev is meaningful: aim straight at the heading.
        if (this.prevRotationPitch == 0.0F && this.prevRotationYaw == 0.0F)
        {
            this.prevRotationYaw = this.rotationYaw = yaw;
            this.prevRotationPitch = this.rotationPitch = pitch;
            return;
        }

        while (pitch - this.prevRotationPitch < -180.0F) this.prevRotationPitch -= 360.0F;
        while (pitch - this.prevRotationPitch >= 180.0F) this.prevRotationPitch += 360.0F;
        while (yaw - this.prevRotationYaw < -180.0F) this.prevRotationYaw -= 360.0F;
        while (yaw - this.prevRotationYaw >= 180.0F) this.prevRotationYaw += 360.0F;

        this.rotationPitch = this.prevRotationPitch + (pitch - this.prevRotationPitch) * 0.2F;
        this.rotationYaw = this.prevRotationYaw + (yaw - this.prevRotationYaw) * 0.2F;
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound tag)
    {
        tag.setByte("State", getState());
        tag.setInteger("TicksInAir", this.ticksInAir);
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound tag)
    {
        setState(tag.getByte("State"));
        this.ticksInAir = tag.getInteger("TicksInAir");
        // Owner id is not persisted; an unresolved owner makes the hook self-destruct.
    }

    /**
     * Hand the client the exact launch velocity at spawn. The ordinary entity-spawn
     * and velocity packets both clamp each component to +/-3.9 b/t, but the hook
     * launches at {@link Tuning#LAUNCH_SPEED} (4.5), so those channels can't carry the
     * true value. With the precise velocity, the client integrates the identical
     * flight parabola the server does and needs no per-tick position correction --
     * that correction (a quantized, lagged snap every tick) was the flight jitter.
     */
    @Override
    public void writeSpawnData(ByteBuf buf)
    {
        buf.writeDouble(this.motionX);
        buf.writeDouble(this.motionY);
        buf.writeDouble(this.motionZ);
    }

    @Override
    public void readSpawnData(ByteBuf buf)
    {
        this.motionX = buf.readDouble();
        this.motionY = buf.readDouble();
        this.motionZ = buf.readDouble();
    }

    @Override
    public boolean canBeCollidedWith() { return false; }

    @Override
    protected boolean canTriggerWalking() { return false; }
}
