package com.ajabad.grapplinghook.client.render;

import java.util.ArrayList;
import java.util.List;

import com.ajabad.grapplinghook.Reference;
import com.ajabad.grapplinghook.Tuning;
import com.ajabad.grapplinghook.entity.EntityGrapplingHook;
import com.ajabad.grapplinghook.util.BlockGeometry;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Renders the hook as a vanilla-style arrow and draws the connecting cord from
 * the owner's hand, bending through the cable pivots when the cord is wrapped.
 *
 * <p>The arrow model is ported from {@code RenderArrow}; the hand-anchor maths are
 * ported from {@code RenderFish} (fishing line) so the cord starts where the held
 * item's tip is, in both first- and third-person.
 */
@SideOnly(Side.CLIENT)
public class RenderGrapplingHook extends Render
{
    private static final ResourceLocation ARROW_TEX =
            new ResourceLocation(Reference.MODID, "textures/items/arrows.png");

    /** How far below a cord point to look for a floor to rest it on, in blocks. */
    private static final int FLOOR_SCAN_DEPTH = 4;

    /**
     * Distance (blocks) from the entity origin back to the arrow's tail, where the
     * cord ties on. The model's back edge is at x=-8; the renderArrow translate(-4)
     * and scale put it (8 + 4) * 0.05625 blocks behind the origin. The head reaches
     * only (8 - 4) * 0.05625 in front, which is why anchoring on the origin looked
     * head-attached (and buried the cord in the block when stuck).
     */
    private static final double ARROW_TAIL_REACH = (8.0D + 4.0D) * 0.05625D;

    @Override
    public void doRender(Entity entity, double x, double y, double z, float yaw, float partial)
    {
        EntityGrapplingHook hook = (EntityGrapplingHook) entity;
        renderArrow(hook, x, y, z, partial);
        renderCord(hook, x, y, z, partial);
    }

