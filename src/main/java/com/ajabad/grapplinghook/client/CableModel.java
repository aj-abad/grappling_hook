package com.ajabad.grapplinghook.client;

import java.util.ArrayList;
import java.util.List;

import com.ajabad.grapplinghook.util.BlockGeometry;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/**
 * The rope as a polyline of pivot points: index 0 is the fixed anchor (where the
 * hook stuck), the last element is the "active" pivot closest to the player and
 * the current swing fulcrum. Intermediate pivots are corners the cord has wrapped
 * around (added in the wrapping stage).
 *
 * <p>{@link #cableLength} is the total rope budget. The portion already spent on
 * fixed wrapped segments is {@link #consumedLength}; what's left for the
 * player-side segment is {@link #activeLength}.
 */
@SideOnly(Side.CLIENT)
public class CableModel
{
    /** Safety cap on how many corners the cord can wrap around. */
    private static final int MAX_PIVOTS = 24;
    /** Endpoint nudge (blocks) so rays don't self-hit the surface a pivot rests on. */
    private static final double NUDGE = 0.1D;
    /** How far (blocks) a wrap corner is pushed off its surface along the hit face. */
    private static final double WRAP_NUDGE = 0.05D;

    public final List<Vec3> pivots = new ArrayList<Vec3>();
    public double cableLength;

    public void init(Vec3 anchor, double length)
    {
        this.pivots.clear();
        this.pivots.add(anchor);
        this.cableLength = length;
    }

    public boolean isEmpty() { return this.pivots.isEmpty(); }

    public Vec3 anchor() { return this.pivots.get(0); }

    public Vec3 activePivot() { return this.pivots.get(this.pivots.size() - 1); }

    /** Length of the fixed, already-wrapped segments (anchor .. active pivot). */
    public double consumedLength()
    {
        double sum = 0.0D;
        for (int i = 0; i + 1 < this.pivots.size(); ++i)
        {
            sum += dist(this.pivots.get(i), this.pivots.get(i + 1));
        }
        return sum;
    }

    /** Max straight-line distance allowed from the active pivot to the player. */
    public double activeLength()
    {
        return Math.max(0.0D, this.cableLength - consumedLength());
    }

    /**
     * Re-shape the cord around block geometry for this tick: first drop any corners
     * the player can now see past (unwrap), then, if the active segment is blocked,
     * bend the cord around the obstructing block corner (wrap).
     *
     * @param world  the player's world
     * @param player the player-side tether point (body centre works best)
     */
    public void update(World world, Vec3 player)
    {
        // Unwrap: while the player has direct line of sight to the pivot behind the
        // active one, the active corner is no longer doing any work.
        int guard = 0;
        while (this.pivots.size() >= 2 && guard++ < MAX_PIVOTS)
        {
            Vec3 behind = this.pivots.get(this.pivots.size() - 2);
            if (segmentBlocked(world, behind, player)) break;
            this.pivots.remove(this.pivots.size() - 1);
        }

        // Wrap: the active segment is obstructed, so catch on a block corner.
        if (this.pivots.size() < MAX_PIVOTS)
        {
            Vec3 active = activePivot();
            if (segmentBlocked(world, active, player))
            {
                Vec3 corner = findWrapCorner(world, active, player);
                if (corner != null) this.pivots.add(corner);
            }
        }
    }

    /** True if a block sits between the two points (endpoints nudged inward). */
    private boolean segmentBlocked(World world, Vec3 from, Vec3 to)
    {
        return traceNudged(world, from, to) != null;
    }

    private MovingObjectPosition traceNudged(World world, Vec3 from, Vec3 to)
    {
        double dx = to.xCoord - from.xCoord;
        double dy = to.yCoord - from.yCoord;
        double dz = to.zCoord - from.zCoord;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 2.0D * NUDGE) return null; // too short to meaningfully obstruct
        double e = NUDGE / len;
        Vec3 a = Vec3.createVectorHelper(from.xCoord + dx * e, from.yCoord + dy * e, from.zCoord + dz * e);
        Vec3 b = Vec3.createVectorHelper(to.xCoord - dx * e, to.yCoord - dy * e, to.zCoord - dz * e);
        return world.rayTraceBlocks(a, b);
    }

    /**
     * The block corner the cord should bend around, or {@code null} if none is
     * usable. Found by tracing from the player toward the active pivot, snapping the
     * first hit to the nearest corner of that block's true collision shape, and
     * nudging it out of the surface.
     */
    private Vec3 findWrapCorner(World world, Vec3 active, Vec3 player)
    {
        MovingObjectPosition hit = traceNudged(world, player, active);
        if (hit == null || hit.hitVec == null) return null;

        // Snap to the nearest corner of the block's true collision shape (so a
        // slab/stair edge lands at the real half-block height, not the grid line),
        // then nudge it just off the hit face so the cord clears the surface.
        double[] n = faceNormal(hit.sideHit);
        Vec3 c = BlockGeometry.nearestShapeCorner(world, hit.blockX, hit.blockY, hit.blockZ, hit.hitVec);
        Vec3 corner = Vec3.createVectorHelper(
                c.xCoord + n[0] * WRAP_NUDGE,
                c.yCoord + n[1] * WRAP_NUDGE,
                c.zCoord + n[2] * WRAP_NUDGE);

        // Reject degenerate, duplicate, or unreachable corners.
        if (dist(corner, active) < 0.25D || dist(corner, player) < 0.25D) return null;
        for (int i = 0; i < this.pivots.size(); ++i)
        {
            if (dist(this.pivots.get(i), corner) < 0.25D) return null;
        }
        if (segmentBlocked(world, active, corner)) return null;
        return corner;
    }

    private static double[] faceNormal(int sideHit)
    {
        switch (sideHit)
        {
            case 0: return new double[] {0.0D, -1.0D, 0.0D}; // bottom
            case 1: return new double[] {0.0D, 1.0D, 0.0D};  // top
            case 2: return new double[] {0.0D, 0.0D, -1.0D}; // north
            case 3: return new double[] {0.0D, 0.0D, 1.0D};  // south
            case 4: return new double[] {-1.0D, 0.0D, 0.0D}; // west
            case 5: return new double[] {1.0D, 0.0D, 0.0D};  // east
            default: return new double[] {0.0D, 1.0D, 0.0D};
        }
    }

    static double dist(Vec3 a, Vec3 b)
    {
        double dx = a.xCoord - b.xCoord;
        double dy = a.yCoord - b.yCoord;
        double dz = a.zCoord - b.zCoord;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
