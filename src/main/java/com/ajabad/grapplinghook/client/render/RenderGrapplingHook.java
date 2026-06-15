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

        // UVs into the hook texture (textures/items/arrows.png, 16x12 px). The arrow
        // seen side-on is the top 16x7 block (the four "shaft" planes); the back-end
        // cross-section is the 5x5 block directly below it, starting at y=7.
        float shaftU0 = 0.0F,         shaftU1 = 16.0F / 16.0F;
        float shaftV0 = 0.0F,         shaftV1 = 7.0F / 12.0F;
        float backU0  = 0.0F,         backU1  = 5.0F / 16.0F;
        float backV0  = 7.0F / 12.0F, backV1  = 12.0F / 12.0F;

        float scale = 0.05625F;
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glRotatef(45.0F, 1.0F, 0.0F, 0.0F);
        GL11.glScalef(scale, scale, scale);
        GL11.glTranslatef(-4.0F, 0.0F, 0.0F);

        // The flat square at the back of the arrow, drawn from both faces.
        GL11.glNormal3f(scale, 0.0F, 0.0F);
        t.startDrawingQuads();
        t.addVertexWithUV(-7.0D, -2.0D, -2.0D, backU0, backV0);
        t.addVertexWithUV(-7.0D, -2.0D, 2.0D, backU1, backV0);
        t.addVertexWithUV(-7.0D, 2.0D, 2.0D, backU1, backV1);
        t.addVertexWithUV(-7.0D, 2.0D, -2.0D, backU0, backV1);
        t.draw();
        GL11.glNormal3f(-scale, 0.0F, 0.0F);
        t.startDrawingQuads();
        t.addVertexWithUV(-7.0D, 2.0D, -2.0D, backU0, backV0);
        t.addVertexWithUV(-7.0D, 2.0D, 2.0D, backU1, backV0);
        t.addVertexWithUV(-7.0D, -2.0D, 2.0D, backU1, backV1);
        t.addVertexWithUV(-7.0D, -2.0D, -2.0D, backU0, backV1);
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

        // Build the cord polyline in world space: hand -> (pivots, active first) -> anchor.
        List<Vec3> points = new ArrayList<Vec3>();
        points.add(getHandPos(owner, partial));
        List<Vec3> pivots = hook.renderPivots;
        if (pivots != null && !pivots.isEmpty())
        {
            for (int i = pivots.size() - 1; i >= 0; --i) points.add(pivots.get(i));
        }
        else
        {
            points.add(Vec3.createVectorHelper(eiwX, eiwY, eiwZ));
        }

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
        // slack (taut => straight line), an in-flight cord gets a small loose droop.
        boolean tethered = pivots != null && !pivots.isEmpty();
        double offX = eiwX - x, offY = eiwY - y, offZ = eiwZ - z;
        List<double[]> curve = saggedCurve(pts, tethered, hook.renderSlack, owner.worldObj, offX, offY, offZ);

        Tessellator t = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE); // the cord is a two-quad tube, visible from any side
        t.startDrawingQuads();
        t.setColorOpaque_I(0x46361F); // dark rope brown
        for (int i = 0; i + 1 < curve.size(); ++i)
        {
            drawThickSegment(t, curve.get(i), curve.get(i + 1), Tuning.CORD_WIDTH);
        }
        t.draw();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** Expand the coarse pivot points into a finer, drooping polyline. */
    private List<double[]> saggedCurve(double[][] pts, boolean tethered, double slack,
            World world, double offX, double offY, double offZ)
    {
        List<double[]> curve = new ArrayList<double[]>();
        for (int i = 0; i + 1 < pts.length; ++i)
        {
            double[] a = pts[i];
            double[] b = pts[i + 1];
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double sag = spanSag(tethered, i, len, slack);
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
    private double spanSag(boolean tethered, int spanIndex, double len, double slack)
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
        // In-flight or remote cord: a small, loose droop.
        return Math.min(Tuning.CORD_SAG_MAX, len * Tuning.CORD_FLIGHT_SAG);
    }

    /** A thick segment drawn as two perpendicular quads (a square-ish tube). */
    private void drawThickSegment(Tessellator t, double[] a, double[] b, float halfWidth)
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

        addQuad(t, a, b, n1x * halfWidth, n1y * halfWidth, n1z * halfWidth);
        addQuad(t, a, b, n2x * halfWidth, n2y * halfWidth, n2z * halfWidth);
    }

    private void addQuad(Tessellator t, double[] a, double[] b, double ox, double oy, double oz)
    {
        t.addVertex(a[0] + ox, a[1] + oy, a[2] + oz);
        t.addVertex(b[0] + ox, b[1] + oy, b[2] + oz);
        t.addVertex(b[0] - ox, b[1] - oy, b[2] - oz);
        t.addVertex(a[0] - ox, a[1] - oy, a[2] - oz);
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