    private void renderArrow(EntityGrapplingHook hook, double x, double y, double z, float partial)
    {
        this.bindEntityTexture(hook);
        GL11.glPushMatrix();
        GL11.glTranslatef((float) x, (float) y, (float) z);
        GL11.glRotatef(hook.prevRotationYaw + (hook.rotationYaw - hook.prevRotationYaw) * partial - 90.0F, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(hook.prevRotationPitch + (hook.rotationPitch - hook.prevRotationPitch) * partial, 0.0F, 0.0F, 1.0F);
        Tessellator t = Tessellator.instance;

        // UVs into the hook texture (textures/items/arrows.png, 12x10 px). The arrow
        // seen side-on is the top 12x7 block (the four "shaft" planes); the back-end
        // cross-section is the 3x3 block directly below it, starting at y=7.
        float shaftU0 = 0.0F,         shaftU1 = 12.0F / 12.0F;
        float shaftV0 = 0.0F,         shaftV1 = 7.0F / 10.0F;
        float backU0  = 0.0F,         backU1  = 3.0F / 12.0F;
        float backV0  = 7.0F / 10.0F, backV1  = 10.0F / 10.0F;

        float scale = 0.05625F;
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glRotatef(45.0F, 1.0F, 0.0F, 0.0F);
        GL11.glScalef(scale, scale, scale);
        GL11.glTranslatef(-4.0F, 0.0F, 0.0F);

        // The flat cross-section capping the tail, drawn from both faces. It sits at
        // x=-8 (the back edge of the shaft planes), not vanilla's -7: the shaft art
        // runs the full 12px to that edge, so the cap sits flush with the tail end of
        // the shaft instead of floating 1px ahead of it.
        GL11.glNormal3f(scale, 0.0F, 0.0F);
        t.startDrawingQuads();
        t.addVertexWithUV(-8.0D, -2.0D, -2.0D, backU0, backV0);
        t.addVertexWithUV(-8.0D, -2.0D, 2.0D, backU1, backV0);
        t.addVertexWithUV(-8.0D, 2.0D, 2.0D, backU1, backV1);
        t.addVertexWithUV(-8.0D, 2.0D, -2.0D, backU0, backV1);
        t.draw();
        GL11.glNormal3f(-scale, 0.0F, 0.0F);
        t.startDrawingQuads();
        t.addVertexWithUV(-8.0D, 2.0D, -2.0D, backU0, backV0);
        t.addVertexWithUV(-8.0D, 2.0D, 2.0D, backU1, backV0);
        t.addVertexWithUV(-8.0D, -2.0D, 2.0D, backU1, backV1);
        t.addVertexWithUV(-8.0D, -2.0D, -2.0D, backU0, backV1);
        t.draw();

        // The shaft: four planes in a + cross, each showing the side view.
        for (int i = 0; i < 4; ++i)
        {
            GL11.glRotatef(90.0F, 1.0F, 0.0F, 0.0F);
            GL11.glNormal3f(0.0F, 0.0F, scale);
            t.startDrawingQuads();
            t.addVertexWithUV(-8.0D, -2.0D, 0.0D, shaftU0, shaftV0);
            t.addVertexWithUV(8.0D, -2.0D, 0.0D, shaftU1, shaftV0);
            t.addVertexWithUV(8.0D, 2.0D, 0.0D, shaftU1, shaftV1);
            t.addVertexWithUV(-8.0D, 2.0D, 0.0D, shaftU0, shaftV1);
            t.draw();
        }

        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glPopMatrix();
    }

    private void renderCord(EntityGrapplingHook hook, double x, double y, double z, float partial)
    {
        EntityPlayer owner = hook.getOwner();
        if (owner == null) return;

        // Interpolated entity world position; cord points are drawn relative to it.
        double eiwX = hook.prevPosX + (hook.posX - hook.prevPosX) * partial;
        double eiwY = hook.prevPosY + (hook.posY - hook.prevPosY) * partial;
        double eiwZ = hook.prevPosZ + (hook.posZ - hook.prevPosZ) * partial;

        // Build the cord polyline in world space: hand -> (wrap pivots, active first)
        // -> the hook's tail. The far end ties onto the arrow's back end, not the
        // entity origin (which sits up by the head, and inside the block when stuck).
        // pivots[0] is the anchor/impact point and coincides with that origin, so we
        // drop it and substitute the tail; any other pivots are real wrap corners.
        List<Vec3> points = new ArrayList<Vec3>();
        points.add(getHandPos(owner, partial));
        List<Vec3> pivots = hook.renderPivots;
        if (pivots != null && !pivots.isEmpty())
        {
            for (int i = pivots.size() - 1; i >= 1; --i) points.add(pivots.get(i));
        }
        points.add(tailAnchor(hook, eiwX, eiwY, eiwZ, partial));

        // Convert to render-local coordinates (relative to this entity's draw origin).
        double[][] pts = new double[points.size()][3];
        for (int i = 0; i < points.size(); ++i)
        {
            Vec3 p = points.get(i);
            pts[i][0] = x + (p.xCoord - eiwX);
            pts[i][1] = y + (p.yCoord - eiwY);
            pts[i][2] = z + (p.zCoord - eiwZ);
        }

        // Subdivide each span and droop it: the anchored active span sags by its
        // slack (taut => straight line), an in-flight cord gets a small loose droop,
        // and a retracting hook eases that droop out so its cord pulls taut as it reels.
        boolean tethered = pivots != null && !pivots.isEmpty();
        double droopScale = retractDroopScale(hook, partial);
        double offX = eiwX - x, offY = eiwY - y, offZ = eiwZ - z;
        List<double[]> curve = saggedCurve(pts, tethered, droopScale, hook.renderSlack, owner.worldObj, offX, offY, offZ);

        // Light each cord point from the world at its own position rather than
        // letting it inherit the hook entity's render brightness: when the hook is
        // buried in a block that brightness is 0, which would render the whole cord
        // black. Colour alternates per segment between two browns, matching the
        // vanilla lead/leash strands.
        World world = owner.worldObj;
        Tessellator t = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE); // the cord is a two-quad tube, visible from any side
        t.startDrawingQuads();
        int briA = cordBrightness(world, curve.get(0), offX, offY, offZ);
        for (int i = 0; i + 1 < curve.size(); ++i)
        {
            int briB = cordBrightness(world, curve.get(i + 1), offX, offY, offZ);
            int color = (i % 2 == 0) ? Tuning.CORD_COLOR_LIGHT : Tuning.CORD_COLOR_DARK;
            drawThickSegment(t, curve.get(i), curve.get(i + 1), Tuning.CORD_WIDTH, color, briA, briB);
            briA = briB;
        }
        t.draw();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** Expand the coarse pivot points into a finer, drooping polyline. */
    private List<double[]> saggedCurve(double[][] pts, boolean tethered, double droopScale, double slack,
            World world, double offX, double offY, double offZ)
    {
        List<double[]> curve = new ArrayList<double[]>();
        for (int i = 0; i + 1 < pts.length; ++i)
        {
            double[] a = pts[i];
            double[] b = pts[i + 1];
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double sag = spanSag(tethered, droopScale, i, len, slack);
            for (int k = 0; k < Tuning.CORD_SUBDIVISIONS; ++k)
            {
                double s = (double) k / Tuning.CORD_SUBDIVISIONS;
                double droop = sag * 4.0D * s * (1.0D - s); // 0 at the ends, max at the middle
                double px = a[0] + dx * s;
                double py = a[1] + dy * s - droop;
                double pz = a[2] + dz * s;
                // Only the drooping belly can dip into the floor; taut points sit on
                // the straight chord between real pivots, so skip probing them.
                if (droop > 0.0D) py = restOnFloor(world, px, py, pz, offX, offY, offZ);
                curve.add(new double[] {px, py, pz});
            }
        }
        curve.add(pts[pts.length - 1]); // final endpoint
        return curve;
    }

    /**
     * Lift a render-local cord point so it rests on the block surface beneath it
     * rather than clipping through the floor. Purely cosmetic: converts to world
     * space, probes the real (state-aware) surface height, and clamps back.
     */
    private double restOnFloor(World world, double localX, double localY, double localZ,
            double offX, double offY, double offZ)
    {
        if (world == null) return localY;
        double top = BlockGeometry.surfaceTopBelow(
                world, localX + offX, localY + offY, localZ + offZ, FLOOR_SCAN_DEPTH);
        if (Double.isNaN(top)) return localY;
        double minLocalY = (top + Tuning.CORD_GROUND_CLEARANCE) - offY;
        return localY < minLocalY ? minLocalY : localY;
    }

    /** Droop depth for one span. */
    private double spanSag(boolean tethered, double droopScale, int spanIndex, double len, double slack)
    {
        if (tethered)
        {
            // Span 0 (hand -> active pivot) is the only free span; the rest are taut
            // wrapped segments. Sag comes from the rope's slack, so a taut rope
            // (slack 0, i.e. player at max distance) draws straight.
            if (spanIndex != 0 || slack <= 0.0D) return 0.0D;
            double h = Math.sqrt(3.0D * len * slack / 8.0D) * Tuning.CORD_SAG_SCALE;
            return Math.min(Tuning.CORD_SAG_MAX, h);
        }
        // In-flight or remote cord: a small, loose droop. While retracting, droopScale
        // eases from 1 to 0 so the cord pulls taut over a fraction of a second.
        return Math.min(Tuning.CORD_SAG_MAX, len * Tuning.CORD_FLIGHT_SAG) * droopScale;
    }

    /**
     * Cord droop multiplier: 1.0 normally, easing 1 -> 0 over the first
     * {@link Tuning#RETRACT_TAUT_TICKS} ticks of retracting so the cord pulls taut
     * smoothly. {@code partial} interpolates within the tick for frame-smooth motion.
     */
    private double retractDroopScale(EntityGrapplingHook hook, float partial)
    {
        if (hook.getState() != EntityGrapplingHook.STATE_RETRACTING) return 1.0D;
        double t = (hook.retractTicks + partial) / Tuning.RETRACT_TAUT_TICKS;
        if (t <= 0.0D) return 1.0D;
        if (t >= 1.0D) return 0.0D;
        double k = t * t * (3.0D - 2.0D * t); // smoothstep: gentle ease in and out
        return 1.0D - k;
    }

    /**
     * A thick segment drawn as two perpendicular quads (a square-ish tube). The
     * segment is one solid {@code color}; brightness is interpolated from {@code briA}
     * at end {@code a} to {@code briB} at end {@code b} so the cord shades with the world.
     */
    private void drawThickSegment(Tessellator t, double[] a, double[] b, float halfWidth,
            int color, int briA, int briB)
    {
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-5D) return;
        dx /= len; dy /= len; dz /= len;

        // A reference "up" not parallel to the segment direction.
        double ux = 0.0D, uy = 1.0D, uz = 0.0D;
        if (Math.abs(dy) > 0.99D) { ux = 1.0D; uy = 0.0D; uz = 0.0D; }

        // n1 = dir x up, n2 = dir x n1 (orthonormal frame around the segment).
        double n1x = dy * uz - dz * uy;
        double n1y = dz * ux - dx * uz;
        double n1z = dx * uy - dy * ux;
        double n1l = Math.sqrt(n1x * n1x + n1y * n1y + n1z * n1z);
        n1x /= n1l; n1y /= n1l; n1z /= n1l;
        double n2x = dy * n1z - dz * n1y;
        double n2y = dz * n1x - dx * n1z;
        double n2z = dx * n1y - dy * n1x;

        t.setColorOpaque_I(color);
        addQuad(t, a, b, n1x * halfWidth, n1y * halfWidth, n1z * halfWidth, briA, briB);
        addQuad(t, a, b, n2x * halfWidth, n2y * halfWidth, n2z * halfWidth, briA, briB);
    }

