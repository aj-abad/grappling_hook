package com.ajabad.grapplinghook.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/**
 * Helpers for reading the real, state-aware collision shape of blocks, so the
 * cable can wrap on the true corners of slabs/stairs/fences and the rendered
 * cord can rest on their actual top surface rather than on the block grid.
 */
public final class BlockGeometry
{
    private BlockGeometry() {}

    /**
     * The Y of the solid surface at or below {@code (wx, wy, wz)}, or
     * {@link Double#NaN} if none is found within {@code maxDrop} blocks. Uses
     * each block's state-aware render bounds, so a bottom slab reports a surface
     * at y+0.5 and a fence at y+1.0 (its visible top), not the block grid line.
     *
     * <p>A solid mass whose underside is above {@code wy} (an overhang the point
     * hangs beneath) is skipped, so a point in the open half of a top-slab
     * column rests on the ground below it rather than snapping up to the slab.
     */
    public static double surfaceTopBelow(World world, double wx, double wy, double wz, int maxDrop)
    {
        int bx = MathHelper.floor_double(wx);
        int bz = MathHelper.floor_double(wz);
        int startY = MathHelper.floor_double(wy);
        for (int by = startY; by >= startY - maxDrop; --by)
        {
            Block block = world.getBlock(bx, by, bz);
            if (!block.getMaterial().blocksMovement()) continue;
            block.setBlockBoundsBasedOnState(world, bx, by, bz);
            if (by + block.getBlockBoundsMinY() > wy + 1.0E-4D) continue; // solid overhead
            return by + block.getBlockBoundsMaxY();
        }
        return Double.NaN;
    }

    /**
     * The corner of the block's true collision shape nearest to {@code hit}. For
     * multi-box blocks (stairs) every sub-box is considered, so the cord can
     * catch a stair's lower-step edge at y+0.5 instead of the outer block corner.
     */
    public static Vec3 nearestShapeCorner(World world, int bx, int by, int bz, Vec3 hit)
    {
        Block block = world.getBlock(bx, by, bz);
        List boxes = new ArrayList();
        AxisAlignedBB mask = AxisAlignedBB.getBoundingBox(
                bx - 1, by - 1, bz - 1, bx + 2, by + 2, bz + 2);
        block.addCollisionBoxesToList(world, bx, by, bz, mask, boxes, null);
        if (boxes.isEmpty())
        {
            // No collision box (a non-solid but ray-traceable block): fall back to
            // the block's render bounds so we still have a corner to wrap around.
            block.setBlockBoundsBasedOnState(world, bx, by, bz);
            boxes.add(AxisAlignedBB.getBoundingBox(
                    bx + block.getBlockBoundsMinX(), by + block.getBlockBoundsMinY(), bz + block.getBlockBoundsMinZ(),
                    bx + block.getBlockBoundsMaxX(), by + block.getBlockBoundsMaxY(), bz + block.getBlockBoundsMaxZ()));
        }

        double bestSq = Double.MAX_VALUE;
        double cx = bx + 0.5D, cy = by + 0.5D, cz = bz + 0.5D;
        for (int i = 0; i < boxes.size(); ++i)
        {
            AxisAlignedBB box = (AxisAlignedBB) boxes.get(i);
            for (int corner = 0; corner < 8; ++corner)
            {
                double x = (corner & 1) == 0 ? box.minX : box.maxX;
                double y = (corner & 2) == 0 ? box.minY : box.maxY;
                double z = (corner & 4) == 0 ? box.minZ : box.maxZ;
                double dsq = sq(x - hit.xCoord) + sq(y - hit.yCoord) + sq(z - hit.zCoord);
                if (dsq < bestSq) { bestSq = dsq; cx = x; cy = y; cz = z; }
            }
        }
        return Vec3.createVectorHelper(cx, cy, cz);
    }

    private static double sq(double v) { return v * v; }
}
