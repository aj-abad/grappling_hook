package com.ajabad.grapplinghook.entity;

import java.util.List;

import com.ajabad.grapplinghook.Tuning;
import com.ajabad.grapplinghook.item.ItemGrapplingHook;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
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
public class EntityGrapplingHook extends Entity
{
    public static final byte STATE_FLYING = 0;
    public static final byte STATE_STUCK = 1;
    public static final byte STATE_RETRACTING = 2;

    private static final int DW_STATE = 16;
    private static final int DW_OWNER = 17;
    private static final int DW_ANCHOR_X = 18;
    private static final int DW_ANCHOR_Y = 19;
    private static final int DW_ANCHOR_Z = 20;

    private int ticksInAir;

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
    }

    public byte getState() { return this.dataWatcher.getWatchableObjectByte(DW_STATE); }

    public void setState(byte state) { this.dataWatcher.updateObject(DW_STATE, Byte.valueOf(state)); }

    public int getOwnerId() { return this.dataWatcher.getWatchableObjectInt(DW_OWNER); }

    public EntityPlayer getOwner()
    {
        Entity e = this.worldObj.getEntityByID(getOwnerId());
        return (e instanceof EntityPlayer) ? (EntityPlayer) e : null;
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
            default:
                break;
        }
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
                this.playSound("random.bowhit", 0.6F, 1.0F / (this.rand.nextFloat() * 0.2F + 0.9F));
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
        updateRotationFromMotion();
        this.setPosition(this.posX, this.posY, this.posZ);

        if (!this.worldObj.isRemote && this.ticksInAir > Tuning.MAX_FLIGHT_TICKS)
        {
            resetOwnerStack(getOwner());
            this.setDead();
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
        updateRotationFromMotion();
        this.setPosition(this.posX, this.posY, this.posZ);
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
        float horiz = MathHelper.sqrt_double(this.motionX * this.motionX + this.motionZ * this.motionZ);
        this.rotationYaw = (float) (Math.atan2(this.motionX, this.motionZ) * 180.0D / Math.PI);
        this.rotationPitch = (float) (Math.atan2(this.motionY, horiz) * 180.0D / Math.PI);
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

    @Override
    public boolean canBeCollidedWith() { return false; }

    @Override
    protected boolean canTriggerWalking() { return false; }
}