    private void addQuad(Tessellator t, double[] a, double[] b, double ox, double oy, double oz,
            int briA, int briB)
    {
        t.setBrightness(briA);
        t.addVertex(a[0] + ox, a[1] + oy, a[2] + oz);
        t.setBrightness(briB);
        t.addVertex(b[0] + ox, b[1] + oy, b[2] + oz);
        t.setBrightness(briB);
        t.addVertex(b[0] - ox, b[1] - oy, b[2] - oz);
        t.setBrightness(briA);
        t.addVertex(a[0] - ox, a[1] - oy, a[2] - oz);
    }

    /**
     * Packed sky/block light at a cord point's world position, for
     * {@link Tessellator#setBrightness}. Sampling per point keeps the cord lit by
     * its surroundings instead of inheriting the (possibly buried) hook's brightness.
     */
    private int cordBrightness(World world, double[] localPt, double offX, double offY, double offZ)
    {
        if (world == null) return 0x00F000F0; // full bright fallback
        int wx = MathHelper.floor_double(localPt[0] + offX);
        int wy = MathHelper.floor_double(localPt[1] + offY);
        int wz = MathHelper.floor_double(localPt[2] + offZ);
        return world.getLightBrightnessForSkyBlocks(wx, wy, wz, 0);
    }

    /**
     * World position of the hook's tail (the feathered back end) for this frame, where
     * the cord ties on. Steps {@link #ARROW_TAIL_REACH} blocks back from the entity
     * origin along the flight direction, rebuilt from the same interpolated yaw/pitch
     * {@code renderArrow} rotates the model by, so the knot tracks the drawn arrow.
     */
    private Vec3 tailAnchor(EntityGrapplingHook hook, double eiwX, double eiwY, double eiwZ, float partial)
    {
        double yaw = Math.toRadians(hook.prevRotationYaw + (hook.rotationYaw - hook.prevRotationYaw) * partial);
        double pitch = Math.toRadians(hook.prevRotationPitch + (hook.rotationPitch - hook.prevRotationPitch) * partial);
        double cosP = Math.cos(pitch);
        // Flight (head) direction; the tail is the opposite end, so subtract it.
        double dirX = Math.sin(yaw) * cosP;
        double dirY = Math.sin(pitch);
        double dirZ = Math.cos(yaw) * cosP;
        return Vec3.createVectorHelper(
                eiwX - dirX * ARROW_TAIL_REACH,
                eiwY - dirY * ARROW_TAIL_REACH,
                eiwZ - dirZ * ARROW_TAIL_REACH);
    }

    /** World position of the held item's tip, ported from RenderFish. */
    private Vec3 getHandPos(EntityPlayer p, float partial)
    {
        float swing = p.getSwingProgress(partial);
        float f10 = MathHelper.sin(MathHelper.sqrt_float(swing) * (float) Math.PI);
        Vec3 v = Vec3.createVectorHelper(-0.5D, 0.03D, 0.8D);
        v.rotateAroundX(-(p.prevRotationPitch + (p.rotationPitch - p.prevRotationPitch) * partial) * (float) Math.PI / 180.0F);
        v.rotateAroundY(-(p.prevRotationYaw + (p.rotationYaw - p.prevRotationYaw) * partial) * (float) Math.PI / 180.0F);
        v.rotateAroundY(f10 * 0.5F);
        v.rotateAroundX(-f10 * 0.7F);

        double px = p.prevPosX + (p.posX - p.prevPosX) * partial;
        double py = p.prevPosY + (p.posY - p.prevPosY) * partial;
        double pz = p.prevPosZ + (p.posZ - p.prevPosZ) * partial;
        double hx = px + v.xCoord;
        double hy = py + v.yCoord;
        double hz = pz + v.zCoord;

        boolean third = this.renderManager.options.thirdPersonView > 0
                || p != Minecraft.getMinecraft().thePlayer;
        if (third)
        {
            double eye = (p == Minecraft.getMinecraft().thePlayer) ? 0.0D : p.getEyeHeight();
            float yawOff = (p.prevRenderYawOffset + (p.renderYawOffset - p.prevRenderYawOffset) * partial) * (float) Math.PI / 180.0F;
            double sin = MathHelper.sin(yawOff);
            double cos = MathHelper.cos(yawOff);
            hx = px - cos * 0.35D - sin * 0.85D;
            hy = py + eye - 0.45D;
            hz = pz - sin * 0.35D + cos * 0.85D;
        }
        return Vec3.createVectorHelper(hx, hy, hz);
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity entity)
    {
        return ARROW_TEX;
    }
}
